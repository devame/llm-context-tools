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

(defn graph-quality
  "Return the persisted evidence-quality breakdown. Every traversable edge is
  exact by schema; weaker observations are counted by reference class."
  [graph]
  (let [db (store/database graph)
        counts (graph-read/entity-counts db)
        references (graph-read/grouped-counts db :reference/classification)]
    {:exact-edges (get counts :entity.type/edge 0)
     :references (get counts :entity.type/reference 0)
     :external (get references :external 0)
     :dynamic (get references :dynamic 0)
     :ambiguous (get references :ambiguous 0)
     :unresolved (get references :unresolved 0)}))

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

(defn edit-distance
  "Small allocation-bounded Levenshtein distance used inside Datalevin query
  predicates for missing-symbol suggestions."
  [left right]
  (let [left (str/lower-case (str left))
        right (str/lower-case (str right))]
    (loop [index 0 previous (vec (range (inc (count right))))]
      (if (= index (count left))
        (peek previous)
        (let [character (.charAt left index)
              current
              (reduce
               (fn [row column]
                 (conj row
                       (min (inc (peek row))
                            (inc (nth previous (inc column)))
                            (+ (nth previous column)
                               (if (= character (.charAt right column))
                                 0 1)))))
               [(inc index)] (range (count right)))]
          (recur (inc index) current))))))

(defn symbol-suggestions [graph term]
  (let [maximum (max 2 (min 5 (quot (inc (count term)) 3)))]
    (->> (store/query
          graph
          '[:find ?distance ?id ?qualified ?platform
            :in $ ?term ?maximum
            :where
            [?symbol :symbol/id ?id]
            [?symbol :symbol/name ?name]
            [?symbol :symbol/qualified-name ?qualified]
            [?symbol :symbol/platform ?platform]
            [(llm-context.query/edit-distance ?term ?name) ?distance]
            [(<= ?distance ?maximum)]
            :order-by [?distance :asc ?qualified :asc]
            :limit 8]
          [term maximum])
         (mapv (fn [[distance id qualified platform]]
                 {:id id :qualified-name qualified :platform platform
                  :edit-distance distance})))))

(defn find-symbol [graph term]
  (let [matches (symbols graph term)]
    (if (seq matches)
      matches
      {:matches [] :suggestions (symbol-suggestions graph term)})))

(defn search
  "Hybrid lexical and semantic code search. Pass nil as semantic-client for a
  deterministic Datalevin-only fallback."
  [graph semantic-client config term]
  (let [candidate-limit (or (get-in config
                                    [:semantic :lateon-code :candidate-count])
                            50)]
    (hybrid/search graph semantic-client config term
                   (symbols graph term candidate-limit))))

(defn search-explain [graph semantic-client config term]
  (let [candidate-limit (or (get-in config
                                    [:semantic :lateon-code :candidate-count])
                            50)]
    (hybrid/search-with-metadata
     graph semantic-client config term
     (symbols graph term candidate-limit))))

(defn callers [graph target]
  (->> (store/query
        graph
        '[:find ?caller-id ?caller-name ?path ?line ?platform ?evidence
          :in $ ?target
          :where [?callee :symbol/id ?target]
                 [?edge :edge/to ?callee]
                 [?edge :edge/from ?caller]
                 [?edge :edge/resolution :resolution/exact]
                 [?edge :edge/evidence ?evidence]
                 [?caller :symbol/id ?caller-id]
                 [?caller :symbol/qualified-name ?caller-name]
                 [?caller :symbol/platform ?platform]
                 [?caller :symbol/file ?file]
                 [?file :file/path ?path]
                 [(get-else $ ?edge :source/start-line 1) ?line]
                 :order-by [?caller-name :asc ?line :asc]
                 :limit 100]
        [target])
       (mapv (fn [[id name path line platform evidence]]
               {:id id :name name :file path :line line
                :platform platform :evidence evidence}))))

(defn callees
  ([graph source] (callees graph source {}))
  ([graph source {:keys [include-external?]}]
   (let [exact
         (store/query
          graph
          '[:find ?target-id ?target-name ?line ?platform ?kind ?evidence
            :in $ ?source
            :where
            [?caller :symbol/id ?source]
            [?edge :edge/from ?caller]
            [?edge :edge/to ?target]
            [?target :symbol/id ?target-id]
            [?target :symbol/qualified-name ?target-name]
            [?target :symbol/platform ?platform]
            [?edge :edge/kind ?kind]
            [?edge :edge/resolution :resolution/exact]
            [?edge :edge/evidence ?evidence]
            [(get-else $ ?edge :source/start-line 1) ?line]
            :order-by [?target-name :asc ?line :asc]
            :limit 100]
          [source])
         external
         (when include-external?
           (store/query
            graph
            '[:find ?target ?line ?kind ?evidence
              :in $ ?source
              :where
              [?caller :symbol/id ?source]
              [?reference :reference/symbol ?caller]
              [?reference :reference/classification :external]
              [?reference :reference/target-text ?target]
              [?reference :reference/kind ?kind]
              [?reference :reference/evidence ?evidence]
              [(get-else $ ?reference :source/start-line 1) ?line]
              :order-by [?target :asc ?line :asc]
              :limit 100]
            [source]))]
     (vec
      (concat
       (map (fn [[id name line platform kind evidence]]
              {:id id :name name :line line :platform platform
               :kind kind :evidence evidence})
            exact)
       (map (fn [[target line kind evidence]]
              {:target target :line line :kind kind :evidence evidence
               :classification :external})
            external))))))

