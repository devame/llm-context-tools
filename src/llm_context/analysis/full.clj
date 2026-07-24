(ns llm-context.analysis.full
  (:require [llm-context.analysis.effects :as effects]
            [llm-context.analysis.files :as files]
            [llm-context.analysis.resolve :as resolve]
            [llm-context.analysis.structural :as structural]
            [llm-context.indexer :as indexer]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.parser.provider :as parser]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.scip :as scip]
            [llm-context.store :as store]))

(def persistence-batch-size 100)

(defn- enrich-effects [{:keys [file entities] :as output}]
  (let [edges (filter :edge/id entities)]
    (update output :entities into (effects/analyze (:file/language file) edges))))

(defn- maybe-scip [project config languages]
  (when (and (contains? (set (get-in config [:semantic :providers])) :scip-typescript)
             (some #{:language/javascript :language/typescript :language/tsx} languages))
    (try
      {:index (scip/run! project config)}
      (catch Throwable error
        {:diagnostic {:level :warning :kind :semantic-provider-failed
                      :provider :scip-typescript :message (.getMessage error)}}))))

(defn- emit! [progress stage data]
  (when progress
    (progress (assoc data :stage stage))))

(defn- persist! [graph project config entities progress]
  (when (semantic-reconcile/enabled? config)
    (semantic-reconcile/mark-full! graph))
  (store/replace-all! graph entities
                      {:batch-size persistence-batch-size
                       :on-progress
                       (when progress
                         #(emit! progress :persist-progress %))})
  (semantic-reconcile/reconcile! graph project config))

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
             extracted
             (mapv (fn [index file]
                     (when (or (zero? index) (zero? (mod index 25)))
                       (emit! progress :parse-progress
                              {:completed index :total total
                               :file (:relative-path file)}))
                     (-> (indexer/index-file structural-indexer file)
                         enrich-effects))
                   (range) files)
             _ (emit! progress :parse-complete
                      {:completed total :total total})
             _ (emit! progress :semantic-start {})
             semantic (maybe-scip project config (map :language files))
             _ (emit! progress :semantic-complete
                      {:provider-ran? (boolean semantic)})
             _ (emit! progress :resolve-start {})
             resolved (resolve/resolve-outputs extracted (:index semantic))
             all-entities (vec (mapcat (fn [{:keys [file entities]}]
                                         (cons file entities))
                                       resolved))
             _ (emit! progress :persist-start
                      {:entities (count all-entities)
                       :batch-size persistence-batch-size})]
         (let [semantic-plan (persist! graph project config
                                       all-entities progress)]
           (emit! progress :complete
                  {:elapsed-seconds
                   (long (/ (- (System/nanoTime) started) 1000000000))})
           {:mode :full
            :files total
            :entities (count all-entities)
            :semantic semantic-plan
            :diagnostics (cond-> (vec (concat diagnostics
                                              (mapcat :diagnostics resolved)
                                              (:diagnostics semantic-plan)))
                           (:diagnostic semantic)
                           (conj (:diagnostic semantic)))}))))))
