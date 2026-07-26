(ns llm-context.semantic.hybrid-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.query :as query]
            [llm-context.query-test :as fixture]
            [llm-context.semantic.fake-index :as fake]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.reconcile :as reconcile]
            [llm-context.semantic.state :as state]
            [llm-context.store :as store]))

(def settings
  (assoc-in (config/defaults) [:semantic :providers] [:lateon-code]))

(defn graph-fixture []
  (update (fixture/fixture) :entities
          (fn [entities]
            (mapv (fn [entity]
                    (if (= :entity.type/symbol (:entity/type entity))
                      (assoc entity
                             :symbol/scope :scope/top-level
                             :symbol/role :role/definition
                             :symbol/indexable? true)
                      entity))
                  entities))))

(defn indexed [symbol-id file-id hash]
  {:provider reconcile/provider
   :symbol-id symbol-id
   :file-id file-id
   :document-hash hash
   :model-revision
   (get-in settings [:semantic :lateon-code :model-revision])
   :document-version
   (get-in settings [:semantic :lateon-code :document-version])
   :chunk-count 2
   :updated-at 10})

(defn candidate [symbol-id file-id hash chunk score]
  {:document-id chunk
   :score score
   :metadata
   {:llm_symbol_id symbol-id
    :llm_file_id file-id
    :llm_document_hash hash
    :llm_model_revision
    (get-in settings [:semantic :lateon-code :model-revision])
    :llm_document_version
    (get-in settings [:semantic :lateon-code :document-version])
    :llm_chunk_index chunk}})

