(ns llm-context.query
  (:require [clojure.string :as str]
            [llm-context.graph.read :as graph-read]
            [llm-context.semantic.hybrid :as hybrid]
            [llm-context.store :as store]))

(defn stats [graph]
  (let [db (store/database graph)
        counts (graph-read/entity-counts db)]
    {:entities (reduce + 0 (vals counts))
     :files (get counts :entity.type/file 0)
     :symbols (get counts :entity.type/symbol 0)
     :edges (get counts :entity.type/edge 0)
     :references (get counts :entity.type/reference 0)
     :topics (get counts :entity.type/topic 0)
     :effects (get counts :entity.type/effect 0)
     :languages (graph-read/grouped-counts db :file/language)
     :symbol-kinds (graph-read/grouped-counts db :symbol/kind)
     :edge-resolution (graph-read/grouped-counts db :edge/resolution)
     :reference-classification
     (graph-read/grouped-counts db :reference/classification)}))

(defn symbols
  "Find symbols by exact name, case-insensitive substring, or Datalevin
  full-text relevance over identifiers, signatures, and documentation."
  ([graph term]
   (symbols graph term nil))
  ([graph term limit]
   (let [db (store/database graph)
        needle (str/lower-case term)
        exact (if limit
                (graph-read/exact-symbols db term limit)
                (graph-read/exact-symbols db term))
        fulltext-query
        (cond-> '[:find ?id ?score
                  :in $ ?query
                  :where
                  [(fulltext $ ?query
                             {:domains ["symbols"]
                              :display :refs+scores})
                   [[?symbol ?attribute ?value ?score]]]
                  [?symbol :symbol/id ?id]
                  :order-by [?score :desc ?id :asc]]
          limit (conj :limit (long limit)))
        fulltext-rows
        (try
          (store/query graph fulltext-query [term])
          (catch Exception _
            ;; Full-text syntax is intentionally richer than identifier search.
            ;; Invalid search expressions still retain the historical literal
            ;; substring behavior instead of failing the command.
            []))
        fulltext-scores
        (reduce (fn [scores [id score]]
                  (update scores id (fnil max Double/NEGATIVE_INFINITY)
                          (double score)))
                {} fulltext-rows)
        fulltext-ids (->> fulltext-scores
                          (sort-by (fn [[id score]] [(- score) id]))
                          (map first))
        substring-ids (try
                        (if limit
                          (graph-read/substring-symbol-ids db needle limit)
                          (graph-read/substring-symbol-ids db needle))
                        (catch Exception _ []))
        exact-ids (set (map :id exact))
        substring-set (set substring-ids)
        candidate-ids (cond->> (distinct (concat (map :id exact)
                                                 fulltext-ids
                                                 substring-ids))
                        limit (take limit))
        candidates (graph-read/symbols-by-ids db candidate-ids)]
    (->> candidate-ids
         (keep (fn [id]
                 (when-let [symbol (get candidates id)]
                   (assoc symbol
                          ::exact? (contains? exact-ids id)
                          ::substring? (contains? substring-set id)
                          ::score (get fulltext-scores id
                                       Double/NEGATIVE_INFINITY)))))
         (sort-by (juxt #(if (::exact? %) 0 (if (::substring? %) 1 2))
                       #(if (Double/isFinite (double (::score %)))
                          (- (double (::score %)))
                          0.0)
                       :qualified-name))
         (mapv #(dissoc % ::exact? ::substring? ::score))))))

(defn search
  "Hybrid lexical and semantic code search. Pass nil as semantic-client for a
  deterministic Datalevin-only fallback."
  [graph semantic-client config term]
  (let [candidate-limit (or (get-in config
                                    [:semantic :lateon-code :candidate-count])
                            50)]
    (hybrid/search graph semantic-client config term
                   (symbols graph term candidate-limit))))

(defn callers [graph target]
  (->> (store/query
        graph
        '[:find ?caller-id ?caller-name ?path ?line ?resolution
          :in $ ?target
          :where [?callee :symbol/id ?target]
                 [?edge :edge/to ?callee]
                 [?edge :edge/from ?caller]
                 [?edge :edge/resolution ?resolution]
                 [?caller :symbol/id ?caller-id]
                 [?caller :symbol/qualified-name ?caller-name]
                 [?caller :symbol/file ?file]
                 [?file :file/path ?path]
                 [?edge :source/start-line ?line]]
        [target])
       (mapv (fn [[id name path line resolution]]
               {:id id :name name :file path :line line
                :resolution resolution}))))

(defn callees [graph source]
  (let [resolved (store/query
                  graph
                  '[:find ?target-id ?target-name ?line ?resolution
                    :in $ ?source
                    :where [?caller :symbol/id ?source]
                           [?edge :edge/from ?caller]
                           [?edge :edge/to ?target]
                           [?target :symbol/id ?target-id]
                           [?target :symbol/qualified-name ?target-name]
                           [?edge :edge/resolution ?resolution]
                           [?edge :source/start-line ?line]]
                  [source])
        unresolved (store/query
                    graph
                    '[:find ?target-text ?line ?resolution
                      :in $ ?source
                      :where [?caller :symbol/id ?source]
                             [?edge :edge/from ?caller]
                             [?edge :edge/target-text ?target-text]
                             [?edge :edge/resolution ?resolution]
                             [?edge :source/start-line ?line]
                             [(not= ?resolution :resolution/exact)]
                             [(not= ?resolution :resolution/heuristic)]]
                    [source])]
    (vec (concat
          (map (fn [[id name line resolution]]
                 {:id id :name name :line line :resolution resolution}) resolved)
          (map (fn [[target line resolution]]
                 {:target target :line line :resolution resolution}) unresolved)))))

