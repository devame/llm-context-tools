(ns llm-context.retrieval-corpus
  "Validate the checked-in retrieval corpus against real analyzer output."
  (:require [clojure.set :as set]
            [llm-context.analysis.files :as files]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.analysis.project-analyzer :as project-analyzer]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.evaluation :as evaluation]))

(def default-project "bench/retrieval-corpus/project")
(def default-queries "bench/retrieval-corpus/queries.edn")

(defn validate!
  [project-path query-path]
  (let [project (project/context project-path)
        config (config/load-config project)
        discovery (files/discover project config incremental/supported-languages)
        snapshot (project-analyzer/analyze project (:files discovery))
        symbols (->> (:outputs snapshot)
                     (mapcat :entities)
                     (filter #(= :entity.type/symbol (:entity/type %))))
        qualified-names (set (map :symbol/qualified-name symbols))
        queries (evaluation/read-corpus query-path)
        judged-identities
        (set (concat (mapcat (comp keys :relevance) queries)
                     (mapcat :hard-negatives queries)))
        missing (sort (set/difference judged-identities qualified-names))
        diagnostics (vec (concat (:diagnostics discovery)
                                 (:diagnostics snapshot)
                                 (mapcat :diagnostics (:outputs snapshot))))]
    (when (or (seq missing) (seq diagnostics))
      (throw
       (ex-info "Retrieval corpus does not match analyzer output"
                {:exit-code 1
                 :missing-judged-identities missing
                 :diagnostics diagnostics})))
    {:corpus/version evaluation/corpus-version
     :queries (count queries)
     :languages (frequencies (map :language queries))
     :query-types (frequencies (map :query-type queries))
     :files (count (:files discovery))
     :symbols (count symbols)
     :judged-identities (count judged-identities)}))

(defn -main [& [project-path query-path]]
  (prn (validate! (or project-path default-project)
                  (or query-path default-queries))))
