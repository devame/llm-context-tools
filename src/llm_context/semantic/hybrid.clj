(ns llm-context.semantic.hybrid
  "Freshness-safe rank fusion between Datalevin FTS and LateOn candidates."
  (:require [llm-context.graph.read :as graph-read]
            [llm-context.semantic.document :as document]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.reconcile :as reconcile]
            [llm-context.semantic.state :as state]
            [llm-context.store :as store]))

(def ^:private rrf-k 60.0)

(defn- candidate-metadata [candidate]
  (let [metadata (:metadata candidate)]
    {:symbol-id (:llm_symbol_id metadata)
     :file-id (:llm_file_id metadata)
     :document-hash (:llm_document_hash metadata)
     :model-revision (:llm_model_revision metadata)
     :document-version (:llm_document_version metadata)
     :chunk-index (:llm_chunk_index metadata)}))

(defn- current-candidate?
  [lateon symbols {:keys [indexed jobs dirty-files graph-revision
                          watermark-revision]} candidate]
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
         ;; Older state may not have a watermark revision. Once present, it
         ;; must describe this exact committed graph snapshot.
         (or (nil? watermark-revision)
             (= graph-revision watermark-revision))
         (not (contains? jobs symbol-id))
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

(defn- failure-status [error]
  (if (or (= :semantic/timeout (:type (ex-data error)))
          (instance? java.util.concurrent.TimeoutException error)
          (re-find #"(?i)timed? out|timeout" (or (.getMessage error) "")))
    :timeout
    :error))

(defn search-with-metadata
  "Fuse lexical results with fresh LateOn candidates and explain semantic
  availability, latency, raw recall, freshness acceptance, and stale rejects."
  [graph client config term lexical-results]
  (let [lateon (get-in config [:semantic :lateon-code])
        lexical-ids (vec (keep :id lexical-results))
        started (System/nanoTime)
        semantic-attempt
        (if (and client (reconcile/enabled? config))
          (try
            {:status :ok
             :candidates
             (vec (index/search-text
                   client term {:top-k (:candidate-count lateon)}))}
            (catch Throwable error
              {:status (failure-status error)
               :error (or (.getMessage error) (str (class error)))
               :candidates []}))
          {:status :unavailable :candidates []})
        latency-ms (long (/ (- (System/nanoTime) started) 1000000))
        raw-semantic-candidates (:candidates semantic-attempt)
        raw-semantic-ids
        (mapv #(get-in % [:metadata :llm_symbol_id])
              raw-semantic-candidates)
        candidate-ids (distinct (concat lexical-ids raw-semantic-ids))
        db (store/database graph)
        symbols (graph-read/symbols-by-ids db candidate-ids)
        operational
        (assoc
         (graph-read/semantic-candidate-state
          db reconcile/provider raw-semantic-ids)
         :graph-revision (document/graph-revision db)
         :watermark-revision
         (:semantic.watermark/graph-revision
          (state/watermark graph reconcile/provider)))
        semantic-candidates
        (->> raw-semantic-candidates
             (filter #(current-candidate?
                       lateon symbols operational %))
             collapse-chunks)
        semantic-ids
        (mapv #(get-in % [:metadata :llm_symbol_id])
              semantic-candidates)
        lexical-set (set lexical-ids)
        semantic-set (set semantic-ids)
        lexical-scores (ranked-scores lexical-ids)
        semantic-scores (ranked-scores semantic-ids)
        all-ids (distinct (concat lexical-ids semantic-ids))
        results
        (->> all-ids
             (keep
              (fn [id]
                (when-let [symbol (get symbols id)]
                  (let [exact? (or (= term id)
                                   (= term (:name symbol))
                                   (= term (:qualified-name symbol)))]
                    (assoc symbol
                           :matched-by
                           (matched-sources id lexical-set semantic-set)
                           :score (+ (get lexical-scores id 0.0)
                                     (get semantic-scores id 0.0))
                           ::exact? exact?)))))
             (sort-by (juxt #(if (::exact? %) 0 1)
                            (comp - :score)
                            :qualified-name))
             (mapv #(dissoc % ::exact?)))
        raw-count (count raw-semantic-candidates)
        accepted-count (count semantic-candidates)
        status (if (and (= :ok (:status semantic-attempt))
                        (zero? raw-count))
                 :no-matches
                 (:status semantic-attempt))]
    {:results results
     :retrieval
     (cond-> {:status status
              :latency-ms latency-ms
              :raw-candidate-count raw-count
              :accepted-fresh-candidate-count accepted-count
              :rejected-stale-candidate-count (- raw-count accepted-count)}
       (:error semantic-attempt)
       (assoc :error (:error semantic-attempt)))}))

(defn search
  "Compatibility result-vector surface for hybrid search."
  [graph client config term lexical-results]
  (:results (search-with-metadata graph client config term lexical-results)))