(defn effects [graph]
  (->> (store/query
        graph
        '[:find ?kind ?symbol-id ?symbol-name ?path ?line ?detail ?confidence
          :where [?effect :effect/kind ?kind]
                 [?effect :effect/symbol ?symbol]
                 [?symbol :symbol/id ?symbol-id]
                 [?symbol :symbol/qualified-name ?symbol-name]
                 [?symbol :symbol/file ?file]
                 [?file :file/path ?path]
                 [?effect :source/start-line ?line]
                 [?effect :effect/detail ?detail]
                 [?effect :effect/confidence ?confidence]]
        [])
       (mapv (fn [[kind id name path line detail confidence]]
               {:kind kind :symbol-id id :symbol name :file path :line line
                :detail detail :confidence confidence}))))

(defn unresolved [graph]
  (->> (store/query
        graph
        '[:find ?kind ?target ?from-id ?from-name ?path ?line ?resolution
          :where [?edge :edge/kind ?kind]
                 [?edge :edge/target-text ?target]
                 [?edge :edge/from ?from]
                 [?from :symbol/id ?from-id]
                 [?from :symbol/qualified-name ?from-name]
                 [?from :symbol/file ?file]
                 [?file :file/path ?path]
                 [?edge :source/start-line ?line]
                 [?edge :edge/resolution ?resolution]
                 [(not= ?resolution :resolution/exact)]
                 [(not= ?resolution :resolution/heuristic)]]
        [])
       (mapv (fn [[kind target id name path line resolution]]
               {:kind kind :target target :from-id id :from name
                :file path :line line :resolution resolution}))))

(def reachability-rules
  '[[(reachable ?from ?to)
     [?edge :edge/from ?from]
     [?edge :edge/to ?to]]
    [(reachable ?from ?to)
     [?edge :edge/from ?from]
     [?edge :edge/to ?middle]
     (reachable ?middle ?to)]])

(defn transitive-callees [graph source]
  (->> (store/query
        graph
        '[:find ?id ?name
          :in $ % ?source-id
          :where [?source :symbol/id ?source-id]
                 (reachable ?source ?target)
                 [?target :symbol/id ?id]
                 [?target :symbol/qualified-name ?name]]
        [reachability-rules source])
       (mapv (fn [[id name]] {:id id :name name}))))

(defn entry-points [graph]
  (graph-read/entry-points (store/database graph)))