(deftest semantic-only-result-is-hydrated-from-the-graph
  (let [{:keys [project file entities]} (graph-fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (store/replace-file! graph file entities)
      (state/put-indexed!
       graph (indexed "symbol:caller" (:file/id file) "sha256:caller"))
      (fake/set-search-results!
       client [(candidate "symbol:caller" (:file/id file)
                          "sha256:caller" 0 10.0)])
      (let [result (query/search graph client settings
                                 "retry network request")]
        (is (= ["symbol:caller"] (mapv :id result)))
        (is (= #{:lateon} (:matched-by (first result))))
        (is (= "src/a.clj" (:file (first result))))))))

(deftest multiple-chunks-collapse-to-the-best-symbol-score
  (let [{:keys [project file entities]} (graph-fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (store/replace-file! graph file entities)
      (state/put-indexed!
       graph (indexed "symbol:caller" (:file/id file) "sha256:caller"))
      (fake/set-search-results!
       client [(candidate "symbol:caller" (:file/id file)
                          "sha256:caller" 0 2.0)
               (candidate "symbol:caller" (:file/id file)
                          "sha256:caller" 1 12.0)])
      (is (= 1 (count (query/search graph client settings "network retry")))))))

(deftest pending-dirty-deleted-and-model-mismatched-results-are-rejected
  (let [{:keys [project file entities]} (graph-fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (store/replace-file! graph file entities)
      (state/put-indexed!
       graph (indexed "symbol:caller" (:file/id file) "sha256:caller"))
      (fake/set-search-results!
       client [(candidate "symbol:caller" (:file/id file)
                          "sha256:caller" 0 10.0)])
      (state/enqueue-job!
       graph {:provider reconcile/provider
              :symbol-id "symbol:caller" :file-id (:file/id file)
              :operation :upsert :document-hash "sha256:new"
              :available-at 10 :updated-at 10})
      (is (empty? (query/search graph client settings "unmatched concept")))
      (state/cancel-job! graph reconcile/provider "symbol:caller")
      (state/mark-dirty!
       graph {:provider reconcile/provider :file-id (:file/id file)
              :file-hash (:file/content-hash file)
              :operation :upsert :created-at 20})
      (is (empty? (query/search graph client settings "unmatched concept")))
      (state/clear-dirty! graph reconcile/provider (:file/id file))
      (fake/set-search-results!
       client [(assoc-in (candidate "symbol:caller" (:file/id file)
                                    "sha256:caller" 0 10.0)
                         [:metadata :llm_model_revision]
                         (apply str (repeat 40 "a")))])
      (is (empty? (query/search graph client settings "unmatched concept")))
      (store/delete-file! graph (:file/id file))
      (is (empty? (query/search graph client settings "unmatched concept"))))))

(deftest stale-graph-watermark-rejects-semantic-results-but-keeps-fts
  (let [{:keys [project file entities]} (graph-fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (store/replace-file! graph file entities)
      (state/put-indexed!
       graph (indexed "symbol:caller" (:file/id file) "sha256:caller"))
      (state/record-watermark!
       graph {:provider reconcile/provider :state :ready
              :graph-revision "sha256:stale"})
      (fake/set-search-results!
       client [(candidate "symbol:caller" (:file/id file)
                          "sha256:caller" 0 10.0)])
      (let [result (query/search graph client settings
                                 "persistent database")]
        (is (= ["symbol:caller"] (mapv :id result)))
        (is (= #{:fts} (:matched-by (first result))))))))

(deftest exact-lexical-match-keeps-priority-and-fuses-provenance
  (let [{:keys [project file entities]} (graph-fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (store/replace-file! graph file entities)
      (doseq [[id hash] [["symbol:caller" "sha256:caller"]
                         ["symbol:callee" "sha256:callee"]]]
        (state/put-indexed! graph (indexed id (:file/id file) hash)))
      (fake/set-search-results!
       client [(candidate "symbol:callee" (:file/id file)
                          "sha256:callee" 0 20.0)
               (candidate "symbol:caller" (:file/id file)
                          "sha256:caller" 0 10.0)])
      (let [result (query/search graph client settings "caller")]
        (is (= "symbol:caller" (:id (first result))))
        (is (= #{:fts :lateon} (:matched-by (first result))))))))

(deftest semantic-failure-falls-back-to-datalevin
  (let [{:keys [project file entities]} (graph-fixture)
        failing
        (reify index/SemanticIndex
          (index-health [_] {:ready? true})
          (ensure-index! [_] nil)
          (add-documents! [_ _] nil)
          (delete-symbols! [_ _] nil)
          (indexed-chunk-count [_ _ _] 0)
          (search-text [_ _ _]
            (throw (ex-info "offline" {:retriable? true})))
          (close-index! [_] nil))]
    (store/with-store [graph project settings]
      (store/replace-file! graph file entities)
      (let [result (query/search graph failing settings "persistent database")]
        (is (= ["symbol:caller"] (mapv :id result)))
        (is (= #{:fts} (:matched-by (first result))))))))

(deftest retrieval-explanation-distinguishes-timeouts-errors-and-no-matches
  (let [{:keys [project file entities]} (graph-fixture)
        client
        (fn [error]
          (reify index/SemanticIndex
            (index-health [_] {:ready? true})
            (ensure-index! [_] nil)
            (add-documents! [_ _] nil)
            (delete-symbols! [_ _] nil)
            (indexed-chunk-count [_ _ _] 0)
            (search-text [_ _ _] (throw error))
            (close-index! [_] nil)))]
    (store/with-store [graph project settings]
      (store/replace-file! graph file entities)
      (let [timeout
            (query/search-explain
             graph (client (java.util.concurrent.TimeoutException. "slow"))
             settings "persistent database")
            failure
            (query/search-explain
             graph (client (ex-info "offline" {}))
             settings "persistent database")
            no-matches
            (query/search-explain graph (fake/create) settings
                                  "persistent database")]
        (is (= :timeout (get-in timeout [:retrieval :status])))
        (is (= :error (get-in failure [:retrieval :status])))
        (is (= :no-matches (get-in no-matches [:retrieval :status])))
        (is (= ["symbol:caller"] (mapv :id (:results timeout))))
        (is (= 0 (get-in timeout [:retrieval :raw-candidate-count])))))))

(deftest fresh-and-stale-candidate-counts-are-observable
  (let [{:keys [project file entities]} (graph-fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (store/replace-file! graph file entities)
      (state/put-indexed!
       graph (indexed "symbol:caller" (:file/id file) "sha256:caller"))
      (fake/set-search-results!
       client [(candidate "symbol:caller" (:file/id file)
                          "sha256:caller" 0 10.0)
               (candidate "symbol:callee" (:file/id file)
                          "sha256:stale" 0 9.0)])
      (let [retrieval
            (:retrieval
             (query/search-explain graph client settings "semantic concept"))]
        (is (= :ok (:status retrieval)))
        (is (= 2 (:raw-candidate-count retrieval)))
        (is (= 1 (:accepted-fresh-candidate-count retrieval)))
        (is (= 1 (:rejected-stale-candidate-count retrieval)))))))

(deftest interactive-deadline-allows-a-subsecond-lateon-response
  (let [{:keys [project file entities]} (graph-fixture)
        slow
        (reify index/SemanticIndex
          (index-health [_] {:ready? true})
          (ensure-index! [_] nil)
          (add-documents! [_ _] nil)
          (delete-symbols! [_ _] nil)
          (indexed-chunk-count [_ _ _] 0)
          (search-text [_ _ _]
            (Thread/sleep 600)
            [])
          (close-index! [_] nil))]
    (is (= 1500 (get-in settings
                        [:semantic :lateon-code :query-timeout-ms])))
    (store/with-store [graph project settings]
      (store/replace-file! graph file entities)
      (let [retrieval
            (:retrieval
             (query/search-explain graph slow settings
                                   "persistent database"))]
        (is (= :no-matches (:status retrieval)))
        (is (>= (:latency-ms retrieval) 500))
        (is (= 0 (:raw-candidate-count retrieval)))))))
