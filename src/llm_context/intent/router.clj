(ns llm-context.intent.router
  "Resident, optional Mixedbread query-shape router. It reuses the pinned
  NextPlaid ONNX runtime and contributes an advisory prior only."
  (:require [clojure.string :as str]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.next-plaid :as next-plaid]
            [llm-context.semantic.runtime :as semantic-runtime])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.net ServerSocket]
           [java.nio.file Files Path]
           [java.util.concurrent TimeUnit]))

(def ^:private route-documents
  [{:shape :lookup
    :text "Find one principal code definition, implementation, owner, or source-of-truth location."}
   {:shape :set
    :text "Find a bounded collection of distinct code elements, modules, implementations, or locations."}
   {:shape :flow
    :text "Explain an ordered process by following execution, calls, data movement, or other code relationships."}])

(defprotocol QueryShapeRouter
  (classify [router query] "Return an advisory query-shape result.")
  (close-router! [router]))

(defn- route-id [shape]
  (str "query-shape/" (name shape)))

(defrecord NextPlaidRouter [client process settings]
  QueryShapeRouter
  (classify [_ query]
    (let [started (System/nanoTime)
          results (index/search-text client query
                                     {:top-k 3
                                      :timeout-ms (:query-timeout-ms settings)})
          scores (into {}
                       (keep (fn [{:keys [score metadata]}]
                               (when-let [id (or (:llm_symbol_id metadata)
                                                (:symbol-id metadata))]
                                 (let [shape (keyword (last (str/split id #"/")))]
                                   (when (contains? #{:lookup :set :flow} shape)
                                     [shape (double score)])))))
                       results)
          ranked (sort-by (comp - val) scores)
          suggestion (some-> ranked first key)
          margin (when (>= (count ranked) 2)
                   (- (double (val (first ranked)))
                      (double (val (second ranked)))))]
      (if suggestion
        {:provider :mixedbread-32m
         :model (:model settings)
         :model-revision (:model-revision settings)
         :status :available
         :suggested-shape suggestion
         :scores scores
         :margin margin
         :latency-ms (long (/ (- (System/nanoTime) started) 1000000))}
        {:provider :mixedbread-32m :status :invalid-response
         :detail "NextPlaid returned no recognized route documents"})))
  (close-router! [_]
    (index/close-index! client)
    (.destroy ^Process process)
    (when-not (.waitFor ^Process process 5 TimeUnit/SECONDS)
      (.destroyForcibly ^Process process))
    nil))

(defn unavailable
  ([reason] (unavailable reason nil))
  ([reason detail]
   (reify QueryShapeRouter
     (classify [_ _]
       (cond-> {:provider :mixedbread-32m :status :unavailable
                :reason reason}
         detail (assoc :detail detail)))
     (close-router! [_] nil))))

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- process-command [executable port index-path model-path settings]
  (vec (concat [(str executable)
                "--host" "127.0.0.1"
                "--port" (str port)
                "--index-dir" (str index-path)
                "--model" (str model-path)]
               (when (= :int8 (:quantization settings)) ["--int8"])
               ["--parallel" (str (:encoding-sessions settings))
                "--batch-size" (str (:encoding-batch-size settings))
                "--query-length" "48"
                "--document-length" "128"])))

(defn- await-ready! [runtime settings]
  (let [deadline (+ (System/currentTimeMillis) (:startup-timeout-ms settings))]
    (loop [last-error nil]
      (cond
        (not (.isAlive ^Process (:process runtime)))
        (throw (ex-info "Query router exited before becoming ready"
                        {:type :query-router/runtime-exited}
                        last-error))

        (>= (System/currentTimeMillis) deadline)
        (throw (ex-info "Timed out waiting for the query router model"
                        {:type :query-router/runtime-timeout}
                        last-error))

        :else
        (let [attempt (try {:health (index/index-health (:client runtime))}
                           (catch Throwable error {:error error}))]
          (if (get-in attempt [:health :ready?])
            (:health attempt)
            (do (Thread/sleep 100)
                (recur (or (:error attempt)
                           (ex-info "Query router is not ready"
                                    {:health (:health attempt)}))))))))))

(defn- visible-route-ids [client ids]
  (try
    (set (map :symbol-id (index/indexed-documents client ids)))
    (catch clojure.lang.ExceptionInfo error
      ;; A newly declared NextPlaid index has no metadata columns until its
      ;; first asynchronous update commits.
      (if (and (= :next-plaid/api-error (:type (ex-data error)))
               (str/includes? (.getMessage error) "Unknown column"))
        #{}
        (throw error)))))

(defn- ensure-route-documents! [client settings]
  (index/ensure-index! client)
  (let [ids (mapv (comp route-id :shape) route-documents)
        existing (visible-route-ids client ids)
        missing (remove #(contains? existing (route-id (:shape %)))
                        route-documents)]
    (when (seq missing)
      (index/add-documents!
       client
       (mapv (fn [{:keys [shape text]}]
               (let [id (route-id shape)]
                 {:id id :symbol-id id :file-id "query-shape/routes"
                  :document-hash (str "query-shape-v1/" (name shape))
                  :model-revision (:model-revision settings)
                  :document-version 1 :chunk-index 0 :chunk-count 1
                  :text text}))
             missing)))
    ;; Encoding updates are asynchronous. Do not publish a ready router until
    ;; all three immutable route documents are query-visible.
    (let [deadline (+ (System/currentTimeMillis) (:update-timeout-ms settings))]
      (loop []
        (let [visible (visible-route-ids client ids)]
          (cond
            (= (set ids) visible) true
            (>= (System/currentTimeMillis) deadline)
            (throw (ex-info "Timed out waiting for query-router documents"
                            {:type :query-router/document-timeout
                             :expected ids :visible visible}))
            :else (do (Thread/sleep 50) (recur))))))))

(defn- warm-router! [client settings]
  ;; Health and metadata visibility do not force NextPlaid to load every
  ;; search page. Pay that one-time cost before publishing :ready.
  (let [results (index/search-text
                 client "Find one principal code location."
                 {:top-k 3 :timeout-ms (:startup-timeout-ms settings)})]
    (when-not (= 3 (count results))
      (throw (ex-info "Query-router warm-up returned incomplete results"
                      {:type :query-router/warmup-failed
                       :result-count (count results)})))))

(defn start!
  "Start one small ONNX router beside the repository retrieval runtime.
  Missing optional artifacts degrade to an unavailable advisory signal."
  [project config]
  (let [settings (get-in config [:context :query-router])]
    (if-not (:enabled settings)
      {:status :disabled :client (unavailable :disabled)}
      (let [command (:next-plaid-command settings)
            executable (semantic-runtime/find-executable (first command))
            model (semantic-runtime/model-path project settings)]
        (cond
          (nil? executable)
          {:status :unavailable :reason :executable-missing
           :detail (first command)
           :client (unavailable :executable-missing (first command))}

          (not (Files/isDirectory model (make-array java.nio.file.LinkOption 0)))
          {:status :unavailable :reason :model-missing :detail (str model)
           :client (unavailable :model-missing (str model))}

          :else
          (let [port (free-port)
                index-path (.normalize (.resolve ^Path (:root project)
                                                 (:index-path settings)))
                log-directory (.resolve ^Path (:state-dir project) "logs")
                log-path (.resolve log-directory "query-router.log")
                _ (Files/createDirectories
                   index-path (make-array java.nio.file.attribute.FileAttribute 0))
                _ (Files/createDirectories
                   log-directory (make-array java.nio.file.attribute.FileAttribute 0))
                full-command (concat (process-command executable port index-path
                                                        model settings)
                                     (next command))
                builder (doto (ProcessBuilder. ^java.util.List (vec full-command))
                          (.redirectErrorStream true)
                          (.redirectOutput
                           (ProcessBuilder$Redirect/appendTo (.toFile log-path))))
                _ (when-let [onnx-runtime
                             (semantic-runtime/onnx-runtime-path executable)]
                    (.put (.environment builder) "ORT_DYLIB_PATH"
                          (str onnx-runtime)))
                process (.start builder)
                endpoint (str "http://127.0.0.1:" port)
                client (next-plaid/create endpoint settings)
                runtime {:process process :client client}]
            (try
              (let [health (await-ready! runtime settings)]
                (ensure-route-documents! client settings)
                (warm-router! client settings)
                {:status :ready :health health :client (->NextPlaidRouter
                                                        client process settings)
                 :endpoint endpoint :log-path log-path
                 :model (:model settings)
                 :model-revision (:model-revision settings)})
              (catch Throwable error
                (.destroy process)
                (when-not (.waitFor process 5 TimeUnit/SECONDS)
                  (.destroyForcibly process))
                (throw error)))))))))

(defn stop! [runtime]
  (when-let [client (:client runtime)]
    (close-router! client)))
