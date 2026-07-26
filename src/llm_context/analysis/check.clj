(ns llm-context.analysis.check
  "Read-only project analysis and graph-contract validation."
  (:require [llm-context.analysis.files :as files]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.analysis.project-analyzer :as project-analyzer]))

(defn check!
  "Analyze the supported source inventory without opening or mutating
  Datalevin. Project analyzer canonicalization performs the graph-format audit
  before this function returns."
  [project config]
  (let [{:keys [files diagnostics]}
        (files/discover project config incremental/supported-languages)
        snapshot (project-analyzer/analyze project files)
        outputs (:outputs snapshot)
        preserved (filterv :preserve? outputs)
        _ (when (seq preserved)
            (throw
             (ex-info
              "Analysis check produced an incomplete source snapshot"
              {:exit-code 1
               :type :analysis/incomplete-snapshot
               :files (mapv (comp :file/path :file) preserved)
               :diagnostics
               (vec (concat diagnostics
                            (:diagnostics snapshot)
                            (mapcat :diagnostics preserved)))})))
        entities (vec (mapcat (fn [{:keys [file entities]}]
                                (cons file entities))
                              outputs))
        by-type (frequencies (map :entity/type entities))]
    {:mode :check
     :files (count files)
     :entities (count entities)
     :symbols (get by-type :entity.type/symbol 0)
     :exact-edges (get by-type :entity.type/edge 0)
     :references (get by-type :entity.type/reference 0)
     :topics (get by-type :entity.type/topic 0)
     :effects (get by-type :entity.type/effect 0)
     :analyzers (:analyzers snapshot)
     :diagnostics
     (vec (concat diagnostics
                  (:diagnostics snapshot)
                  (mapcat :diagnostics outputs)))}))
