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

(defn- analyze-graph! [graph project config]
  (let [{:keys [files diagnostics]}
        (files/discover project config supported-languages)
        scanned (set (map :relative-path files))
        existing (graph-read/files-by-path (store/database graph))
        snapshot (project-analyzer/analyze project files)
        outputs (:outputs snapshot)
        changed (filterv #(and (not (:preserve? %))
                               (changed-output? existing %))
                         outputs)
        deleted (->> (keys existing) (remove scanned) sort vec)]
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
          (semantic-reconcile/reconcile! graph project config)]
      {:mode :incremental
       :files (count files)
       :changed (count changed)
       :deleted (count deleted)
       :entities (reduce + (map #(inc (count (:entities %))) changed))
       :semantic semantic-plan
       :analyzers (:analyzers snapshot)
       :diagnostics (vec (concat diagnostics
                                 (:diagnostics snapshot)
                                 (mapcat :diagnostics outputs)
                                 (:diagnostics semantic-plan)))})))

(defn analyze!
  "Run authoritative project analyzers, then persist only source or semantic
  fingerprints that changed."
  ([project config]
   (store/with-store [graph project config]
     (analyze-graph! graph project config)))
  ([graph project config]
   (analyze-graph! graph project config)))
