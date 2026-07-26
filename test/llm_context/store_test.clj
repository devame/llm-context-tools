(ns llm-context.store-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [llm-context.config :as config]
            [llm-context.model.ids :as ids]
            [llm-context.model.schema :as schema]
            [llm-context.project :as project]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.state :as semantic-state]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(defn temp-project []
  (project/context (str (Files/createTempDirectory "llm-context-store-"
                                                   (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn file-entity [path content]
  {:entity/type :entity.type/file
   :file/id (ids/file-id path)
   :file/path path
   :file/language :language/clojure
   :file/content-hash (ids/content-hash content)
   :file/size (count (.getBytes content java.nio.charset.StandardCharsets/UTF_8))
   :file/modified-at 100})

(defn symbol-entity [file name line]
  (let [parts {:file-id (:file/id file) :kind :symbol.kind/function
               :qualified-name name :signature "[]"
               :start-line line :start-column 1}]
    {:entity/type :entity.type/symbol
     :symbol/id (ids/symbol-id parts)
     :symbol/name name
     :symbol/qualified-name name
     :symbol/kind :symbol.kind/function
     :symbol/file (:file/id file)
     :symbol/platform :clj
     :symbol/analyzer :test
     :symbol/scope :scope/top-level
     :symbol/role :role/definition
     :symbol/indexable? true
     :symbol/signature "[]"
     :source/start-line line :source/start-column 1
     :source/end-line line :source/end-column 10}))

(defn fulltext-symbol-names [graph query]
  (set
   (store/query
    graph
    '[:find [?name ...]
      :in $ ?query
      :where
      [(fulltext $ ?query {:domains ["symbols"]})
       [[?symbol ?attribute ?value]]]
      [?symbol :symbol/qualified-name ?name]]
    [query])))

(deftest native-datalevin-round-trip
  (let [project (temp-project)
        file (file-entity "src/a.clj" "(defn a [])")
        symbol (symbol-entity file "sample/a" 1)]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file [symbol])
      (is (= #{["sample/a"]}
             (store/query graph
                          '[:find ?name :where [_ :symbol/qualified-name ?name]]
                          [])))
      (is (= 2 (count (store/query graph
                                  '[:find [?entity ...]
                                    :where [?entity :entity/type _]] [])))))))

(deftest graph-format-metadata-gates-legacy-derived-state
  (let [project (temp-project)
        settings (config/defaults)
        file (file-entity "src/legacy.clj" "(defn legacy [])")]
    (store/with-store [graph project settings]
      (is (= :empty (store/graph-state graph)))
      (store/replace-file! graph file [])
      (is (= :incompatible (store/graph-state graph)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"analyze --full"
           (store/assert-query-compatible! graph)))
      (store/write-graph-metadata!
       graph {:analyzer-name "fixture"
              :analyzer-version "1"
              :janet-catalog-version "1.41.2"
              :semantic-document-version 2
              :semantic-index-name "llm-context-v2"})
      (is (= :ready (store/graph-state graph)))
      (is (= schema/graph-format-version
             (:llm-context/graph-format (store/graph-metadata graph)))))))

(deftest existing-symbols-are-backfilled-into-full-text-search
  (let [project (temp-project)
        file (file-entity "src/legacy.clj" "legacy")
        symbol (assoc (symbol-entity file "sample/legacy-loader" 1)
                      :symbol/doc "Hydrate historical project state")
        legacy-schema (dissoc schema/datalevin-schema :symbol/search-text)
        path (.normalize (.resolve (:root project) ".llm-context/db"))]
    (Files/createDirectories path
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (with-open [connection (d/get-conn (str path) legacy-schema)]
      (d/transact! connection
                   [(assoc file :db/id -1)
                    (assoc symbol :db/id -2 :symbol/file -1)]))
    (store/with-store [graph project (config/defaults)]
      (is (= #{["sample/legacy-loader"]}
             (store/query
              graph
              '[:find ?qualified
                :where
                [(fulltext $ "historical project"
                           {:domains ["symbols"]})
                 [[?symbol ?attribute ?value]]]
                [?symbol :symbol/qualified-name ?qualified]]
              [])))
      (is (= 1
             (store/query
              graph
              '[:find ?version .
                :where [?meta :llm-context/meta-key "search-index"]
                       [?meta :llm-context/search-schema-version ?version]]
              []))))))

