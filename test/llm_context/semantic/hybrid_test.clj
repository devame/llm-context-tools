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

(defn indexed [symbol-id file-id hash]
  {:provider reconcile/provider
   :symbol-id symbol-id
   :file-id file-id
   :document-hash hash
   :model-revision
   (get-in settings [:semantic :lateon-code :model-revision])
   :document-version 1
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
    :llm_document_version 1
    :llm_chunk_index chunk}})

(deftest semantic-only-result-is-hydrated-from-the-graph
  (let [{:keys [project file entities]} (fixture/fixture)
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
  (let [{:keys [project file entities]} (fixture/fixture)
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
  (let [{:keys [project file entities]} (fixture/fixture)
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

(deftest exact-lexical-match-keeps-priority-and-fuses-provenance
  (let [{:keys [project file entities]} (fixture/fixture)
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
  (let [{:keys [project file entities]} (fixture/fixture)
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
