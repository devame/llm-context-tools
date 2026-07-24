(ns llm-context.semantic.next-plaid-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [llm-context.config :as config]
            [llm-context.semantic.fake-index :as fake]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.next-plaid :as next-plaid]))

(def settings
  (get-in (config/defaults) [:semantic :lateon-code]))

(defn scripted-client [responses requests]
  (next-plaid/create
   "http://127.0.0.1:8080"
   settings
   (fn [request]
     (swap! requests conj request)
     (let [response (first @responses)]
       (swap! responses subvec 1)
       response))))

(defn response [status value]
  {:status status :body (json/write-str value)})

(def ready-health
  {:status "healthy"
   :version "1.6.4"
   :indices [{:name "llm-context" :num_documents 10}]
   :updates []
   :model {:name "lightonai/LateOn-Code"
           :path (str "/models/snapshots/" (:model-revision settings))
           :embedding_dim 128}})

(def document-chunk
  {:id "symbol:a#chunk-000"
   :symbol-id "symbol:a"
   :file-id "file:src/a.clj"
   :document-hash "sha256:document"
   :model-revision (:model-revision settings)
   :document-version 1
   :chunk-index 0
   :chunk-count 1
   :text "Name: a\n\nSource:\n(defn a [] 1)"})

(deftest health-requires-the-expected-loaded-model
  (let [requests (atom [])
        client (scripted-client (atom [(response 200 ready-health)]) requests)
        health (index/index-health client)]
    (is (:healthy? health))
    (is (:model-ready? health))
    (is (:version-ready? health))
    (is (:ready? health))
    (is (= 10 (get-in health [:index :num_documents]))))
  (let [client (scripted-client
                (atom [(response 200 (assoc ready-health :model nil))])
                (atom []))]
    (is (false? (:model-ready? (index/index-health client)))))
  (let [client (scripted-client
                (atom [(response 200 (assoc ready-health :version "1.6.3"))])
                (atom []))]
    (is (false? (:version-ready? (index/index-health client)))))
  (let [client (scripted-client
                (atom [(response 200
                                  (assoc-in ready-health [:model :path]
                                            "/models/floating-main"))])
                (atom []))]
    (is (false? (:model-ready? (index/index-health client))))))

(deftest ensure-index-declares-incremental-configuration
  (let [requests (atom [])
        client (scripted-client
                (atom [(response 404 {:code "INDEX_NOT_FOUND"
                                      :message "missing"})
                       (response 201 {:name "llm-context"})])
                requests)]
    (is (= "llm-context" (:name (index/ensure-index! client))))
    (let [create-request (second @requests)]
      (is (= :post (:method create-request)))
      (is (= 0 (get-in create-request
                       [:body :config :start_from_scratch])))
      (is (= 4 (get-in create-request [:body :config :nbits]))))))

(deftest document-mutations-use-stable-private-metadata
  (let [requests (atom [])
        client (scripted-client
                (atom [(response 202 "queued")
                       (response 202 "delete queued")])
                requests)]
    (is (= "queued" (index/add-documents! client [document-chunk])))
    (is (= "delete queued"
           (index/delete-symbols! client ["symbol:a" "symbol:b"])))
    (let [add-request (first @requests)
          delete-request (second @requests)]
      (is (= ["Name: a\n\nSource:\n(defn a [] 1)"]
             (get-in add-request [:body :documents])))
      (is (= "symbol:a"
             (get-in add-request
                     [:body :metadata 0 "llm_symbol_id"])))
      (is (= 2 (count (get-in delete-request [:body :parameters]))))
      (is (= "llm_symbol_id IN (?, ?)"
             (get-in delete-request [:body :condition]))))))

(deftest metadata-count-can-check-all-or-one-hash
  (let [requests (atom [])
        client (scripted-client
                (atom [(response 200 {:count 3 :document_ids [1 2 3]})
                       (response 200 {:count 2 :document_ids [2 3]})])
                requests)]
    (is (= 3 (index/indexed-chunk-count client "symbol:a" nil)))
    (is (= 2 (index/indexed-chunk-count client "symbol:a"
                                         "sha256:document")))
    (is (= "llm_symbol_id = ?"
           (get-in (first @requests) [:body :condition])))
    (is (= "llm_symbol_id = ? AND llm_document_hash = ?"
           (get-in (second @requests) [:body :condition])))))

(deftest absent-metadata-database-means-no-visible-chunks
  (let [client (scripted-client
                (atom [(response 404 {:code "METADATA_NOT_FOUND"
                                      :message "not created yet"})])
                (atom []))]
    (is (zero? (index/indexed-chunk-count client "symbol:a" nil)))))

(deftest search-options-and-results-are-normalized
  (let [requests (atom [])
        client
        (scripted-client
         (atom [(response
                 200
                 {:results
                  [{:query_id 0
                    :document_ids [7]
                    :scores [12.5]
                    :metadata [{:llm_symbol_id "symbol:a"
                                :llm_document_hash "sha256:document"}]}]})])
         requests)
        result (index/search-text client "database retry" {:top-k 7})]
    (is (= [{:document-id 7
             :score 12.5
             :metadata {:llm_symbol_id "symbol:a"
                        :llm_document_hash "sha256:document"}}]
           result))
    (is (= 7 (get-in (first @requests) [:body :params :top_k])))
    (is (= 8 (get-in (first @requests)
                     [:body :params :n_ivf_probe])))
    (is (nil? (get-in (first @requests)
                      [:body :params :centroid_score_threshold])))))

(deftest api-errors-are-typed-bounded-and-retriable
  (let [client (scripted-client
                (atom [(response 503 {:code "SERVICE_UNAVAILABLE"
                                      :message (apply str (repeat 3000 "x"))})])
                (atom []))
        error (try (index/index-health client) nil
                   (catch clojure.lang.ExceptionInfo error error))]
    (is (= :next-plaid/api-error (:type (ex-data error))))
    (is (= 503 (:status (ex-data error))))
    (is (:retriable? (ex-data error)))
    (is (= 2000 (count (.getMessage error))))))

(deftest endpoints-must-remain-loopback-only
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"loopback"
       (next-plaid/create "http://example.com:8080" settings (constantly nil)))))

(deftest fake-index-supports-worker-and-query-tests
  (let [client (fake/create)]
    (index/ensure-index! client)
    (index/add-documents! client [document-chunk])
    (is (= 1 (index/indexed-chunk-count client "symbol:a"
                                         "sha256:document")))
    (fake/set-search-results! client [{:document-id 0 :score 1.0}])
    (is (= [{:document-id 0 :score 1.0}]
           (index/search-text client "a" {})))
    (index/delete-symbols! client ["symbol:a"])
    (is (zero? (index/indexed-chunk-count client "symbol:a" nil)))
    (index/close-index! client)
    (is (:closed? (fake/snapshot client)))))
