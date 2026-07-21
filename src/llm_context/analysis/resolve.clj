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
