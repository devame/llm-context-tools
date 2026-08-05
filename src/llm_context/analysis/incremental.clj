(ns llm-context.analysis.incremental
  (:require [llm-context.analysis.files :as files]
            [llm-context.analysis.project-analyzer :as project-analyzer]
            [llm-context.graph.read :as graph-read]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.store :as store]))

(def supported-languages
  #{:language/clojure :language/clojurescript :language/clojure-common
    :language/janet :language/edn-data})

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
    :janet-catalog-version
    (:llm-context/janet-catalog-version metadata)
    :semantic-document-version
    (:llm-context/semantic-document-version metadata)
    :semantic-index-name (:llm-context/semantic-index-name metadata)}))

(defn- analyze-graph! [graph project config]
  ;; Incremental mutation cannot recover an interrupted or format-incompatible
  ;; full snapshot because it has no authoritative old baseline to preserve.
  (store/assert-query-compatible! graph)
  (let [{:keys [files diagnostics]}
        (files/discover project config supported-languages)
        scanned (set (map :relative-path files))
        existing (graph-read/files-by-path (store/database graph))
        snapshot (project-analyzer/analyze project files)
        outputs (:outputs snapshot)
        preserved (filterv :preserve? outputs)
        _ (when (seq preserved)
            ;; Without a persisted analyzer IR, mixing last-good stored facts
            ;; with a newly resolved project snapshot would be internally
            ;; inconsistent. Fail the complete incremental update before any
            ;; mutation rather than changing callers around a malformed file.
            (throw
             (ex-info
              "Incremental analysis produced an incomplete snapshot; existing graph was preserved"
              {:exit-code 1
               :type :analysis/incomplete-snapshot
               :files (mapv (comp :file/path :file) preserved)
               :diagnostics
               (vec (concat diagnostics
                            (:diagnostics snapshot)
                            (mapcat :diagnostics preserved)))})))
        changed (filterv #(changed-output? existing %) outputs)
        deleted (->> (keys existing) (remove scanned) sort vec)
        updating? (boolean (or (seq changed) (seq deleted)))
        metadata (when updating? (store/graph-metadata graph))]
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
    (let [semantic-plan
          (semantic-reconcile/reconcile! graph project config)
          result
          {:mode :incremental
           :files (count files)
           :changed (count changed)
           :deleted (count deleted)
           :entities (reduce + (map #(inc (count (:entities %))) changed))
           :analysis-metrics (:analysis-metrics snapshot)
           :semantic semantic-plan
           :analyzers (:analyzers snapshot)
           :diagnostics (vec (concat diagnostics
                                     (:diagnostics snapshot)
                                     (mapcat :diagnostics outputs)
                                     (:diagnostics semantic-plan)))}]
      (when updating?
        (reactivate-metadata! graph metadata))
      result)))

(defn analyze!
  "Run authoritative project analyzers, then persist only source or semantic
  fingerprints that changed."
  ([project config]
   (store/with-store [graph project config]
     (analyze-graph! graph project config)))
  ([graph project config]
   (analyze-graph! graph project config)))
