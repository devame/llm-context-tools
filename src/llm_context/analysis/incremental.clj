(ns llm-context.analysis.incremental
  (:require [llm-context.analysis.effects :as effects]
            [llm-context.analysis.files :as files]
            [llm-context.analysis.resolve :as resolve]
            [llm-context.analysis.structural :as structural]
            [llm-context.indexer :as indexer]
            [llm-context.model.ids :as ids]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.scip :as scip]
            [llm-context.store :as store]))

(defn index-present? [project config]
  (store/with-store [graph project config]
    (boolean (seq (store/query graph
                               '[:find [?id ...] :where [_ :file/id ?id]] [])))))

(defn- existing-files [graph]
  (into {}
        (map (fn [[id path hash]] [path {:id id :hash hash}]))
        (store/query graph
                     '[:find ?id ?path ?hash
                       :where [?file :file/id ?id]
                              [?file :file/path ?path]
                              [?file :file/content-hash ?hash]]
                     [])))

(defn- graph-symbols [graph]
  (mapv (fn [[id name qualified path sl sc el ec]]
          {:symbol-id id :name name :qualified-name qualified :file-path path
           :source/start-line sl :source/start-column sc
           :source/end-line el :source/end-column ec})
        (store/query graph
                     '[:find ?id ?name ?qualified ?path ?sl ?sc ?el ?ec
                       :where [?symbol :symbol/id ?id]
                              [?symbol :symbol/name ?name]
                              [?symbol :symbol/qualified-name ?qualified]
                              [?symbol :symbol/file ?file]
                              [?file :file/path ?path]
                              [?symbol :source/start-line ?sl]
                              [?symbol :source/start-column ?sc]
                              [?symbol :source/end-line ?el]
                              [?symbol :source/end-column ?ec]]
                     [])))

(defn- graph-edges [graph]
  (let [targets (into {}
                      (store/query graph
                                   '[:find ?id ?target-id
                                     :where [?edge :edge/id ?id]
                                            [?edge :edge/to ?target]
                                            [?target :symbol/id ?target-id]]
                                   []))]
    (mapv (fn [[id kind target resolution confidence path sl sc el ec]]
            {:edge-id id :kind kind :target-text target
             :resolution resolution :confidence confidence
             :current-target (get targets id) :file-path path
             :source/start-line sl :source/start-column sc
             :source/end-line el :source/end-column ec})
          (store/query graph
                       '[:find ?id ?kind ?target ?resolution ?confidence ?path ?sl ?sc ?el ?ec
                         :where [?edge :edge/id ?id]
                                [?edge :edge/kind ?kind]
                                [?edge :edge/target-text ?target]
                                [?edge :edge/resolution ?resolution]
                                [?edge :edge/confidence ?confidence]
                                [?edge :edge/from ?from]
                                [?from :symbol/file ?file]
                                [?file :file/path ?path]
                                [?edge :source/start-line ?sl]
                                [?edge :source/start-column ?sc]
                                [?edge :source/end-line ?el]
                                [?edge :source/end-column ?ec]]
                       []))))

(defn- semantic-index [project config changed]
  (when (and (contains? (set (get-in config [:semantic :providers])) :scip-typescript)
             (some #(contains? #{:language/javascript :language/typescript :language/tsx}
                               (:language %)) changed))
    (try
      {:index (scip/run! project config)}
      (catch Throwable error
        {:diagnostic {:level :warning :kind :semantic-provider-failed
                      :provider :scip-typescript :message (.getMessage error)}}))))

(defn analyze! [project config]
  (let [{:keys [files diagnostics]}
        (files/discover project config (jtreesitter/available-languages))
        scanned (into {} (map (juxt :relative-path identity) files))]
    (store/with-store [graph project config]
      (let [existing (existing-files graph)
            changed (->> files
                         (filter #(not= (ids/content-hash (:content %))
                                        (get-in existing [(:relative-path %) :hash])))
                         vec)
            deleted (->> (keys existing) (remove (set (keys scanned))) vec)
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
        (let [semantic (semantic-index project config changed)
              symbols (graph-symbols graph)
              edges (graph-edges graph)
              exact (if (:index semantic)
                      (resolve/scip-exact-targets symbols edges (:index semantic))
                      {})]
          (store/reconcile-edges!
           graph (resolve/resolution-decisions symbols edges exact))
          (let [semantic-plan
                (semantic-reconcile/reconcile! graph project config)]
            {:mode :incremental
           :files (count files)
           :changed (count changed)
           :deleted (count deleted)
           :entities (reduce + (map #(inc (count (:entities %))) extracted))
           :semantic semantic-plan
           :diagnostics (cond-> (vec (concat diagnostics
                                             (mapcat :diagnostics extracted)
                                             (:diagnostics semantic-plan)))
                          (:diagnostic semantic)
                          (conj (:diagnostic semantic)))}))))))