(deftest replacement-and-deletion-are-cascading
  (let [project (temp-project)
        file (file-entity "src/a.clj" "old")
        old-symbol (symbol-entity file "sample/old" 1)
        new-file (file-entity "src/a.clj" "new")
        new-symbol (symbol-entity new-file "sample/new" 2)]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file [old-symbol])
      (is (= #{"sample/old"} (fulltext-symbol-names graph "old")))
      (store/replace-file! graph new-file [new-symbol])
      (is (empty? (fulltext-symbol-names graph "old")))
      (is (= #{"sample/new"} (fulltext-symbol-names graph "new")))
      (is (= #{"sample/new"}
             (set (store/query graph
                               '[:find [?name ...]
                                 :where [_ :symbol/qualified-name ?name]]
                               []))))
      (store/delete-file! graph (:file/id new-file))
      (is (empty? (fulltext-symbol-names graph "new")))
      (is (empty? (store/query graph
                               '[:find [?entity ...]
                                 :where [?entity :entity/type _]]
                               []))))))

(deftest retained-file-facts-update-in-place-and-remove-stale-attributes
  (let [project (temp-project)
        file (file-entity "src/a.clj" "old")
        target-file (file-entity "src/target.clj" "target")
        symbol (assoc (symbol-entity file "sample/a" 1)
                      :symbol/doc "old documentation")
        target (symbol-entity target-file "sample/target" 1)
        edge {:entity/type :entity.type/edge
              :edge/id "edge:retained"
              :edge/kind :edge.kind/calls
              :edge/from (:symbol/id symbol)
              :edge/to (:symbol/id target)
              :edge/target-text "sample/target"
              :edge/resolution :resolution/exact
              :edge/confidence 1.0
              :edge/evidence :test-exact
              :source/snippet "(target)"}
        reference {:entity/type :entity.type/reference
                   :reference/id "reference:retained"
                   :reference/symbol (:symbol/id symbol)
                   :reference/kind :edge.kind/calls
                   :reference/target-text "dynamic-target"
                   :reference/classification :dynamic
                   :reference/evidence :test-dynamic
                   :source/snippet "(dynamic-target)"}
        effect {:entity/type :entity.type/effect
                :effect/id "effect:retained"
                :effect/kind :effect.kind/logging
                :effect/symbol (:symbol/id symbol)
                :effect/detail "old detail"
                :effect/confidence 1.0
                :source/snippet "(println value)"}
        new-file (file-entity "src/a.clj" "new")
        new-symbol (dissoc symbol :symbol/doc)
        new-edge (dissoc edge :source/snippet)
        new-reference (dissoc reference :source/snippet)
        new-effect (-> effect
                       (assoc :effect/detail "new detail")
                       (dissoc :source/snippet))]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph target-file [target])
      (store/replace-file! graph file [symbol edge reference effect])
      (store/replace-file! graph new-file
                           [new-symbol new-edge new-reference new-effect])
      (is (= #{["edge:retained"]
               ["reference:retained"]
               ["effect:retained"]}
             (set
              (store/query
               graph
               '[:find ?identity
                 :where
                 (or [_ :edge/id ?identity]
                     [_ :reference/id ?identity]
                     [_ :effect/id ?identity])]
               []))))
      (is (empty?
           (store/query
            graph
            '[:find ?value
              :where
              (or [_ :symbol/doc ?value]
                  [_ :source/snippet ?value])]
            [])))
      (is (= #{["new detail"]}
             (store/query
              graph
              '[:find ?detail :where [_ :effect/detail ?detail]]
              []))))))

(deftest file-mutations-can-atomically-assert-semantic-dirty-markers
  (let [project (temp-project)
        file (file-entity "src/a.clj" "old")
        symbol (symbol-entity file "sample/a" 1)]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file-and-mark!
       graph file [symbol]
       [(semantic-reconcile/dirty-entity
         (:file/id file) (:file/content-hash file) :upsert 10)])
      (is (= #{["sample/a"]}
             (store/query graph
                          '[:find ?name
                            :where [_ :symbol/qualified-name ?name]]
                          [])))
      (is (= :upsert
             (:semantic.dirty/operation
              (first (semantic-state/dirty-records
                      graph :lateon-code)))))
      (store/delete-file-and-mark!
       graph (:file/id file)
       [(semantic-reconcile/dirty-entity
         (:file/id file) nil :delete 20)])
      (is (empty? (store/query graph
                               '[:find [?entity ...]
                                 :where [?entity :entity/type _]]
                               [])))
      (let [marker (first (semantic-state/dirty-records
                           graph :lateon-code))]
        (is (= :delete (:semantic.dirty/operation marker)))
        (is (nil? (:semantic.dirty/file-hash marker)))))))

