(ns llm-context.analysis.resolve
  (:require [clojure.string :as str]))

(defn- point-in? [entity line column]
  (let [start [(:source/start-line entity) (:source/start-column entity)]
        end [(:source/end-line entity) (:source/end-column entity)]
        point [line column]]
    (and (every? some? (concat start end))
         (not (neg? (compare point start)))
         (not (pos? (compare point end))))))

(defn- span [entity]
  (- (* 1000000 (:source/end-line entity)) (:source/start-line entity)))

(defn- scip-definition-map [outputs scip-index]
  (let [symbols-by-file
        (into {} (map (fn [{:keys [file entities]}]
                        [(:file/path file) (filter :symbol/id entities)])) outputs)]
    (into {}
          (mapcat
           (fn [{:keys [relative-path occurrences]}]
             (let [symbols (get symbols-by-file relative-path)]
               (keep (fn [{:keys [range symbol roles]}]
                       (when (and symbol range (pos? (bit-and 1 roles)))
                         (let [[line column] range
                               match (->> symbols
                                          (filter #(point-in? % (inc line) (inc column)))
                                          (sort-by span)
                                          first)]
                           (when match [symbol (:symbol/id match)]))))
                     occurrences)))
           (:documents scip-index)))))

(defn- scip-reference-target [edge document definition-map]
  (some (fn [{:keys [range symbol roles]}]
          (when (and range symbol (zero? (bit-and 1 roles))
                     (contains? definition-map symbol))
            (let [[line column] range]
              (when (point-in? edge (inc line) (inc column))
                (get definition-map symbol)))))
        (:occurrences document)))

(defn- target-name [target]
  (-> target
      str/trim
      (str/replace #"^[`'\"]|[`'\"]$" "")
      (str/replace #"\(.*$" "")
      (str/split #"[./]")
      last))

(defn resolve-outputs
  "Resolve edges against the complete analysis batch. SCIP evidence wins;
  otherwise unique structural names are heuristic and collisions are explicit."
  [outputs scip-index]
  (let [symbols (vec (mapcat #(filter :symbol/id (:entities %)) outputs))
        by-qualified (group-by :symbol/qualified-name symbols)
        by-name (group-by :symbol/name symbols)
        definition-map (if scip-index (scip-definition-map outputs scip-index) {})
        documents (into {} (map (juxt :relative-path identity)
                                (:documents scip-index)))]
    (mapv
     (fn [{:keys [file entities] :as output}]
       (let [document (get documents (:file/path file))]
         (assoc output :entities
                (mapv
                 (fn [entity]
                   (if (or (not (:edge/id entity)) (:edge/to entity))
                     entity
                     (let [scip-target (when document
                                         (scip-reference-target entity document definition-map))
                           qualified (get by-qualified (:edge/target-text entity))
                           named (get by-name (target-name (:edge/target-text entity)))
                           candidates (or (seq qualified) (seq named))]
                       (cond
                         scip-target
                         (assoc entity :edge/to scip-target
                                :edge/resolution :resolution/exact
                                :edge/confidence 1.0)

                         (= 1 (count candidates))
                         (assoc entity :edge/to (:symbol/id (first candidates))
                                :edge/resolution :resolution/heuristic
                                :edge/confidence 0.75)

                         (> (count candidates) 1)
                         (assoc entity :edge/resolution :resolution/ambiguous
                                :edge/confidence 0.25)

                         :else entity))))
                 entities))))
     outputs)))

(defn scip-exact-targets
  "Return edge-id -> symbol-id evidence from a SCIP index and database-shaped
  symbol/edge maps."
  [symbols edges scip-index]
  (let [symbols-by-file (group-by :file-path symbols)
        definitions
        (into {}
              (mapcat
               (fn [{:keys [relative-path occurrences]}]
                 (keep (fn [{:keys [range symbol roles]}]
                         (when (and range symbol (pos? (bit-and 1 roles)))
                           (let [[line column] range
                                 target (->> (get symbols-by-file relative-path)
                                             (filter #(point-in? % (inc line) (inc column)))
                                             (sort-by span) first)]
                             (when target [symbol (:symbol-id target)]))))
                       occurrences))
               (:documents scip-index)))
        edges-by-file (group-by :file-path edges)]
    (into {}
          (mapcat
           (fn [{:keys [relative-path occurrences]}]
             (for [edge (get edges-by-file relative-path)
                   occurrence occurrences
                   :let [range (:range occurrence)
                         symbol (:symbol occurrence)]
                   :when (and range symbol
                              (zero? (bit-and 1 (:roles occurrence)))
                              (contains? definitions symbol)
                              (let [[line column] range]
                                (point-in? edge (inc line) (inc column))))]
               [(:edge-id edge) (get definitions symbol)]))
           (:documents scip-index)))))

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
                            (seq (get by-name (target-name (:target-text edge)))))]
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
