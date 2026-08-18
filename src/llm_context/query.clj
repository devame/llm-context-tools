(ns llm-context.query
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [llm-context.graph.read :as graph-read]
            [llm-context.intent :as intent]
            [llm-context.intent.reranker :as learned-reranker]
            [llm-context.model.schema :as schema]
            [llm-context.semantic.hybrid :as hybrid]
            [llm-context.semantic.mode :as retrieval-mode]
            [llm-context.source-role :as source-role]
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
     :aggregates (get counts :entity.type/aggregate 0)
     :memberships (get counts :entity.type/membership 0)
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

(defn lexical-candidates
  "Select a bounded identifier candidate pool through the rarest indexed
  character gram present in the query."
  [graph term limit]
  (let [grams (schema/symbol-search-grams
               {:symbol/name term :symbol/qualified-name term})
        frequencies
        (when (seq grams)
          (store/query
           graph
           '[:find ?gram (count ?symbol)
             :in $ [?gram ...]
             :where [?symbol :symbol/search-grams ?gram]]
           [(vec grams)]))
        rarest (some->> frequencies
                        (sort-by (juxt second first))
                        ffirst)]
    (if-not rarest
      []
      (->> (store/query
            graph
            (conj
             '[:find ?id ?name ?qualified ?platform
               :in $ ?gram
               :where
               [?symbol :symbol/search-grams ?gram]
               [?symbol :symbol/id ?id]
               [?symbol :symbol/name ?name]
               [?symbol :symbol/qualified-name ?qualified]
               [?symbol :symbol/platform ?platform]
               :order-by [?qualified :asc ?id :asc]
               :limit]
             (long limit))
            [rarest])
           (map (fn [[id name qualified platform]]
                  {:id id :name name :qualified-name qualified
                   :platform platform}))
           (sort-by
            (fn [{:keys [name qualified-name]}]
              [(- (count
                   (set/intersection
                    grams
                    (schema/symbol-search-grams
                     {:symbol/name name
                      :symbol/qualified-name qualified-name}))))
               qualified-name]))
           vec))))

(defn- invalid-fulltext-expression? [error]
  (boolean
   (re-find
    #"(?i)(full.?text.*(parse|syntax)|parse.*(full.?text|query)|query.*syntax|lexical error|encountered .* at line)"
    (or (.getMessage ^Throwable error) ""))))

(defn- fulltext-or-literal [operation]
  (try
    (operation)
    (catch Exception error
      (if (invalid-fulltext-expression? error)
        []
        (throw error)))))

