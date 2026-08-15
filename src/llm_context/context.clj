(ns llm-context.context
  (:require [clojure.string :as str]
            [llm-context.graph.read :as graph-read]
            [llm-context.intent :as intent]
            [llm-context.model.schema :as schema]
            [llm-context.query :as query]
            [llm-context.store :as store])
  (:import [java.util Comparator PriorityQueue]))

(def edge-costs
  {:edge.kind/calls 1.0
   :edge.kind/macro-invokes 1.0
   :edge.kind/references 1.0
   ;; Two half-edges form one symbol-topic-symbol bridge.
   :edge.kind/event-dispatches 0.5
   :edge.kind/subscribes 0.5
   :edge.kind/topic-registers 0.5
   :edge.kind/state-reads 0.5
   :edge.kind/state-writes 0.5
   :edge.kind/protocol-implements 1.25
   :edge.kind/implements 1.25
   :edge.kind/imports 2.0
   :edge.kind/contains 0.25})

(def default-edge-kinds (set (keys edge-costs)))

(defn estimate-tokens [value]
  (long (Math/ceil (/ (count (pr-str value)) 4.0))))

(defn- namespace-name [qualified]
  (some-> qualified symbol namespace))

(defn- proximity [focus neighbor]
  (cond
    (and (:file-id focus) (= (:file-id focus) (:file-id neighbor))) 0
    (= (namespace-name (:qualified-name focus))
       (namespace-name (:qualified-name neighbor))) 1
    :else 2))

(defn- node-neighbor [step current-id]
  (if (= current-id (:from step)) (:to step) (:from step)))

(defn- expandable-step?
  [catalog current-id step]
  ;; Containment may find a namespace owner, but a namespace is never expanded
  ;; through every member it contains.
  (not (and (= :edge.kind/contains (:kind step))
            (= current-id (:from step))
            (= :symbol.kind/namespace
               (:kind (get catalog current-id))))))

(defn- queue-comparator []
  (reify Comparator
    (compare [_ left right]
      (compare [(:cost left) (:proximity left)
                (:qualified left) (:id left)]
               [(:cost right) (:proximity right)
                (:qualified right) (:id right)]))))

(defn- node-records [db ids]
  (merge (graph-read/symbols-by-ids db ids)
         (graph-read/topics-by-ids db ids)))

