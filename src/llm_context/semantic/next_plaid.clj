(ns llm-context.semantic.next-plaid
  "NextPlaid v1.6 REST client. The rest of llm-context depends only on the
  SemanticIndex protocol and never on endpoint shapes or SQL metadata names."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [llm-context.semantic.index :as index])
  (:import [java.net ConnectException URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent CompletionException]))

(def ^:private max-error-length 2000)
(def ^:private retriable-statuses #{408 425 429 500 502 503 504})
(def ^:private expected-success #{200 201 202 204})

(defn- bounded [value]
  (let [value (str value)]
    (subs value 0 (min max-error-length (count value)))))

(defn- loopback-uri! [value]
  (let [uri (URI/create (str/replace (str value) #"/+$" ""))
        host (some-> (.getHost uri) str/lower-case)]
    (when-not (and (= "http" (.getScheme uri))
                   (contains? #{"127.0.0.1" "localhost" "::1"} host))
      (throw (ex-info "NextPlaid endpoint must be loopback HTTP"
                      {:endpoint (str uri)})))
    uri))

(defn- request-uri [^URI base path]
  (.resolve base path))

(defn- json-body [value]
  (when (some? value)
    (json/write-str value)))

(defn- java-transport [^HttpClient http ^URI base
                       {:keys [method path body timeout-ms]}]
  (let [publisher (if (some? body)
                    (HttpRequest$BodyPublishers/ofString (json-body body))
                    (HttpRequest$BodyPublishers/noBody))
        builder (doto (HttpRequest/newBuilder (request-uri base path))
                  (.timeout (Duration/ofMillis timeout-ms))
                  (.header "Accept" "application/json")
                  (.header "Content-Type" "application/json"))
        request (-> builder
                    (.method (str/upper-case (name method)) publisher)
                    .build)
        response (.send http request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn- parse-body [body]
  (when-not (str/blank? (str body))
    (try
      (json/read-str body :key-fn keyword)
      (catch Throwable _
        body))))

(defn- api-error [response request]
  (let [parsed (parse-body (:body response))
        message (or (:message parsed) (:body response)
                    (str "NextPlaid returned HTTP " (:status response)))]
    (ex-info (bounded message)
             {:type :next-plaid/api-error
              :status (:status response)
              :code (:code parsed)
              :retriable? (contains? retriable-statuses (:status response))
              :method (:method request)
              :path (:path request)})))

(defn- transport-error [error request]
  (let [cause (if (instance? CompletionException error)
                (.getCause ^CompletionException error)
                error)
        timeout? (instance? java.net.http.HttpTimeoutException cause)
        connect? (or (instance? ConnectException cause)
                     (instance? java.net.ConnectException cause))]
    (ex-info (if timeout?
               "NextPlaid request timed out"
               (if connect?
                 "NextPlaid service is unavailable"
                 (bounded (.getMessage ^Throwable cause))))
             {:type :next-plaid/transport-error
              :retriable? true
              :timeout? timeout?
              :method (:method request)
              :path (:path request)}
             cause)))

(defn- send-request
  [{:keys [transport] :as client} request]
  (try
    (let [response (transport request)]
      (assoc response :parsed (parse-body (:body response))))
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Throwable error
      (throw (transport-error error request)))))

(defn- request!
  [client request]
  (let [response (send-request client request)]
    (if (contains? expected-success (:status response))
      (:parsed response)
      (throw (api-error response request)))))

(defn- model-name-matches? [expected actual]
  (let [short-name (last (str/split expected #"/"))]
    (or (= expected actual) (= short-name actual))))

(defn- model-ready? [client model]
  (and (map? model)
       (model-name-matches? (:expected-model client) (:name model))
       (str/includes? (str (:path model)) (:expected-revision client))
       (pos-int? (:embedding_dim model))))

(defn- index-summary [health index-name]
  (first (filter #(= index-name (:name %)) (:indices health))))

(defn- metadata [document]
  {"llm_chunk_id" (:id document)
   "llm_symbol_id" (:symbol-id document)
   "llm_file_id" (:file-id document)
   "llm_document_hash" (:document-hash document)
   "llm_model_revision" (:model-revision document)
   "llm_document_version" (:document-version document)
   "llm_chunk_index" (:chunk-index document)
   "llm_chunk_count" (:chunk-count document)})

(defrecord NextPlaidClient
    [base-uri index-name expected-model expected-revision settings transport]
  index/SemanticIndex

  (index-health [client]
    (let [raw (request! client {:method :get :path "/health"
                                :timeout-ms (:health-timeout-ms settings)})
          model-ready (model-ready? client (:model raw))
          version-ready (= (:next-plaid-version settings) (:version raw))
          summary (index-summary raw index-name)]
      {:healthy? (= "healthy" (:status raw))
       :model-ready? model-ready
       :version-ready? version-ready
       :ready? (and (= "healthy" (:status raw))
                    model-ready version-ready)
       :version (:version raw)
       :model (:model raw)
       :index summary
       :updates (vec (:updates raw))
       :raw raw}))

  (ensure-index! [client]
    (let [path (str "/indices/" index-name)
          response (send-request client {:method :get :path path
                                         :timeout-ms
                                         (:health-timeout-ms settings)})]
      (case (:status response)
        200 (:parsed response)
        404
        (try
          (request! client
                    {:method :post
                     :path "/indices"
                     :timeout-ms (:health-timeout-ms settings)
                     :body {:name index-name
                            :config
                            {:nbits (:nbits settings)
                             :start_from_scratch
                             (:start-from-scratch settings)
                             :fts_tokenizer "unicode61"
                             :binary false}}})
          (catch clojure.lang.ExceptionInfo error
            ;; Another coordinator may have won declaration after our GET.
            (if (= 409 (:status (ex-data error)))
              (request! client {:method :get :path path
                                :timeout-ms (:health-timeout-ms settings)})
              (throw error))))
        (throw (api-error response {:method :get :path path})))))

  (add-documents! [client documents]
    (when-not (seq documents)
      (throw (ex-info "At least one semantic document is required"
                      {:type :next-plaid/invalid-request})))
    (request!
     client
     {:method :post
      :path (str "/indices/" index-name "/update_with_encoding")
      :timeout-ms (:update-timeout-ms settings)
      :body {:documents (mapv :text documents)
             :metadata (mapv metadata documents)
             :pool_factor (:pool-factor settings)}}))

  (delete-symbols! [client symbol-ids]
    (let [symbol-ids (vec (distinct symbol-ids))]
      (when-not (seq symbol-ids)
        (throw (ex-info "At least one semantic symbol ID is required"
                        {:type :next-plaid/invalid-request})))
      (let [placeholders (str/join ", " (repeat (count symbol-ids) "?"))]
        (request!
         client
         {:method :delete
          :path (str "/indices/" index-name "/documents")
          :timeout-ms (:update-timeout-ms settings)
          :body {:condition (str "llm_symbol_id IN (" placeholders ")")
                 :parameters symbol-ids}}))))

  (indexed-chunk-count [client symbol-id document-hash]
    (let [with-hash? (some? document-hash)
          result
          (try
            (request!
             client
             {:method :post
              :path (str "/indices/" index-name "/metadata/query")
              :timeout-ms (:health-timeout-ms settings)
              :body
              {:condition
               (if with-hash?
                 "llm_symbol_id = ? AND llm_document_hash = ?"
                 "llm_symbol_id = ?")
               :parameters
               (cond-> [symbol-id] with-hash? (conj document-hash))}})
            (catch clojure.lang.ExceptionInfo error
              (if (and (= 404 (:status (ex-data error)))
                       (contains? #{"INDEX_NOT_FOUND" "METADATA_NOT_FOUND"}
                                  (:code (ex-data error))))
                {:count 0}
                (throw error))))]
      (long (:count result 0))))

  (search-text [client query options]
    (when (str/blank? query)
      (throw (ex-info "Semantic search query must not be blank"
                      {:type :next-plaid/invalid-request})))
    (let [top-k (or (:top-k options) (:candidate-count settings))
          result
          (request!
           client
           {:method :post
            :path (str "/indices/" index-name "/search_with_encoding")
            :timeout-ms (:query-timeout-ms settings)
            :body {:queries [query]
                   :params
                   {:top_k top-k
                    :n_ivf_probe (:n-ivf-probe settings)
                    :n_full_scores (:n-full-scores settings)
                    :centroid_score_threshold
                    (:centroid-score-threshold settings)}}})
          first-result (first (:results result))]
      (mapv (fn [document-id score metadata]
              {:document-id document-id
               :score (double score)
               :metadata metadata})
            (:document_ids first-result)
            (:scores first-result)
            (:metadata first-result))))

  (close-index! [_] nil))

(defn create
  "Create a loopback-only NextPlaid client. A transport function may be
  injected for deterministic tests."
  ([endpoint settings]
   (create endpoint settings nil))
  ([endpoint settings transport]
   (let [base (loopback-uri! endpoint)
         index-name (:index-name settings)
         _ (when-not (re-matches #"[A-Za-z0-9_-]+" index-name)
             (throw (ex-info "Unsafe NextPlaid index name"
                             {:index-name index-name})))
         http (when-not transport
                (-> (HttpClient/newBuilder)
                    (.connectTimeout
                     (Duration/ofMillis (:health-timeout-ms settings)))
                    (.version HttpClient$Version/HTTP_1_1)
                    .build))
         transport (or transport #(java-transport http base %))]
     (->NextPlaidClient base index-name (:model settings)
                        (:model-revision settings) settings transport))))
