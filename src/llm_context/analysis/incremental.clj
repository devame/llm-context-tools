(ns llm-context.analysis.incremental
  (:require [llm-context.analysis.effects :as effects]
            [llm-context.analysis.files :as files]
            [llm-context.analysis.resolve :as resolve]
            [llm-context.analysis.structural :as structural]
            [llm-context.graph.read :as graph-read]
            [llm-context.indexer :as indexer]
            [llm-context.model.ids :as ids]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.store :as store]))

(defn index-present?
  ([project config]
   (store/with-store [graph project config]
     (index-present? graph)))
  ([graph]
   (graph-read/any-file? (store/database graph))))

(defn- existing-files [graph]
  (graph-read/files-by-path (store/database graph)))

(defn- analyze-graph! [graph project config]
  (let [{:keys [files diagnostics]}
        (files/discover project config (jtreesitter/available-languages))
        scanned (into {} (map (juxt :relative-path identity) files))
        existing (existing-files graph)
        changed (->> files
                     (filter #(not= (ids/content-hash (:content %))
                                    (get-in existing [(:relative-path %) :hash])))
                     vec)
        deleted (->> (keys existing) (remove (set (keys scanned))) vec)
        existing-affected-file-ids
        (->> (concat (map :relative-path changed) deleted)
             (keep #(get-in existing [% :id]))
             set)
        old-identities
        (graph-read/symbol-identities-for-files
         (store/database graph) existing-affected-file-ids)
        extracted
        (if (seq changed)
          (with-open [parser (jtreesitter/open project)]
            (let [analyzer (structural/create parser)]
              (mapv (fn [file]
                      (let [output (indexer/index-file analyzer file)
                            edges (filter :edge/id (:entities output))]
                        (update output :entities into
                                (effects/analyze (:language file) edges))))
                    changed)))
          [])]
    (doseq [path deleted]
      (let [file-id (get-in existing [path :id])]
        (if (semantic-reconcile/enabled? config)
          (store/delete-file-and-mark!
           graph file-id
           [(semantic-reconcile/dirty-entity file-id nil :delete)])
          (store/delete-file! graph file-id))))
    (doseq [{:keys [file entities]} extracted]
      (if (semantic-reconcile/enabled? config)
        (store/replace-file-and-mark!
         graph file entities
         [(semantic-reconcile/dirty-entity
           (:file/id file) (:file/content-hash file) :upsert)])
        (store/replace-file! graph file entities)))
    (let [changed-file-ids
          (set (concat existing-affected-file-ids
                       (map (comp :file/id :file) extracted)))
          db (store/database graph)
          new-identities
          (graph-read/symbol-identities-for-files db changed-file-ids)
          identities (concat old-identities new-identities)
          changed-names (set (map :name identities))
          changed-qualified (set (map :qualified-name identities))
          edge-ids (graph-read/affected-edge-ids
                    db changed-file-ids changed-names changed-qualified)
          edges (graph-read/edge-resolution-inputs db edge-ids)
          symbols (graph-read/resolution-candidate-symbols db edges)
          exact {}]
      (when (seq edges)
        (store/reconcile-edges!
         graph (resolve/resolution-decisions symbols edges exact)))
      (let [semantic-plan
            (semantic-reconcile/reconcile! graph project config)]
        {:mode :incremental
           :files (count files)
           :changed (count changed)
           :deleted (count deleted)
           :entities (reduce + (map #(inc (count (:entities %))) extracted))
           :semantic semantic-plan
           :diagnostics (vec (concat diagnostics
                                     (mapcat :diagnostics extracted)
                                     (:diagnostics semantic-plan)))}))))

(defn analyze!
  "Incrementally analyze into either a caller-owned graph or a temporary
  project connection."
  ([project config]
   (store/with-store [graph project config]
     (analyze-graph! graph project config)))
  ([graph project config]
   (analyze-graph! graph project config)))