(defn callees-command [graph args]
  (let [source (first args)
        options (set (next args))]
    (when-not source
      (throw (ex-info "query callees requires an argument" {:exit-code 2})))
    (when-let [unknown (first (remove #{"--include-external"} options))]
      (throw (ex-info (str "Unknown query callees option: " unknown)
                      {:exit-code 2})))
    (callees graph source
             {:include-external? (contains? options "--include-external")})))

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

(defn unresolved
  ([graph] (unresolved graph {}))
  ([graph {:keys [file platform kind classification]
           :or {classification #{:unresolved :ambiguous}}}]
   (let [classifications (if (set? classification)
                           classification #{classification})
         rows
         (store/query
          graph
          '[:find ?kind ?target ?from-id ?from-name ?path ?line
                  ?platform ?classification ?evidence
            :in $ ?classifications ?file-filter ?platform-filter ?kind-filter
            :where
            [?reference :reference/kind ?kind]
            [?reference :reference/target-text ?target]
            [?reference :reference/classification ?classification]
            [(contains? ?classifications ?classification)]
            [?reference :reference/evidence ?evidence]
            [?reference :reference/symbol ?from]
            [?from :symbol/id ?from-id]
            [?from :symbol/qualified-name ?from-name]
            [?from :symbol/platform ?platform]
            [?from :symbol/file ?file]
            [?file :file/path ?path]
            [(get-else $ ?reference :source/start-line 1) ?line]
            [(or (nil? ?file-filter) (= ?file-filter ?path))]
            [(or (nil? ?platform-filter) (= ?platform-filter ?platform))]
            [(or (nil? ?kind-filter) (= ?kind-filter ?kind))]
            :order-by [?path :asc ?line :asc ?target :asc]
            :limit 500]
          [classifications file platform kind])]
     (mapv (fn [[kind target id name path line platform classification
                 evidence]]
             {:kind kind :target target :from-id id :from name
              :file path :line line :platform platform
              :classification classification :evidence evidence})
           rows))))

(defn- option-pairs [args]
  (loop [remaining (seq args) result {}]
    (if-let [option (first remaining)]
      (if-let [value (second remaining)]
        (recur (nnext remaining) (assoc result option value))
        (throw (ex-info (str option " requires a value") {:exit-code 2})))
      result)))

(defn unresolved-command [graph args]
  (let [options (option-pairs args)
        allowed #{"--file" "--platform" "--kind" "--classification"}]
    (when-let [unknown (first (remove allowed (keys options)))]
      (throw (ex-info (str "Unknown query unresolved option: " unknown)
                      {:exit-code 2})))
    (unresolved
     graph
     (cond-> {}
       (options "--file") (assoc :file (options "--file"))
       (options "--platform") (assoc :platform (keyword (options "--platform")))
       (options "--kind")
       (assoc :kind (keyword "edge.kind" (options "--kind")))
       (options "--classification")
       (assoc :classification (keyword (options "--classification")))))))

(defn topic-relationships
  "Return bounded exact relationships for a topic ID or literal key."
  ([graph topic] (topic-relationships graph topic {}))
  ([graph topic {:keys [edge-kind]}]
   (->> (store/query
         graph
         '[:find ?topic-id ?topic-kind ?key ?edge-kind ?symbol-id
                 ?qualified ?platform ?path ?line ?evidence
           :in $ ?topic ?edge-kind-filter
           :where
           (or [?target :topic/id ?topic]
               [?target :topic/key ?topic])
           [?target :topic/id ?topic-id]
           [?target :topic/kind ?topic-kind]
           [?target :topic/key ?key]
           [?edge :edge/to ?target]
           [?edge :edge/kind ?edge-kind]
           [(or (nil? ?edge-kind-filter)
                (= ?edge-kind-filter ?edge-kind))]
           [?edge :edge/from ?symbol]
           [?edge :edge/evidence ?evidence]
           [?symbol :symbol/id ?symbol-id]
           [?symbol :symbol/qualified-name ?qualified]
           [?symbol :symbol/platform ?platform]
           [?symbol :symbol/file ?file]
           [?file :file/path ?path]
           [(get-else $ ?edge :source/start-line 1) ?line]
           :order-by [?edge-kind :asc ?qualified :asc ?line :asc]
           :limit 200]
         [topic edge-kind])
        (mapv (fn [[topic-id topic-kind key edge-kind symbol-id qualified
                    platform path line evidence]]
                {:topic-id topic-id :topic-kind topic-kind :key key
                 :relationship edge-kind :symbol-id symbol-id
                 :symbol qualified :platform platform :file path :line line
                 :evidence evidence})))))

(def topic-command-kinds
  {"registrations" :edge.kind/topic-registers
   "dispatchers" :edge.kind/event-dispatches
   "subscribers" :edge.kind/subscribes
   "state-readers" :edge.kind/state-reads
   "state-writers" :edge.kind/state-writes})

(defn topics-command [graph subcommand args]
  (let [topic (first args)]
    (when-not topic
      (throw (ex-info (str "query " subcommand " requires a topic ID or key")
                      {:exit-code 2})))
    (topic-relationships
     graph topic
     (cond-> {}
       (get topic-command-kinds subcommand)
       (assoc :edge-kind (get topic-command-kinds subcommand))))))

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
