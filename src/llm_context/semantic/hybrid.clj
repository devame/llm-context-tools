(ns llm-context.semantic.hybrid
  "Freshness-safe rank fusion between Datalevin FTS and LateOn candidates."
  (:require [datalevin.core :as d]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.reconcile :as reconcile]
            [llm-context.semantic.state :as state]
            [llm-context.store :as store]))

(def ^:private rrf-k 60.0)

(defn- graph-symbols [graph]
  (let [db (store/database graph)
        eids (d/q '[:find [?symbol ...]
                    :where [?symbol :symbol/id _]]
                  db)]
    (into {}
          (map
           (fn [eid]
             (let [symbol (d/pull db '[*] eid)
                   file-ref (:symbol/file symbol)
                   file-eid (if (map? file-ref) (:db/id file-ref) file-ref)
                   file (d/pull db '[*] file-eid)]
               [(:symbol/id symbol)
                {:id (:symbol/id symbol)
                 :name (:symbol/name symbol)
                 :qualified-name (:symbol/qualified-name symbol)
                 :kind (:symbol/kind symbol)
                 :file (:file/path file)
                 :file-id (:file/id file)
                 :line (:source/start-line symbol)
                 :signature (:symbol/signature symbol)}]))
           eids))))

(defn- operational-state [graph]
  {:indexed
   (into {}
         (map (juxt :semantic.indexed/symbol-id identity))
         (state/indexed-records graph reconcile/provider))
   :pending
   (set (map :semantic.job/symbol-id
             (state/job-records graph reconcile/provider)))
   :dirty-files
   (set (map :semantic.dirty/file-id
             (state/dirty-records graph reconcile/provider)))})

(defn- candidate-metadata [candidate]
  (let [metadata (:metadata candidate)]
    {:symbol-id (:llm_symbol_id metadata)
     :file-id (:llm_file_id metadata)
     :document-hash (:llm_document_hash metadata)
     :model-revision (:llm_model_revision metadata)
     :document-version (:llm_document_version metadata)
     :chunk-index (:llm_chunk_index metadata)}))

(defn- current-candidate?
  [lateon symbols {:keys [indexed pending dirty-files]} candidate]
  (let [{:keys [symbol-id file-id document-hash model-revision
                document-version]} (candidate-metadata candidate)
        symbol (get symbols symbol-id)
        recorded (get indexed symbol-id)]
    (and symbol
         recorded
         (integer? document-version)
         (= file-id (:file-id symbol))
         (= file-id (:semantic.indexed/file-id recorded))
         (= document-hash (:semantic.indexed/document-hash recorded))
         (= model-revision (:semantic.indexed/model-revision recorded))
         (= (long document-version)
            (:semantic.indexed/document-version recorded))
         (= model-revision (:model-revision lateon))
         (= (long document-version) (:document-version lateon))
         (not (contains? pending symbol-id))
         (not (contains? dirty-files file-id))
         (not (contains? dirty-files reconcile/project-marker)))))

(defn- collapse-chunks [candidates]
  (->> candidates
       (reduce
        (fn [by-symbol candidate]
          (let [symbol-id (:symbol-id (candidate-metadata candidate))
                existing (get by-symbol symbol-id)]
            (if (or (nil? existing)
                    (> (:score candidate) (:score existing)))
              (assoc by-symbol symbol-id candidate)
              by-symbol)))
        {})
       vals
       (sort-by (juxt (comp - :score)
                      (comp :symbol-id candidate-metadata)))
       vec))

(defn- ranked-scores [ids]
  (into {}
        (map-indexed
         (fn [index id]
           [id (/ 1.0 (+ rrf-k (inc index)))])
         ids)))

(defn- matched-sources [id lexical-ids semantic-ids]
  (cond-> #{}
    (contains? lexical-ids id) (conj :fts)
    (contains? semantic-ids id) (conj :lateon)))

(defn search
  "Fuse already-ranked lexical results with fresh LateOn candidates.

  Exact lexical identifiers remain first. Semantic failures intentionally
  collapse to lexical-only results."
  [graph client config term lexical-results]
  (let [lateon (get-in config [:semantic :lateon-code])
        symbols (graph-symbols graph)
        lexical-ids (vec (keep :id lexical-results))
        semantic-candidates
        (if (and client (reconcile/enabled? config))
          (try
            (let [operational (operational-state graph)]
              (->> (index/search-text
                    client term {:top-k (:candidate-count lateon)})
                   (filter #(current-candidate?
                             lateon symbols operational %))
                   collapse-chunks))
            (catch Throwable _
              []))
          [])
        semantic-ids
        (mapv #(get-in % [:metadata :llm_symbol_id])
              semantic-candidates)
        lexical-set (set lexical-ids)
        semantic-set (set semantic-ids)
        lexical-scores (ranked-scores lexical-ids)
        semantic-scores (ranked-scores semantic-ids)
        all-ids (distinct (concat lexical-ids semantic-ids))]
    (->> all-ids
         (keep
          (fn [id]
            (when-let [symbol (get symbols id)]
              (let [exact? (contains? #{id (:name symbol)
                                        (:qualified-name symbol)}
                                      term)]
                (assoc symbol
                       :matched-by
                       (matched-sources id lexical-set semantic-set)
                       :score (+ (get lexical-scores id 0.0)
                                 (get semantic-scores id 0.0))
                       ::exact? exact?)))))
         (sort-by (juxt #(if (::exact? %) 0 1)
                        (comp - :score)
                        :qualified-name))
         (mapv #(dissoc % ::exact?)))))