(deftest whole-graph-replacement-resolves-forward-cross-file-references
  (let [project (temp-project)
        source-file (file-entity "src/source.clj" "source")
        target-file (file-entity "src/target.clj" "target")
        source (symbol-entity source-file "sample/source" 1)
        target (symbol-entity target-file "sample/target" 1)
        edge {:entity/type :entity.type/edge
              :edge/id "edge:forward"
              :edge/kind :edge.kind/calls
              :edge/from (:symbol/id source)
              :edge/to (:symbol/id target)
              :edge/target-text "sample/target"
              :edge/resolution :resolution/exact
              :edge/confidence 1.0
              :edge/evidence :test-exact}]
    (store/with-store [graph project (config/defaults)]
      ;; The edge intentionally precedes its target in input order.
      (store/replace-all! graph [source-file source edge target-file target])
      (is (= #{["edge:forward" "sample/target"]}
             (store/query graph
                          '[:find ?edge-id ?target-name
                            :where [?edge :edge/id ?edge-id]
                                   [?edge :edge/to ?target]
                                   [?target :symbol/qualified-name ?target-name]]
                          [])))
      (store/replace-all! graph [])
      (is (empty? (store/query graph
                               '[:find [?entity ...]
                                 :where [?entity :entity/type _]]
                               []))))))

(deftest transaction-order-puts-reference-targets-first
  (let [file (file-entity "src/a.clj" "source")
        symbol (symbol-entity file "sample/a" 1)
        edge {:entity/type :entity.type/edge :edge/id "edge:a"
              :edge/from (:symbol/id symbol) :edge/to (:symbol/id symbol)}
        effect {:entity/type :entity.type/effect :effect/id "effect:a"
                :effect/symbol (:symbol/id symbol)}]
    (is (= [:entity.type/file :entity.type/symbol
            :entity.type/edge :entity.type/effect]
           (mapv :entity/type
                 (#'store/dependency-order [effect edge symbol file]))))))

(deftest duplicate-canonical-identities-are-rejected
  (let [project (temp-project)
        file (file-entity "src/a.clj" "source")]
    (store/with-store [graph project (config/defaults)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Duplicate canonical entity identities"
           (store/replace-all! graph [file file]))))))

(deftest exact-edges-cannot-target-missing-graph-identities
  (let [project (temp-project)
        file (file-entity "src/a.clj" "source")
        source (symbol-entity file "sample/source" 1)
        edge {:entity/type :entity.type/edge
              :edge/id "edge:missing"
              :edge/kind :edge.kind/calls
              :edge/from (:symbol/id source)
              :edge/to "symbol:missing"
              :edge/target-text "sample/missing"
              :edge/resolution :resolution/exact
              :edge/confidence 1.0
              :edge/evidence :test-exact}]
    (store/with-store [graph project (config/defaults)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"relationships refer to missing owners or targets"
           (store/replace-all! graph [file source edge]))))))

(deftest every-canonical-relationship-requires-an-asserted-owner
  (let [project (temp-project)
        file (file-entity "src/a.clj" "source")
        symbol (symbol-entity file "sample/source" 1)
        missing-file-symbol
        (assoc symbol :symbol/id "symbol:missing-file-owner"
               :symbol/file "file:missing")
        missing-from
        {:entity/type :entity.type/edge
         :edge/id "edge:missing-from"
         :edge/kind :edge.kind/calls
         :edge/from "symbol:missing"
         :edge/to (:symbol/id symbol)
         :edge/target-text "sample/source"
         :edge/resolution :resolution/exact
         :edge/confidence 1.0
         :edge/evidence :test-exact}
        missing-reference
        {:entity/type :entity.type/reference
         :reference/id "reference:missing-owner"
         :reference/symbol "symbol:missing"
         :reference/kind :edge.kind/calls
         :reference/target-text "dynamic"
         :reference/classification :dynamic
         :reference/evidence :test-dynamic}
        missing-effect
        {:entity/type :entity.type/effect
         :effect/id "effect:missing-owner"
         :effect/kind :effect.kind/logging
         :effect/symbol "symbol:missing"
         :effect/detail "fixture"
         :effect/confidence 1.0}]
    (store/with-store [graph project (config/defaults)]
      (doseq [snapshot [[file missing-file-symbol]
                        [file symbol missing-from]
                        [file missing-reference]
                        [file missing-effect]]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"relationships refer to missing owners or targets"
             (store/validate-replacement! graph snapshot)))))))

(deftest file-replacement-rejects-facts-owned-by-another-file
  (let [project (temp-project)
        file-a (file-entity "src/a.clj" "a")
        file-b (file-entity "src/b.clj" "b")
        symbol-a (symbol-entity file-a "sample/a" 1)
        symbol-b (symbol-entity file-b "sample/b" 1)
        edge {:entity/type :entity.type/edge
              :edge/id "edge:foreign-owner"
              :edge/kind :edge.kind/calls
              :edge/from (:symbol/id symbol-b)
              :edge/to (:symbol/id symbol-a)
              :edge/target-text "sample/a"
              :edge/resolution :resolution/exact
              :edge/confidence 1.0
              :edge/evidence :test-exact}]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file-a [symbol-a])
      (store/replace-file! graph file-b [symbol-b])
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"facts owned by another file"
           (store/replace-file! graph file-a [symbol-a edge]))))))