(defn- traverse
  [db seeds {:keys [depth max-nodes directions edge-kinds]}]
  (let [queue (PriorityQueue. 32 (queue-comparator))
        seed-catalog (graph-read/symbols-by-ids db seeds)
        focus (get seed-catalog (first seeds))
        catalog (atom seed-catalog)
        best (atom {})
        discovered (atom (set seeds))
        depth-truncated? (atom false)]
    (doseq [seed seeds
            :let [symbol (get seed-catalog seed)]]
      (.add queue {:id seed :cost 0.0 :depth 0 :proximity 0
                   :qualified (:qualified-name symbol)
                   :path []}))
    (loop [selected []]
      (if (or (.isEmpty queue) (>= (count selected) max-nodes))
        {:order selected :catalog @catalog
         :graph-truncated? (or @depth-truncated? (not (.isEmpty queue)))}
        (let [entry (.poll queue)
              id (:id entry)]
          (if (contains? @best id)
            (recur selected)
            (do
              (swap! best assoc id entry)
              (let [selected (conj selected entry)]
                (if (>= (:depth entry) depth)
                  (do
                    (when (some
                           (fn [step]
                             (not (contains? @discovered
                                             (node-neighbor step id))))
                           (graph-read/adjacent-exact
                            db [id] {:directions directions
                                     :edge-kinds edge-kinds :limit 20}))
                      (reset! depth-truncated? true))
                    (recur selected))
                  (let [steps
                        (graph-read/adjacent-exact
                         db [id] {:directions directions
                                  :edge-kinds edge-kinds
                                  :limit (inc (- max-nodes
                                                 (count selected)))})
                        steps (filter #(expandable-step? @catalog id %) steps)
                        neighbor-ids
                        (->> steps
                             (map #(node-neighbor % id))
                             (remove #(contains? @best %))
                             distinct
                             vec)
                        neighbors (node-records db neighbor-ids)]
                    (swap! catalog merge neighbors)
                    (swap! discovered into neighbor-ids)
                    (doseq [step steps
                            :let [neighbor-id (node-neighbor step id)
                                  neighbor (get neighbors neighbor-id)]
                            :when (and neighbor
                                       (not (contains? @best neighbor-id)))]
                        (let [path-step
                            (assoc (select-keys
                                    step [:edge-id :kind :from :to :direction
                                          :evidence :line])
                                   :id (:edge-id step)
                                   :cost (get edge-costs (:kind step) 2.0))]
                        (.add queue
                              {:id neighbor-id
                               :cost (+ (:cost entry) (:cost path-step))
                               :depth (inc (:depth entry))
                               :proximity
                               (if (= :topic (:to-type step))
                                 0 (proximity focus neighbor))
                               :qualified
                               (or (:qualified-name neighbor) (:key neighbor)
                                   neighbor-id)
                               :path (conj (:path entry) path-step)})))
                    (recur selected)))))))))))

(defn- packet-for
  [focus focus-resolution max-tokens traversal n db]
  (let [selected (vec (take n (:order traversal)))
        selected-ids (set (map :id selected))
        selected-symbols (filter #(str/starts-with? (:id %) "symbol:")
                                 selected)
        selected-topics (filter #(str/starts-with? (:id %) "topic:")
                                selected)
        catalog (:catalog traversal)
        symbol-ids (mapv :id selected-symbols)
        relationships
        (->> selected
             (mapcat :path)
             (filter #(and (selected-ids (:from %))
                           (selected-ids (:to %))))
             (reduce (fn [result relationship]
                       (assoc result (:edge-id relationship) relationship))
                     (sorted-map))
             vals vec)
        packet
        {:packet/version 3
         :focus focus
         :focus-resolution focus-resolution
         :budget
         {:max-tokens max-tokens
          :allocation
          {:symbols (long (* max-tokens 0.60))
           :relationships-and-topics (long (* max-tokens 0.25))
           :effects (long (* max-tokens 0.10))
           :diagnostics (- max-tokens
                           (long (* max-tokens 0.60))
                           (long (* max-tokens 0.25))
                           (long (* max-tokens 0.10)))}}
         :symbols
         (mapv (fn [entry]
                 (assoc (get catalog (:id entry))
                        :path-cost (:cost entry)
                        :selected-path (:path entry)))
               selected-symbols)
         :topics
         (mapv (fn [entry]
                 (assoc (get catalog (:id entry))
                        :path-cost (:cost entry)
                        :selected-path (:path entry)))
               selected-topics)
         :relationships relationships
         :effects (graph-read/effects-for-symbols db symbol-ids)
         :diagnostics {:references
                       (graph-read/reference-summary db symbol-ids)}
         :truncation
         {:graph? (:graph-truncated? traversal)
          :token-budget? (< n (count (:order traversal)))}}]
    (-> packet
        (assoc :truncated? (boolean
                            (some true? (vals (:truncation packet)))))
        (assoc-in [:budget :estimated-tokens] (estimate-tokens packet)))))

(defn- largest-fitting-prefix
  [focus focus-resolution max-tokens traversal db]
  (loop [low 1 high (count (:order traversal)) best nil]
    (if (> low high)
      best
      (let [middle (quot (+ low high) 2)
            packet (packet-for focus focus-resolution max-tokens
                               traversal middle db)]
        (if (<= (get-in packet [:budget :estimated-tokens]) max-tokens)
          (recur (inc middle) high middle)
          (recur low (dec middle) best))))))

(defn- focus-candidate [rank matched-by symbol]
  {:id (:id symbol)
   :name (:name symbol)
   :qualified-name (:qualified-name symbol)
   :rank rank
   :matched-by matched-by})

(defn resolve-symbol-focus
  "Resolve the historical symbol-name-or-ID context focus without semantic
  retrieval. All returned selected IDs are canonical graph symbol IDs."
  [graph focus limit]
  (let [db (store/database graph)
        exact (graph-read/exact-symbols db focus limit)
        matches (or (seq exact)
                    (seq (query/symbols graph focus limit)))
        selected (mapv #(focus-candidate
                         (inc %1)
                         (if (seq exact) #{:exact} #{:fts})
                         %2)
                       (range)
                       (or (seq exact) (seq matches)))]
    (when-not (seq selected)
      (throw (ex-info (str "No symbol matches context focus: " focus)
                      {:exit-code 2 :focus focus
                       :suggestions (query/symbol-suggestions graph focus)})))
    {:mode :symbol
     :strategy (if (seq exact) :exact :lexical)
     :selected selected
     :alternatives []}))

(defn resolve-intent-focus
  "Select one or more bounded, diverse traversal roots according to the
  inspectable query plan. Unselected results remain provenance only."
  [focus {:keys [results retrieval]}]
  (when-not (seq results)
    (throw
     (ex-info
      (str "No symbol matches context intent: " focus)
      {:exit-code 2 :focus focus :retrieval retrieval})))
  (let [candidate
        (fn [rank symbol]
          {:id (:id symbol)
           :name (:name symbol)
           :qualified-name (:qualified-name symbol)
           :file (:file symbol)
           :rank rank
           :matched-by (:matched-by symbol)
           :score (:score symbol)
           :source-role (:source-role symbol)
           :fused-rank (:fused-rank symbol)
           :final-rank (:final-rank symbol)
           :ranking-reason (:ranking-reason symbol)
           :pre-rerank-rank (:pre-rerank-rank symbol)
           :post-rerank-rank (:post-rerank-rank symbol)
           :intent-score (:intent-score symbol)
           :intent-qualified? (:intent-qualified? symbol)
           :intent-reasons (:intent-reasons symbol)})
        candidates (mapv candidate (range 1 (inc (count results))) results)
        plan (or (:query-plan retrieval)
                 {:shape :lookup :seed-mode :single :max-seeds 1})
        selected-results (intent/select-seeds results plan)
        selected-ids (set (map :id selected-results))
        selected (->> candidates
                      (filter #(contains? selected-ids (:id %))) vec)
        qualified (filter :intent-qualified? candidates)
        inventory-limit 24
        inventory (if (= :set (:shape plan))
                    (mapv #(select-keys % [:qualified-name :file])
                          (take inventory-limit qualified))
                    [])
        alternatives (->> candidates
                          (remove #(contains? selected-ids (:id %)))
                          (take 4) vec)]
    {:mode :intent
     :strategy (if (some #(contains? (:matched-by %) :lateon) selected)
                 :hybrid
                 :lexical-fallback)
     :selected selected
     :inventory inventory
     :inventory-truncated? (> (count qualified) inventory-limit)
     :alternatives alternatives
     :retrieval retrieval}))

(defn build-from-seeds
  "Build a bounded context packet from an explicit focus-resolution record.
  Only canonical IDs in :selected seed traversal; alternatives are explanatory
  metadata and never become zero-cost traversal roots."
  [graph {:keys [focus max-tokens depth directions edge-kinds]
          :or {max-tokens 8000 depth 4 directions #{:outgoing :incoming}
               edge-kinds default-edge-kinds}}
   focus-resolution]
  (let [db (store/database graph)
        max-nodes (max 1 (quot max-tokens 8))
        seeds (mapv :id (:selected focus-resolution))
        existing (graph-read/symbols-by-ids db seeds)
        missing (vec (remove #(contains? existing %) seeds))]
    (when-not (seq seeds)
      (throw (ex-info "Context focus resolution selected no symbols"
                      {:exit-code 2 :focus focus})))
    (when (seq missing)
      (throw (ex-info "Context focus resolution contains unknown symbols"
                      {:exit-code 2 :focus focus :missing-symbol-ids missing})))
    (let [traversal
          (traverse db seeds {:depth depth :max-nodes max-nodes
                              :directions (set directions)
                              :edge-kinds (set edge-kinds)})
          best-count (largest-fitting-prefix
                      focus focus-resolution max-tokens traversal db)]
      (if best-count
        (packet-for focus focus-resolution max-tokens traversal best-count db)
        (let [minimum (packet-for focus focus-resolution Long/MAX_VALUE
                                  traversal 1 db)
              tokens (get-in minimum [:budget :estimated-tokens])]
          (throw
           (ex-info
            (str "Context token budget is too small; minimum is approximately "
                 tokens)
            {:exit-code 2 :minimum-tokens tokens})))))))

(defn build
  [graph {:keys [focus max-tokens] :as options
          :or {max-tokens 8000}}]
  (let [max-nodes (max 1 (quot max-tokens 8))
        resolution (resolve-symbol-focus graph focus max-nodes)]
    (build-from-seeds graph options resolution)))

(defn markdown [packet]
  (str "# Code context: " (:focus packet) "\n\n"
       "Estimated tokens: " (get-in packet [:budget :estimated-tokens])
       " / " (get-in packet [:budget :max-tokens])
       (when (:truncated? packet) " (truncated)") "\n\n"
       "Focus resolution: "
       (name (get-in packet [:focus-resolution :strategy]))
       " (`"
       (str/join "`, `"
                 (map :id (get-in packet [:focus-resolution :selected])))
       "`)\n"
       (when (= :intent (get-in packet [:focus-resolution :mode]))
         (str "Source preference: "
              (name (get-in packet [:focus-resolution :retrieval
                                    :requested-source-preference] :none))
              " → "
              (name (get-in packet [:focus-resolution :retrieval
                                    :resolved-source-preference] :none))
              " ("
              (name (get-in packet [:focus-resolution :retrieval
                                    :source-preference-reason]
                            :explicit-preference))
              ")\n"
              "Selected source role: "
              (str/join ", "
                        (distinct
                         (map #(name (:source-role % :unknown))
                              (get-in packet [:focus-resolution :selected]))))
              "\nQuery plan: "
              (name (get-in packet [:focus-resolution :retrieval
                                    :query-plan :shape] :lookup))
              " / "
              (name (get-in packet [:focus-resolution :retrieval
                                    :query-plan :seed-mode] :single))
              " (" (count (get-in packet [:focus-resolution :selected]))
              " selected)"
              "\nSemantic retrieval: "
              (name (get-in packet [:focus-resolution :retrieval :status]
                            :unknown))
              " in "
              (get-in packet [:focus-resolution :retrieval :latency-ms] 0)
              " ms, timeout "
              (get-in packet [:focus-resolution :retrieval
                              :effective-timeout-ms] 0)
              " ms"
              "\n"))
       "\n"
       (when (seq (get-in packet [:focus-resolution :inventory]))
         (str "## Structurally qualified inventory\n\n"
              (str/join
               "\n"
               (for [{:keys [qualified-name file]}
                     (get-in packet [:focus-resolution :inventory])]
                 (str "- `" qualified-name "`"
                      (when file (str " — `" file "`")))))
              (when (get-in packet [:focus-resolution :inventory-truncated?])
                "\n- … additional qualified candidates omitted by the packet bound")
              "\n\n"))
       "## Symbols\n\n"
       (str/join
        "\n"
        (for [{:keys [id qualified-name kind file line signature doc
                      path-cost selected-path]} (:symbols packet)]
          (str "- `" qualified-name "` — " (name kind) " at `" file ":" line "`"
               " (`" id "`, path cost " path-cost ")"
               (when (seq signature) (str "\n  - Signature: `" signature "`"))
               (when (seq doc) (str "\n  - " (str/replace doc #"\s+" " ")))
               (when (seq selected-path)
                 (str "\n  - Selected path: "
                      (str/join " → " (map (comp name :kind) selected-path)))))))
       "\n\n## Topics\n\n"
       (if (seq (:topics packet))
         (str/join
          "\n"
          (for [{:keys [id kind key path-cost]} (:topics packet)]
            (str "- `" key "` — " (name kind) " (`" id
                 "`, path cost " path-cost ")")))
         "None")
       "\n\n## Relationships\n\n"
       (if (seq (:relationships packet))
         (str/join
          "\n"
          (for [{:keys [kind from to evidence line]} (:relationships packet)]
            (str "- " (name kind) ": `" from "` → `" to
                 "` (" (name evidence) ", line " line ")")))
         "None")
       "\n\n## Effects\n\n"
       (if (seq (:effects packet))
         (str/join "\n"
                   (for [{:keys [kind symbol-id detail]} (:effects packet)]
                     (str "- " (name kind) " in `" symbol-id "`: " detail)))
         "None")
       "\n\n## Diagnostic references\n\n"
       (pr-str (get-in packet [:diagnostics :references]))
       "\n"))
