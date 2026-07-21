(ns llm-context.context
  (:require [clojure.string :as str]
            [llm-context.query :as query]
            [llm-context.store :as store]))

(defn estimate-tokens [value]
  (long (Math/ceil (/ (count (pr-str value)) 4.0))))

(defn- symbol-catalog [graph]
  (let [signatures (into {} (store/query graph
                                         '[:find ?id ?signature
                                           :where [?symbol :symbol/id ?id]
                                                  [?symbol :symbol/signature ?signature]] []))
        docs (into {} (store/query graph
                                  '[:find ?id ?doc
                                    :where [?symbol :symbol/id ?id]
                                           [?symbol :symbol/doc ?doc]] []))]
    (into {}
          (map (fn [[id name qualified kind path line]]
                 [id (cond-> {:id id :name name :qualified-name qualified
                              :kind kind :file path :line line}
                       (get signatures id) (assoc :signature (get signatures id))
                       (get docs id) (assoc :doc (get docs id)))]))
          (store/query graph
                       '[:find ?id ?name ?qualified ?kind ?path ?line
                         :where [?symbol :symbol/id ?id]
                                [?symbol :symbol/name ?name]
                                [?symbol :symbol/qualified-name ?qualified]
                                [?symbol :symbol/kind ?kind]
                                [?symbol :symbol/file ?file]
                                [?file :file/path ?path]
                                [?symbol :source/start-line ?line]] []))))

(defn- relationships [graph]
  (let [targets (into {} (store/query graph
                                      '[:find ?edge-id ?target-id
                                        :where [?edge :edge/id ?edge-id]
                                               [?edge :edge/to ?target]
                                               [?target :symbol/id ?target-id]] []))]
    (mapv (fn [[id kind from target-text resolution line]]
            (cond-> {:id id :kind kind :from from :target-text target-text
                     :resolution resolution :line line}
              (get targets id) (assoc :to (get targets id))))
          (store/query graph
                       '[:find ?id ?kind ?from-id ?target-text ?resolution ?line
                         :where [?edge :edge/id ?id]
                                [?edge :edge/kind ?kind]
                                [?edge :edge/from ?from]
                                [?from :symbol/id ?from-id]
                                [?edge :edge/target-text ?target-text]
                                [?edge :edge/resolution ?resolution]
                                [?edge :source/start-line ?line]] []))))

(defn- traversal-order [seed-ids relationships max-depth]
  (let [adjacency (reduce (fn [result {:keys [from to]}]
                            (if to
                              (-> result
                                  (update from (fnil conj #{}) to)
                                  (update to (fnil conj #{}) from))
                              result))
                          {} relationships)]
    (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                       (map #(vector % 0) seed-ids))
           seen #{}
           order []]
      (if-let [[id depth] (peek queue)]
        (let [queue (pop queue)]
          (if (contains? seen id)
            (recur queue seen order)
            (let [neighbors (if (< depth max-depth)
                              (sort (get adjacency id)) [])]
              (recur (into queue (map #(vector % (inc depth)) neighbors))
                     (conj seen id)
                     (conj order {:id id :distance depth})))))
        order))))

(defn- packet-for [focus max-tokens symbols relationships effects order n]
  (let [selected (vec (take n order))
        selected-ids (set (map :id selected))
        packet {:packet/version 1
                :focus focus
                :budget {:max-tokens max-tokens}
                :symbols (mapv (fn [{:keys [id distance]}]
                                 (assoc (get symbols id) :distance distance))
                               selected)
                :relationships (->> relationships
                                    (filter #(contains? selected-ids (:from %)))
                                    (filter #(or (nil? (:to %))
                                                 (contains? selected-ids (:to %))))
                                    vec)
                :effects (->> effects
                              (filter #(contains? selected-ids (:symbol-id %)))
                              vec)
                :truncated? (< n (count order))}]
    (assoc-in packet [:budget :estimated-tokens] (estimate-tokens packet))))

(defn build
  [graph {:keys [focus max-tokens depth]
          :or {max-tokens 8000 depth 4}}]
  (let [catalog (symbol-catalog graph)
        matches (query/symbols graph focus)
        exact (filter #(or (= focus (:name %)) (= focus (:qualified-name %))
                           (= focus (:id %))) matches)
        seeds (mapv :id (or (seq exact) (seq matches)))]
    (when-not (seq seeds)
      (throw (ex-info (str "No symbol matches context focus: " focus)
                      {:exit-code 2 :focus focus})))
    (let [relations (relationships graph)
          order (traversal-order seeds relations depth)
          effects (mapv #(select-keys % [:kind :symbol-id :detail :confidence :file :line])
                        (query/effects graph))
          fits? #(<= (get-in (packet-for focus max-tokens catalog relations effects order %)
                             [:budget :estimated-tokens])
                     max-tokens)
          best-count (last (filter fits? (range 1 (inc (count order)))))]
      (if best-count
        (packet-for focus max-tokens catalog relations effects order best-count)
        (let [seed (select-keys (get catalog (:id (first order)))
                                [:id :name :qualified-name :kind :file :line])
              packet {:packet/version 1 :focus focus
                      :budget {:max-tokens max-tokens}
                      :symbols [(assoc seed :distance 0)]
                      :relationships [] :effects [] :truncated? true}
              compact (assoc-in packet [:budget :estimated-tokens]
                                (estimate-tokens packet))]
          (if (<= (get-in compact [:budget :estimated-tokens]) max-tokens)
            compact
            (throw (ex-info
                    (str "Context token budget is too small; minimum is approximately "
                         (get-in compact [:budget :estimated-tokens]))
                    {:exit-code 2
                     :minimum-tokens (get-in compact [:budget :estimated-tokens])}))))))))

(defn markdown [packet]
  (str "# Code context: " (:focus packet) "\n\n"
       "Estimated tokens: " (get-in packet [:budget :estimated-tokens])
       " / " (get-in packet [:budget :max-tokens])
       (when (:truncated? packet) " (truncated)") "\n\n"
       "## Symbols\n\n"
       (str/join
        "\n"
        (for [{:keys [id qualified-name kind file line signature doc]} (:symbols packet)]
          (str "- `" qualified-name "` — " (name kind) " at `" file ":" line "`"
               " (`" id "`)"
               (when (seq signature) (str "\n  - Signature: `" signature "`"))
               (when (seq doc) (str "\n  - " (str/replace doc #"\s+" " "))))))
       "\n\n## Relationships\n\n"
       (if (seq (:relationships packet))
         (str/join "\n"
                   (for [{:keys [kind from to target-text resolution line]}
                         (:relationships packet)]
                     (str "- " (name kind) ": `" from "` → `"
                          (or to target-text) "` (" (name resolution)
                          ", line " line ")")))
         "None")
       "\n\n## Effects\n\n"
       (if (seq (:effects packet))
         (str/join "\n"
                   (for [{:keys [kind symbol-id detail]} (:effects packet)]
                     (str "- " (name kind) " in `" symbol-id "`: " detail)))
         "None")
       "\n"))
