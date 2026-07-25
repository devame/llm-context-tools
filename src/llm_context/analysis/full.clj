(ns llm-context.analysis.full
  (:require [llm-context.analysis.effects :as effects]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.clojure :as clojure-analysis]
            [llm-context.analysis.files :as files]
            [llm-context.analysis.resolve :as resolve]
            [llm-context.analysis.structural :as structural]
            [llm-context.graph.read :as graph-read]
            [llm-context.indexer :as indexer]
            [llm-context.model.ids :as ids]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.parser.provider :as parser]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.store :as store]))

(def persistence-batch-size 100)
(def resolution-batch-size 1000)

(defn- enrich-effects [{:keys [file entities] :as output}]
  (let [edges (filter :edge/id entities)]
    (update output :entities into (effects/analyze (:file/language file) edges))))

(defn- edn-output
  [{:keys [relative-path language content size modified-at]}]
  {:file {:entity/type :entity.type/file
          :file/id (ids/file-id relative-path)
          :file/path relative-path
          :file/language language
          :file/content-hash (ids/content-hash content)
          :file/size size
          :file/modified-at modified-at}
   :entities []
   :diagnostics []})

(defn- emit! [progress stage data]
  (when progress
    (progress (assoc data :stage stage))))

(defn- persist! [graph config entities progress]
  (when (semantic-reconcile/enabled? config)
    (semantic-reconcile/mark-full! graph))
  (store/replace-all! graph entities
                      {:batch-size persistence-batch-size
                       :on-progress
                       (when progress
                         #(emit! progress :persist-progress %))}))

(defn- resolve-persisted! [graph progress]
  (let [db (store/database graph)
        edge-ids (graph-read/all-edge-ids db)
        _ (emit! progress :resolve-edges-selected {:edges (count edge-ids)})
        edges (graph-read/edge-resolution-inputs db edge-ids)
        _ (emit! progress :resolve-edges-loaded {:edges (count edges)})
        symbols (graph-read/resolution-candidate-symbols db edges)
        _ (emit! progress :resolve-candidates-selected
                 {:candidates (count symbols)})
        decisions (resolve/resolution-decisions symbols edges {})]
    (emit! progress :resolve-plan
           {:edges (count edges) :candidates (count symbols)
            :exact 0 :batch-size resolution-batch-size})
    (store/reconcile-edges!
     graph decisions
     {:batch-size resolution-batch-size
      :on-progress
      (when progress #(emit! progress :resolve-progress %))})
    {:edges (count edges) :candidates (count symbols)
     :exact 0}))

(defn analyze!
  "Perform a complete project scan and replace Datalevin facts in bounded
  transactions. A missing optional semantic provider degrades resolution, not
  availability."
  ([project config]
   (analyze! project config nil))
  ([project config progress]
   (store/with-store [graph project config]
     (analyze! graph project config progress)))
  ([graph project config progress]
   (let [started (System/nanoTime)]
     (emit! progress :discover-start {})
     (with-open [parser-provider (jtreesitter/open project)]
       (let [{:keys [files diagnostics]}
             (files/discover project config
                             (parser/supported-languages parser-provider))
             total (count files)
             _ (emit! progress :discover-complete
                      {:files total :diagnostics (count diagnostics)})
             structural-indexer (structural/create parser-provider)
             clojure-files
             (filterv #(contains? clj-kondo/clojure-languages (:language %))
                      files)
             janet-files (filterv #(= :language/janet (:language %)) files)
             edn-files (filterv #(= :language/edn-data (:language %)) files)
             clojure-snapshot (clj-kondo/analyze! project clojure-files)
             by-path
             (into {}
                   (map (juxt (comp :file/path :file) identity)
                        (concat
                         (clojure-analysis/materialize
                          clojure-files clojure-snapshot)
                         (map #(-> (indexer/index-file structural-indexer %)
                                   enrich-effects)
                              janet-files)
                         (map edn-output edn-files))))
             extracted
             (mapv (fn [index file]
                     (when (or (zero? index) (zero? (mod index 25)))
                       (emit! progress :parse-progress
                              {:completed index :total total
                               :file (:relative-path file)}))
                     (get by-path (:relative-path file)))
                   (range) files)
             _ (emit! progress :parse-complete
                      {:completed total :total total})
             all-entities (vec (mapcat (fn [{:keys [file entities]}]
                                         (cons file entities))
                                       extracted))
             _ (emit! progress :persist-start
                      {:entities (count all-entities)
                       :batch-size persistence-batch-size})]
         (persist! graph config all-entities progress)
         (emit! progress :resolve-start {})
         (let [resolution (resolve-persisted! graph progress)
               _ (emit! progress :semantic-reconcile-start {})
               semantic-plan
               (semantic-reconcile/reconcile! graph project config)
               _ (emit! progress :semantic-reconcile-complete
                        {:upserts (:queued-upserts semantic-plan)
                         :deletes (:queued-deletes semantic-plan)
                         :deferred (:deferred semantic-plan)})]
           (emit! progress :complete
                  {:elapsed-seconds
                   (long (/ (- (System/nanoTime) started) 1000000000))})
           {:mode :full
            :files total
            :entities (count all-entities)
            :resolution resolution
            :semantic semantic-plan
            :diagnostics (vec (concat diagnostics
                                      (:diagnostics clojure-snapshot)
                                      (mapcat :diagnostics extracted)
                                      (:diagnostics semantic-plan)))}))))))
