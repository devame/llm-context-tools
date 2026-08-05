(ns corpus.context.packet)

(defn choose-context-seed
  "Choose one canonical symbol from ranked natural-language retrieval results."
  [ranked-results]
  (first ranked-results))

(defn expand-exact-neighbors
  "Traverse verified caller and callee edges around a selected symbol."
  [graph symbol-id depth]
  (take depth (get graph symbol-id)))

(defn build-context-packet
  "Construct a token-bounded context packet from a natural-language seed and
  its exact semantic graph neighborhood."
  [graph ranked-results token-budget]
  (let [seed (choose-context-seed ranked-results)
        neighbors (expand-exact-neighbors graph (:id seed) token-budget)]
    {:seed seed :symbols (vec (cons seed neighbors))}))

(defn build-summary-packet
  "Construct a broad repository summary without intent-based seed selection."
  [symbols]
  {:symbols (take 10 symbols)})
