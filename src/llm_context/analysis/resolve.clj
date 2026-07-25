(ns llm-context.analysis.resolve
  (:require [llm-context.model.schema :as schema]))

(defn resolution-decisions
  "Resolve database-shaped edge maps after incremental symbol changes."
  [symbols edges exact-targets]
  (let [symbol-ids (set (map :symbol-id symbols))
        by-qualified (group-by :qualified-name symbols)
        by-name (group-by :name symbols)]
    (mapv
     (fn [edge]
       (let [exact (get exact-targets (:edge-id edge))
             candidates (or (seq (get by-qualified (:target-text edge)))
                            (seq (get by-name
                                      (schema/edge-target-name
                                       (:target-text edge)))))]
         (cond
           (= :edge.kind/contains (:kind edge))
           {:edge-id (:edge-id edge) :target-id (:current-target edge)
            :resolution :resolution/exact :confidence 1.0}

           exact
           {:edge-id (:edge-id edge) :target-id exact
            :resolution :resolution/exact :confidence 1.0}

           (and (= :resolution/exact (:resolution edge))
                (contains? symbol-ids (:current-target edge)))
           {:edge-id (:edge-id edge) :target-id (:current-target edge)
            :resolution :resolution/exact :confidence 1.0}

           (= 1 (count candidates))
           {:edge-id (:edge-id edge) :target-id (:symbol-id (first candidates))
            :resolution :resolution/heuristic :confidence 0.75}

           (> (count candidates) 1)
           {:edge-id (:edge-id edge) :target-id nil
            :resolution :resolution/ambiguous :confidence 0.25}

           :else
           {:edge-id (:edge-id edge) :target-id nil
            :resolution :resolution/unresolved :confidence 0.0})))
     edges)))
