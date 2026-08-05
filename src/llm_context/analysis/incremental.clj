(ns llm-context.analysis.incremental
  (:require [llm-context.analysis.full :as full]
            [llm-context.graph.read :as graph-read]
            [llm-context.model.canonical-hash :as canonical-hash]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.store :as store]))

(def supported-languages full/supported-languages)

(defn index-present?
  ([project config]
   (store/with-store [graph project config]
     (index-present? graph)))
  ([graph]
   (graph-read/any-file? (store/database graph))))

(defn- changed-output?
  [existing {:keys [file]}]
  (let [record (get existing (:file/path file))]
    (or (nil? record)
        (not= (:file/content-hash file) (:hash record))
        (not= (:file/semantic-hash file) (:semantic-hash record)))))

(defn- reactivate-metadata!
  [graph metadata]
  (store/write-graph-metadata!
   graph
   {:analyzer-name (:llm-context/analyzer-name metadata)
    :analyzer-version (:llm-context/analyzer-version metadata)
    :semantic-fingerprint-version canonical-hash/contract-version
    :janet-catalog-version
    (:llm-context/janet-catalog-version metadata)
    :semantic-document-version
    (:llm-context/semantic-document-version metadata)
    :semantic-index-name (:llm-context/semantic-index-name metadata)}))

(defn commit-candidate!
  "Persist only changed/deleted files from a fully prepared candidate. The
  caller coordinates this mutation boundary."
  [graph config candidate]
  ;; Incremental mutation cannot recover an interrupted or format-incompatible
  ;; full snapshot because it has no authoritative old baseline to preserve.
  (store/assert-query-compatible! graph)
  (let [files (:files candidate)
        scanned (set (map :relative-path files))
        existing (graph-read/files-by-path (store/database graph))
        outputs (:outputs candidate)
        metadata (store/graph-metadata graph)
        fingerprint-compatible?
        (= canonical-hash/contract-version
           (:llm-context/semantic-fingerprint-version metadata))
        changed (if fingerprint-compatible?
                  (filterv #(changed-output? existing %) outputs)
                  outputs)
        deleted (->> (keys existing) (remove scanned) sort vec)
        updating? (boolean (or (seq changed) (seq deleted)))
        metadata (when updating? metadata)]
    ;; File replacements are individually atomic, but a cross-file semantic
    ;; snapshot may span several of them. Mark the graph unavailable before
    ;; the first mutation so a process interruption cannot advertise a mixed
    ;; project revision as ready.
    (when updating?
      (store/begin-full-replacement! graph))
    (doseq [path deleted]
      (let [file-id (get-in existing [path :id])]
        (if (semantic-reconcile/enabled? config)
          (store/delete-file-and-mark!
           graph file-id
           [(semantic-reconcile/dirty-entity file-id nil :delete)])
          (store/delete-file! graph file-id))))
    (doseq [{:keys [file entities]} changed]
      (if (semantic-reconcile/enabled? config)
        (store/replace-file-and-mark!
         graph file entities
         [(semantic-reconcile/dirty-entity
           (:file/id file) (:file/content-hash file) :upsert)])
        (store/replace-file! graph file entities)))
    (store/prune-orphan-topics! graph)
    (let [result
          {:mode :incremental
           :files (count files)
           :changed (count changed)
           :deleted (count deleted)
           :entities (reduce + 0 (map #(inc (count (:entities %))) changed))
           :analysis-metrics (:analysis-metrics candidate)
           :analyzers (:analyzers candidate)
           :diagnostics (:diagnostics candidate)
           :started (:started candidate)}]
      (when updating?
        (reactivate-metadata! graph metadata))
      result)))

(defn finish-candidate!
  [graph project config result]
  (let [semantic-plan (semantic-reconcile/reconcile! graph project config)]
    (-> result
        (dissoc :started)
        (assoc :semantic semantic-plan)
        (update :diagnostics into (:diagnostics semantic-plan)))))

(defn- analyze-graph! [graph project config]
  (let [candidate (full/prepare-current project config nil :incremental)]
    (if (:stale? candidate)
      candidate
      (finish-candidate!
       graph project config (commit-candidate! graph config candidate)))))

(defn analyze!
  "Run authoritative project analyzers, then persist only source or semantic
  fingerprints that changed."
  ([project config]
   (store/with-store [graph project config]
     (analyze-graph! graph project config)))
  ([graph project config]
   (analyze-graph! graph project config)))
