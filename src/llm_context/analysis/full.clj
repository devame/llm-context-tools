(ns llm-context.analysis.full
  (:require [llm-context.analysis.effects :as effects]
            [llm-context.analysis.files :as files]
            [llm-context.analysis.resolve :as resolve]
            [llm-context.analysis.structural :as structural]
            [llm-context.indexer :as indexer]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.parser.provider :as parser]
            [llm-context.semantic.scip :as scip]
            [llm-context.store :as store]))

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

(defn analyze!
  "Perform a complete project scan and replace Datalevin facts file by file.
  A missing optional semantic provider degrades resolution, not availability."
  [project config]
  (with-open [parser-provider (jtreesitter/open project)]
    (let [{:keys [files diagnostics]}
          (files/discover project config (parser/supported-languages parser-provider))
          structural-indexer (structural/create parser-provider)
          extracted (mapv #(-> (indexer/index-file structural-indexer %)
                               enrich-effects)
                          files)
          semantic (maybe-scip project config (map :language files))
          resolved (resolve/resolve-outputs extracted (:index semantic))
          all-entities (vec (mapcat (fn [{:keys [file entities]}]
                                      (cons file entities))
                                    resolved))]
      (store/with-store [graph project config]
        (store/replace-all! graph all-entities))
      {:mode :full
       :files (count files)
       :entities (reduce + (map #(inc (count (:entities %))) resolved))
       :diagnostics (cond-> (vec (concat diagnostics
                                         (mapcat :diagnostics resolved)))
                      (:diagnostic semantic) (conj (:diagnostic semantic)))})))