(deftest interrupted-full-replacement-is-explicitly-unavailable-until-activation
  (let [project (temp-project)
        settings (config/defaults)
        file (file-entity "src/a.clj" "source")
        symbol (symbol-entity file "sample/source" 1)]
    (store/with-store [graph project settings]
      (store/replace-all! graph [file symbol])
      (store/begin-full-replacement! graph)
      (is (= :unavailable (store/graph-state graph)))
      (is (= :graph/update-incomplete
             (try
               (store/assert-query-compatible! graph)
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error))))))
      (store/write-graph-metadata!
       graph {:analyzer-name "fixture"
              :analyzer-version "1"
              :janet-catalog-version "1.41.2"
              :semantic-document-version 2
              :semantic-index-name "llm-context-v2"})
      (is (= :ready (store/graph-state graph))))))

(deftest incremental-topic-cleanup-retracts-only-unreferenced-topics
  (let [project (temp-project)
        file (file-entity "src/a.cljs" "source")
        symbol (assoc (symbol-entity file "sample/a" 1)
                      :symbol/platform :cljs)
        topic {:entity/type :entity.type/topic :topic/id "topic:cleanup"
               :topic/kind :state-key :topic/key "[:saved]"
               :topic/platform :cljs}
        edge {:entity/type :entity.type/edge :edge/id "edge:topic-cleanup"
              :edge/kind :edge.kind/state-reads
              :edge/from (:symbol/id symbol) :edge/to (:topic/id topic)
              :edge/target-text "[:saved]"
              :edge/resolution :resolution/exact :edge/confidence 1.0
              :edge/evidence :test-topic}]
    (store/with-store [graph project (config/defaults)]
      (store/replace-all! graph [file symbol topic edge])
      (is (zero? (store/prune-orphan-topics! graph)))
      (store/replace-file! graph file [symbol])
      (is (empty? (store/query graph
                               '[:find [?id ...] :where [?edge :edge/id ?id]]
                               [])))
      (is (= 1 (store/prune-orphan-topics! graph)))
      (is (empty? (store/query graph
                               '[:find [?id ...] :where [?topic :topic/id ?id]]
                               []))))))

(deftest whole-graph-replacement-commits-bounded-batches
  (let [project (temp-project)
        file-a (file-entity "src/a.clj" "source-a")
        file-b (file-entity "src/b.clj" "source-b")
        symbols (mapv (fn [index]
                        (symbol-entity (if (even? index) file-a file-b)
                                       (str "sample/function-" index)
                                       (inc index)))
                      (range 205))
        events (atom [])]
    (store/with-store [graph project (config/defaults)]
      (store/replace-all! graph
                          (concat symbols [file-b file-a])
                          {:batch-size 100
                           :on-progress #(swap! events conj %)})
      (is (= [{:phase :assert :completed 100 :total 207}
              {:phase :assert :completed 200 :total 207}
              {:phase :assert :completed 207 :total 207}]
             @events))
      (is (= 207
             (count (store/query graph
                                 '[:find [?entity ...]
                                   :where [?entity :entity/type _]]
                                 [])))))))

(deftest target-file-replacement-preserves-inbound-evidence
  (let [project (temp-project)
        source-file (file-entity "src/source.clj" "source")
        target-file (file-entity "src/target.clj" "target")
        source (symbol-entity source-file "sample/source" 1)
        target (symbol-entity target-file "sample/target" 1)
        edge {:entity/type :entity.type/edge
              :edge/id "edge:inbound"
              :edge/kind :edge.kind/calls
              :edge/from (:symbol/id source)
              :edge/to (:symbol/id target)
              :edge/target-text "target"
              :edge/resolution :resolution/exact
              :edge/confidence 1.0
              :edge/evidence :test-exact}]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph source-file [source])
      (store/replace-file! graph target-file [target])
      (store/transact! graph [edge])
      (store/delete-file! graph (:file/id target-file))
      (is (empty? (store/query graph
                               '[:find [?id ...]
                                 :where [?edge :edge/id ?id]]
                               []))))))
