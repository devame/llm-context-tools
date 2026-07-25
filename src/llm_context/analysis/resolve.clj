(ns llm-context.analysis.resolve
  (:require [llm-context.model.schema :as schema]))

(defn- point-in? [entity line column]
  (let [start [(:source/start-line entity) (:source/start-column entity)]
        end [(:source/end-line entity) (:source/end-column entity)]
        point [line column]]
    (and (every? some? (concat start end))
         (not (neg? (compare point start)))
         (not (pos? (compare point end))))))

(defn scip-exact-targets-focused
  "Resolve SCIP evidence only for the affected database edge set. External
  SCIP occurrences are inspected to identify evidence; graph symbol selection
  remains an exact Datalevin point lookup supplied by symbol-at-point."
  [edges scip-index symbol-at-point]
  (let [documents (into {} (map (juxt :relative-path identity)
                                (:documents scip-index)))
        references
        (into {}
              (keep
               (fn [edge]
                 (when-let [document (get documents (:file-path edge))]
                   (when-let [occurrence
                              (some
                               (fn [{:keys [range symbol roles]}]
                                 (when (and range symbol
                                            (zero? (bit-and 1 roles)))
                                   (let [[line column] range]
                                     (when (point-in? edge
                                                      (inc line) (inc column))
                                       {:symbol symbol}))))
                               (:occurrences document))]
                     [(:edge-id edge) (:symbol occurrence)])))
               edges))
        needed (set (vals references))
        definitions
        (into {}
              (mapcat
               (fn [{:keys [relative-path occurrences]}]
                 (keep
                  (fn [{:keys [range symbol roles]}]
                    (when (and range
                               (contains? needed symbol)
                               (pos? (bit-and 1 roles)))
                      (let [[line column] range
                            target (symbol-at-point relative-path
                                                    (inc line) (inc column))]
                        (when target [symbol (:symbol-id target)]))))
                  occurrences))
               (:documents scip-index)))]
    (into {}
          (keep (fn [[edge-id scip-symbol]]
                  (when-let [target (get definitions scip-symbol)]
                    [edge-id target])))
          references)))

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
