(ns llm-context.context
  (:require [clojure.string :as str]
            [llm-context.graph.read :as graph-read]
            [llm-context.query :as query]
            [llm-context.store :as store]))

(defn estimate-tokens [value]
  (long (Math/ceil (/ (count (pr-str value)) 4.0))))

(defn- traversal-order [db seed-ids max-depth]
  (loop [depth 0
         frontier (vec (sort (distinct seed-ids)))
         seen #{}
         order []]
    (if (or (empty? frontier) (> depth max-depth))
      order
      (let [fresh (vec (remove seen frontier))
            seen' (into seen fresh)
            order' (into order (map #(hash-map :id % :distance depth) fresh))
            next-frontier
            (when (< depth max-depth)
              (->> (graph-read/neighbor-ids db fresh)
                   (remove seen')
                   sort
                   vec))]
        (recur (inc depth) next-frontier seen' order')))))

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

(defn- largest-fitting-prefix
  [focus max-tokens symbols relationships effects order]
  (loop [low 1
         high (count order)
         best nil]
    (if (> low high)
      best
      (let [middle (quot (+ low high) 2)
            packet (packet-for focus max-tokens symbols relationships
                               effects order middle)
            fits? (<= (get-in packet [:budget :estimated-tokens])
                      max-tokens)]
        (if fits?
          (recur (inc middle) high middle)
          (recur low (dec middle) best))))))

(defn build
  [graph {:keys [focus max-tokens depth]
          :or {max-tokens 8000 depth 4}}]
  (let [db (store/database graph)
        exact (graph-read/exact-symbols db focus)
        matches (or (seq exact) (seq (query/symbols graph focus)))
        seeds (mapv :id (or (seq exact) (seq matches)))]
    (when-not (seq seeds)
      (throw (ex-info (str "No symbol matches context focus: " focus)
                      {:exit-code 2 :focus focus})))
    (let [order (traversal-order db seeds depth)
          ids (mapv :id order)
          catalog (graph-read/symbols-by-ids db ids)
          relations (graph-read/edges-for-symbols db ids)
          effects (graph-read/effects-for-symbols db ids)
          best-count (largest-fitting-prefix focus max-tokens catalog
                                             relations effects order)]
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