(defn symbols
  "Find symbols by exact name, case-insensitive substring, or Datalevin
  full-text relevance over identifiers, signatures, and documentation."
  ([graph term]
   (symbols graph term 128))
  ([graph term limit]
   (let [db (store/database graph)
        needle (str/lower-case term)
        candidate-limit (or limit 128)
        exact (graph-read/exact-symbols db term candidate-limit)
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
          true (conj :limit (long candidate-limit)))
        fulltext-rows
        (fulltext-or-literal
         #(store/query graph fulltext-query [term]))
        fulltext-scores
        (reduce (fn [scores [id score]]
                  (update scores id (fnil max Double/NEGATIVE_INFINITY)
                          (double score)))
                {} fulltext-rows)
        fulltext-ids (->> fulltext-scores
                          (sort-by (fn [[id score]] [(- score) id]))
                          (map first))
        primary-ids (distinct (concat (map :id exact) fulltext-ids))
        substring-ids
        (when-not (seq primary-ids)
          (->> (lexical-candidates graph term candidate-limit)
               (keep (fn [{:keys [id name qualified-name]}]
                       (when (or (str/includes? (str/lower-case name) needle)
                                 (str/includes?
                                  (str/lower-case qualified-name) needle))
                         id)))))
        exact-ids (set (map :id exact))
        substring-set (set substring-ids)
        candidate-ids (take candidate-limit
                            (concat primary-ids substring-ids))
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

(defn aggregate-symbols
  "Find canonical owner symbols through the independent aggregate full-text
  domain. This increases recall for registry and inventory questions without
  inventing symbol relationships or replacing ordinary symbol retrieval."
  ([graph term] (aggregate-symbols graph term 32))
  ([graph term limit]
   (let [query-form
         (cond-> '[:find ?symbol-id ?score
                   :in $ ?query
                   :where
                   [(fulltext $ ?query
                              {:domains ["aggregates"]
                               :display :refs+scores})
                    [[?aggregate ?attribute ?value ?score]]]
                   [?aggregate :aggregate/owner ?symbol]
                   [?symbol :symbol/id ?symbol-id]
                   :order-by [?score :desc ?symbol-id :asc]]
           true (conj :limit (long limit)))
         rows (fulltext-or-literal
               #(store/query graph query-form [term]))
         ids (->> rows (map first) distinct vec)
         symbols (graph-read/symbols-by-ids (store/database graph) ids)
         aggregates (graph-read/aggregates-for-symbols
                     (store/database graph) ids)]
     (mapv (fn [id]
             (assoc (get symbols id)
                    :retrieval-classes #{:aggregate}
                    :aggregates (get aggregates id [])))
           (filter #(contains? symbols %) ids)))))

(defn edit-distance
  "Small allocation-bounded Levenshtein distance used only after indexed
  candidate selection for missing-symbol suggestions."
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
    (->> (lexical-candidates graph term 128)
         (keep (fn [{:keys [id name qualified-name platform]}]
                 (let [distance (edit-distance term name)]
                   (when (<= distance maximum)
                     {:id id :qualified-name qualified-name
                      :platform platform :edit-distance distance}))))
         (sort-by (juxt :edit-distance :qualified-name :id))
         (take 8)
         vec)))

(defn find-symbol [graph term]
  (let [matches (symbols graph term)]
    (if (seq matches)
      matches
      {:matches [] :suggestions (symbol-suggestions graph term)})))

(defn parse-search-args
  "Parse query-search arguments shared by the CLI and resident service."
  [args]
  (let [term (or (first args)
                 (throw (ex-info "query search requires an argument"
                                 {:exit-code 2})))]
    (loop [remaining (next args)
           result {:term term
                   :mode retrieval-mode/default
                   :source-preference :none
                   :intent-rerank? false
                   :semantic-timeout-ms nil
                   :explain? false}]
      (if-let [argument (first remaining)]
        (case argument
          "--explain" (recur (next remaining) (assoc result :explain? true))
          ("--mode" "--retrieval-mode")
          (if-let [value (second remaining)]
            (recur (nnext remaining)
                   (assoc result :mode (retrieval-mode/normalize value)))
            (throw (ex-info (str argument " requires fts-only, lateon-only, or hybrid")
                            {:exit-code 2})))
          "--source-preference"
          (if-let [value (second remaining)]
            (recur (nnext remaining)
                   (assoc result :source-preference
                          (source-role/normalize-preference value)))
            (throw (ex-info "--source-preference requires auto, production, test, or none"
                            {:exit-code 2})))
          "--intent-rerank"
          (recur (next remaining) (assoc result :intent-rerank? true))
          "--semantic-timeout-ms"
          (if-let [value (second remaining)]
            (let [timeout (parse-long value)]
              (when-not (pos-int? timeout)
                (throw (ex-info "--semantic-timeout-ms requires a positive integer"
                                {:exit-code 2})))
              (recur (nnext remaining)
                     (assoc result :semantic-timeout-ms timeout)))
            (throw (ex-info "--semantic-timeout-ms requires a positive integer"
                            {:exit-code 2})))
          (throw (ex-info (str "Unknown query search option: " argument)
                          {:exit-code 2})))
        result))))

(declare search-explain)

(defn search
  "Search using FTS-only, LateOn-only, or hybrid retrieval.

  The four-argument form remains the hybrid default. Pass {:mode ...} as the
  optional fifth argument for an explicit ablation."
  ([graph semantic-client config term]
   (search graph semantic-client config term {}))
  ([graph semantic-client config term {:keys [mode source-preference]
                                       :or {mode retrieval-mode/default
                                            source-preference :none}}]
   (let [mode (retrieval-mode/normalize mode)
         candidate-limit (or (get-in config
                                     [:semantic :lateon-code :candidate-count])
                             50)
         lexical-results (symbols graph term candidate-limit)]
     (case mode
       :fts-only
       (:results (hybrid/fts-explain lexical-results))

       (:lateon-only :hybrid)
       (:results
        (search-explain graph semantic-client config term
                        {:mode mode :source-preference source-preference}))))))

(defn semantic-search-attempt
  "Run only the external semantic phase of search.

  FTS-only deliberately does not contact the semantic sidecar."
  ([semantic-client config term]
   (semantic-search-attempt semantic-client config term retrieval-mode/default))
  ([semantic-client config term mode-or-options]
   (let [{:keys [mode] :as options}
         (if (map? mode-or-options) mode-or-options {:mode mode-or-options})
         mode (retrieval-mode/normalize (or mode retrieval-mode/default))]
     (if (= :fts-only mode)
       {:mode mode :status :not-requested :candidates [] :latency-ms 0
        :requested-timeout-ms (:semantic-timeout-ms options)
        :effective-timeout-ms (or (:semantic-timeout-ms options)
                                  (get-in config [:semantic :lateon-code
                                                  :query-timeout-ms]))}
       (hybrid/retrieve semantic-client config term mode
                        {:candidate-count (:candidate-count options)
                         :timeout-ms (:semantic-timeout-ms options)})))))

(defn- intent-lexical-results [graph term limit enabled?]
  (let [channels (if enabled?
                   [(symbols graph term limit)
                    (aggregate-symbols graph term limit)]
                   [(symbols graph term limit)])]
    (->> channels
         (apply concat)
         (reduce (fn [{:keys [seen results]} candidate]
                   (if (contains? seen (:id candidate))
                     (update {:seen seen :results results} :results
                             (fn [values]
                               (mapv (fn [existing]
                                       (if (= (:id existing) (:id candidate))
                                         (merge-with
                                          (fn [left right]
                                            (if (and (set? left) (set? right))
                                              (set/union left right)
                                              (or right left)))
                                          existing candidate)
                                         existing))
                                     values)))
                     {:seen (conj seen (:id candidate))
                      :results (conj results candidate)}))
                 {:seen #{} :results []})
         :results
         (take limit)
         vec)))

(def ^:private flow-edge-kinds
  #{:edge.kind/calls :edge.kind/macro-invokes})

(defn- exact-relationship-count
  "Count exact execution edges among structurally qualified candidates.
  Generic containment, imports, implementation, and reference edges do not
  establish an ordered flow."
  [graph candidates]
  (let [ids (->> candidates
                 (filter #(and (:structurally-qualified? %)
                               (pos? (double (:intent-score % 0.0)))))
                 (take 12) (map :id) distinct vec)]
    (if (< (count ids) 2)
      0
      (or (some-> (store/query
                   graph
                   '[:find (count ?edge)
                     :in $ [?from-id ...] [?to-id ...] [?kind ...]
                     :where
                     [?from :symbol/id ?from-id]
                     [?to :symbol/id ?to-id]
                     [?edge :edge/from ?from]
                     [?edge :edge/to ?to]
                     [?edge :edge/kind ?kind]
                     [?edge :edge/resolution :resolution/exact]]
                   [ids ids (vec flow-edge-kinds)])
                  ffirst long)
          0))))

(defn search-explain-with-attempt
  "Fuse a completed semantic attempt with current lexical and graph state."
  ([graph config term semantic-attempt]
   (search-explain-with-attempt
    graph config term semantic-attempt {}))
  ([graph config term semantic-attempt options-or-mode]
   (let [{:keys [mode source-preference intent-rerank? seed-mode max-seeds
                 intent-advisory candidate-reranker]}
         (if (map? options-or-mode)
           options-or-mode
           {:mode options-or-mode})
         mode (retrieval-mode/normalize
               (or mode (:mode semantic-attempt) retrieval-mode/default))
         source-preference (source-role/normalize-preference
                            (or source-preference :none))
         plan (intent/analyze
               term {:seed-mode (if intent-rerank?
                                  (or seed-mode
                                      (get-in config [:context
                                                      :intent-seed-mode]))
                                  :single)
                     :max-seeds (or max-seeds
                                    (get-in config [:context :intent-max-seeds]))
                     :default-max-seeds
                     (get-in config [:context :intent-max-seeds])
                     :default-candidate-count
                     (get-in config [:semantic :lateon-code :candidate-count])
                     :semantic-candidate-count
                     (get-in config [:semantic :lateon-code :candidate-count])
                     :multi-candidate-count
                     (get-in config [:context :intent-candidate-count])})
         candidate-limit (or (:candidate-count plan)
                             (get-in config [:semantic :lateon-code :candidate-count])
                             50)
         lexical-results (intent-lexical-results
                          graph term candidate-limit intent-rerank?)
         response
         (case mode
           :fts-only (hybrid/fts-explain lexical-results)
           :lateon-only (hybrid/fuse-with-metadata
                         graph config term [] semantic-attempt mode)
           :hybrid (hybrid/fuse-with-metadata
                    graph config term lexical-results semantic-attempt mode))
         aggregate-evidence
         (graph-read/aggregates-for-symbols
          (store/database graph) (mapv :id (:results response)))
         response
         (update response :results
                 (fn [results]
                   (mapv (fn [candidate]
                           (if-let [aggregates
                                    (seq (get aggregate-evidence
                                              (:id candidate)))]
                             (assoc candidate
                                    :aggregates (vec aggregates)
                                    :retrieval-classes
                                    (conj (set (:retrieval-classes candidate))
                                          :aggregate))
                             candidate))
                         results)))
         preferred
         (source-role/prefer
          (:results response) term source-preference
          (get-in config [:context :source-role-overrides]))
         {:keys [requested resolved reason]} (:resolution preferred)
         reranked (if intent-rerank?
                    (learned-reranker/safely-rerank
                     candidate-reranker term (:results preferred))
                    {:results (:results preferred)
                     :provider :none :status :not-requested
                     :candidate-count 0 :cache-hits 0 :cache-misses 0
                     :latency-ms 0
                     :reordered? false})
         qualification-plan
         (assoc plan :advisory-shape (:suggested-shape intent-advisory))
         qualified (if intent-rerank?
                     (intent/qualify term (:results reranked)
                                     qualification-plan)
                     {:results (:results reranked)
                      :provider :none :status :not-requested
                      :reordered? false})
         resolved-plan
         (if intent-rerank?
           (intent/resolve-plan
            plan (:results qualified)
            {:advisory (or intent-advisory
                           {:provider :none :status :not-requested})
             :minimum-advisory-margin
             (get-in config [:context :query-router :minimum-margin])
             :exact-relationship-count
             (exact-relationship-count graph (:results qualified))})
           plan)]
     (assoc response
            :results (:results qualified)
            :retrieval
            (assoc (:retrieval response)
                   :requested-source-preference requested
                   :resolved-source-preference resolved
                   :source-preference-reason reason
                   :source-role-counts (:role-counts preferred)
                   :source-preference-reordered? (:reordered? preferred)
                   :query-plan (dissoc resolved-plan :query-terms)
                   :reranker (select-keys
                              reranked
                              [:provider :status :reason :detail :model
                               :model-revision :mode :candidate-count
                               :cache-hits :cache-misses :latency-ms
                               :would-reorder? :reordered?])
                   :structural-qualification
                   {:provider (:provider qualified)
                    :status (:status qualified)
                    :reordered? false})))))

(defn search-explain
  ([graph semantic-client config term]
   (search-explain graph semantic-client config term {}))
  ([graph semantic-client config term {:keys [mode source-preference
                                              intent-rerank?
                                              semantic-timeout-ms seed-mode
                                              max-seeds intent-advisory
                                              candidate-reranker]
                                       :or {mode retrieval-mode/default
                                            source-preference :none}}]
   (let [mode (retrieval-mode/normalize mode)
         plan (intent/analyze
               term {:seed-mode (if intent-rerank?
                                  (or seed-mode
                                      (get-in config [:context
                                                      :intent-seed-mode]))
                                  :single)
                     :max-seeds (or max-seeds
                                    (get-in config [:context :intent-max-seeds]))
                     :default-max-seeds
                     (get-in config [:context :intent-max-seeds])
                     :default-candidate-count
                     (get-in config [:semantic :lateon-code :candidate-count])
                     :semantic-candidate-count
                     (get-in config [:semantic :lateon-code :candidate-count])
                     :multi-candidate-count
                     (get-in config [:context :intent-candidate-count])})]
     (search-explain-with-attempt
      graph config term
      (semantic-search-attempt
       semantic-client config term
       {:mode mode :semantic-timeout-ms semantic-timeout-ms
        :candidate-count (:semantic-candidate-count plan)})
      {:mode mode :source-preference source-preference
       :intent-rerank? intent-rerank? :seed-mode seed-mode
       :max-seeds max-seeds :intent-advisory intent-advisory
       :candidate-reranker candidate-reranker}))))

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

(defn transitive-callees
  "Return a bounded, cycle-safe breadth-first trace over exact call edges."
  ([graph source]
   (transitive-callees graph source {:depth 4 :limit 200}))
  ([graph source {:keys [depth limit] :or {depth 4 limit 200}}]
   (when-not (pos-int? depth)
     (throw (ex-info "Trace depth must be a positive integer"
                     {:exit-code 2 :depth depth})))
   (when-not (pos-int? limit)
     (throw (ex-info "Trace limit must be a positive integer"
                     {:exit-code 2 :limit limit})))
   (let [db (store/database graph)]
     (when-not (graph-read/symbol-by-id db source)
       (throw (ex-info (str "Unknown trace source: " source)
                       {:exit-code 2 :source source})))
     (let [finish
           (fn [results depth-truncated? limit-truncated?]
             {:source source
              :depth depth
              :limit limit
              :results (vec (sort-by (juxt :depth :name :id) results))
              :truncated? (or depth-truncated? limit-truncated?)
              :truncation {:depth? depth-truncated?
                           :limit? limit-truncated?}})]
       (loop [frontier [source]
              visited #{source}
              level 0
              results []]
         (cond
           (empty? frontier)
           (finish results false false)

           (>= level depth)
           (finish results
                   (boolean (seq (graph-read/outgoing-call-targets
                                  db frontier visited 1)))
                   false)

           :else
           (let [remaining (- limit (count results))
                 candidates (graph-read/outgoing-call-targets
                             db frontier visited (inc remaining))
                 overflow? (> (count candidates) remaining)
                 selected (vec (take remaining candidates))
                 next-depth (inc level)
                 layer (mapv (fn [{:keys [id qualified-name]}]
                               {:id id :name qualified-name
                                :depth next-depth})
                             selected)
                 next-frontier (mapv :id selected)
                 visited (into visited next-frontier)
                 results (into results layer)]
             (cond
               overflow?
               (finish results false true)

               (= (count results) limit)
               (let [deeper? (boolean
                              (seq (graph-read/outgoing-call-targets
                                    db next-frontier visited 1)))]
                 (finish results
                         (and (= next-depth depth) deeper?)
                         deeper?))

               :else
               (recur next-frontier visited next-depth results)))))))))

(defn trace-command [graph settings args]
  (let [source (first args)]
    (when-not source
      (throw (ex-info "query trace requires an argument" {:exit-code 2})))
    (let [options (option-pairs (next args))
          allowed #{"--depth" "--limit"}]
      (when-let [unknown (first (remove allowed (keys options)))]
        (throw (ex-info (str "Unknown query trace option: " unknown)
                        {:exit-code 2})))
      (let [parse-positive
            (fn [option default]
              (if-let [value (get options option)]
                (let [parsed (parse-long value)]
                  (when-not (pos-int? parsed)
                    (throw (ex-info (str option " must be a positive integer")
                                    {:exit-code 2 :option option
                                     :value value})))
                  parsed)
                default))]
        (transitive-callees
         graph source
         {:depth (parse-positive "--depth"
                                 (get-in settings [:context :trace-depth] 4))
          :limit (parse-positive "--limit"
                                 (get-in settings [:context :trace-limit]
                                         200))})))))

(defn entry-points [graph]
  (graph-read/entry-points (store/database graph)))
