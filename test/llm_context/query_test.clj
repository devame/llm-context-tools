(ns llm-context.query-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.model.ids :as ids]
            [llm-context.project :as project]
            [llm-context.query :as query]
            [llm-context.store :as store]
            [llm-context.test-support.db :as db-support])
  (:import [java.nio.file Files]))

(defn fixture []
  (let [root (Files/createTempDirectory "llm-context-query-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        file {:entity/type :entity.type/file :file/id "file:src/a.clj"
              :file/path "src/a.clj" :file/language :language/clojure
              :file/content-hash (ids/content-hash "fixture")
              :file/size 7 :file/modified-at 1}
        symbol (fn [id name line]
                 {:entity/type :entity.type/symbol :symbol/id id :symbol/name name
                  :symbol/qualified-name (str "sample/" name)
                  :symbol/kind :symbol.kind/function :symbol/file (:file/id file)
                  :symbol/platform :clj :symbol/analyzer :test
                  :symbol/scope :scope/top-level
                  :symbol/role :role/definition
                  :symbol/indexable? true
                  :source/start-line line :source/start-column 1
                  :source/end-line (+ line 2) :source/end-column 1})
        caller (assoc (symbol "symbol:caller" "caller" 1)
                      :symbol/signature "[connection]"
                      :symbol/doc "Initialize the persistent database connection")
        callee (symbol "symbol:callee" "callee" 5)
        edge {:entity/type :entity.type/edge :edge/id "edge:call"
              :edge/kind :edge.kind/calls :edge/from (:symbol/id caller)
              :edge/to (:symbol/id callee) :edge/target-text "callee"
              :edge/resolution :resolution/exact :edge/confidence 1.0
              :edge/evidence :test-exact
              :source/start-line 2 :source/start-column 2
              :source/end-line 2 :source/end-column 10}
        effect {:entity/type :entity.type/effect :effect/id "effect:log"
                :effect/kind :effect.kind/logging :effect/symbol (:symbol/id caller)
                :effect/detail "println" :effect/confidence 0.99
                :source/start-line 2 :source/start-column 2
                :source/end-line 2 :source/end-column 10}
        external {:entity/type :entity.type/reference
                  :reference/id "reference:external"
                  :reference/symbol (:symbol/id caller)
                  :reference/kind :edge.kind/calls
                  :reference/target-text "clojure.core/swap!"
                  :reference/qualified-target "clojure.core/swap!"
                  :reference/classification :external
                  :reference/evidence :test-reference
                  :source/start-line 2 :source/start-column 11}
        unresolved {:entity/type :entity.type/reference
                    :reference/id "reference:missing"
                    :reference/symbol (:symbol/id caller)
                    :reference/kind :edge.kind/calls
                    :reference/target-text "missing"
                    :reference/classification :unresolved
                    :reference/evidence :test-reference
                    :source/start-line 3 :source/start-column 1}
        topic {:entity/type :entity.type/topic :topic/id "topic:save"
               :topic/kind :event :topic/key ":save" :topic/platform :clj}
        topic-edge {:entity/type :entity.type/edge :edge/id "edge:dispatch"
                    :edge/kind :edge.kind/event-dispatches
                    :edge/from (:symbol/id caller) :edge/to (:topic/id topic)
                    :edge/target-text ":save"
                    :edge/resolution :resolution/exact :edge/confidence 1.0
                    :edge/evidence :test-topic
                    :source/start-line 3 :source/start-column 2}]
    {:project project :file file
     :entities [caller callee edge effect external unresolved topic topic-edge]}))

(deftest datalog-query-surface
  (let [{:keys [project file entities]} (fixture)]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file entities)
      (is (= 9 (:entities (query/stats graph))))
      (is (= 1 (:files (query/stats graph))))
      (is (= "sample/callee" (:qualified-name (first (query/symbols graph "callee")))))
      (is (= "sample/caller"
             (:qualified-name
              (first (query/symbols graph "persistent database")))))
      (is (= "sample/caller"
             (:qualified-name
              (first (query/symbols graph "initialize connection")))))
      (is (some #{"sample/caller"}
                (map :qualified-name
                     (:suggestions (query/find-symbol graph "callre")))))
      (is (= "sample/caller" (:name (first (query/callers graph "symbol:callee")))))
      (is (= "sample/callee" (:name (first (query/callees graph "symbol:caller")))))
      (is (= :external
             (:classification
              (second (query/callees graph "symbol:caller"
                                     {:include-external? true})))))
      (is (= "missing" (:target (first (query/unresolved graph)))))
      (is (empty? (query/unresolved graph {:classification :dynamic})))
      (is (= "sample/caller"
             (:symbol (first (query/topic-relationships graph ":save")))))
      (is (= [{:id "symbol:callee" :name "sample/callee" :depth 1}]
             (:results
              (query/transitive-callees graph "symbol:caller"))))
      (is (= :effect.kind/logging (:kind (first (query/effects graph)))))
      (is (= #{"symbol:caller"}
             (set (map :id (query/entry-points graph))))))))

(defn trace-fixture [unrelated-count]
  (let [root (Files/createTempDirectory "llm-context-trace-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        file {:entity/type :entity.type/file :file/id "file:src/trace.clj"
              :file/path "src/trace.clj" :file/language :language/clojure
              :file/content-hash (ids/content-hash "trace")
              :file/size 5 :file/modified-at 1}
        symbol (fn [id name line]
                 {:entity/type :entity.type/symbol :symbol/id id
                  :symbol/name name :symbol/qualified-name (str "trace/" name)
                  :symbol/kind :symbol.kind/function :symbol/file (:file/id file)
                  :symbol/platform :clj :symbol/analyzer :test
                  :symbol/scope :scope/top-level :symbol/role :role/definition
                  :symbol/indexable? true
                  :source/start-line line :source/start-column 1
                  :source/end-line line :source/end-column 10})
        symbols [(symbol "symbol:source" "source" 1)
                 (symbol "symbol:a" "a" 2)
                 (symbol "symbol:b" "b" 3)
                 (symbol "symbol:c" "c" 4)
                 (symbol "symbol:non-call" "non-call" 5)]
        unrelated (mapv #(symbol (str "symbol:unrelated-" %)
                                 (str "unrelated-" %) (+ 10 %))
                        (range unrelated-count))
        edge (fn [id kind from to]
               {:entity/type :entity.type/edge :edge/id id :edge/kind kind
                :edge/from from :edge/to to :edge/target-text to
                :edge/resolution :resolution/exact :edge/confidence 1.0
                :edge/evidence :test-exact})
        edges [(edge "edge:source-a" :edge.kind/calls
                     "symbol:source" "symbol:a")
               (edge "edge:source-b" :edge.kind/calls
                     "symbol:source" "symbol:b")
               (edge "edge:a-c" :edge.kind/calls "symbol:a" "symbol:c")
               (edge "edge:b-c" :edge.kind/calls "symbol:b" "symbol:c")
               (edge "edge:cycle" :edge.kind/calls "symbol:c" "symbol:source")
               (edge "edge:contains" :edge.kind/contains
                     "symbol:source" "symbol:non-call")]]
    {:project project :file file
     :entities (vec (concat symbols unrelated edges))}))

(deftest trace-is-call-only-cycle-safe-bounded-and-deterministic
  (let [{:keys [project file entities]} (trace-fixture 0)]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file entities)
      (let [complete (query/transitive-callees
                      graph "symbol:source" {:depth 4 :limit 20})]
        (is (= [["symbol:a" 1] ["symbol:b" 1] ["symbol:c" 2]]
               (mapv (juxt :id :depth) (:results complete))))
        (is (false? (:truncated? complete)))
        (is (not-any? #{"symbol:non-call"}
                      (map :id (:results complete)))))
      (let [depth-limited (query/transitive-callees
                           graph "symbol:source" {:depth 1 :limit 20})]
        (is (true? (:truncated? depth-limited)))
        (is (= {:depth? true :limit? false}
               (:truncation depth-limited))))
      (let [result-limited (query/transitive-callees
                            graph "symbol:source" {:depth 4 :limit 1})]
        (is (= ["symbol:a"] (mapv :id (:results result-limited))))
        (is (= {:depth? false :limit? true}
               (:truncation result-limited))))
      (is (db-support/completes-while-monitor-held?
           graph #(query/transitive-callees graph "symbol:source") 2000)))))

(defn trace-operation-counts [unrelated-count]
  (let [{:keys [project file entities]} (trace-fixture unrelated-count)]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file entities)
      (:counts
       (db-support/with-operation-counts
         (query/transitive-callees graph "symbol:source"))))))

(deftest trace-database-cardinality-is-independent-of-disconnected-symbols
  (let [small (trace-operation-counts 0)
        large (trace-operation-counts 500)]
    (is (= small large))
    (is (<= (:query large) 4))
    (is (= 1 (:pull large)))
    (is (zero? (:entity large)))))

(deftest trace-command-validates-options-and-uses-configured-defaults
  (let [{:keys [project file entities]} (trace-fixture 0)
        settings (assoc (config/defaults) :context
                        {:default-max-tokens 8000
                         :trace-depth 1 :trace-limit 1})]
    (store/with-store [graph project settings]
      (store/replace-file! graph file entities)
      (is (= 1 (:depth (query/trace-command
                        graph settings ["symbol:source"]))))
      (is (= 1 (:limit (query/trace-command
                        graph settings ["symbol:source"]))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown query trace"
                            (query/trace-command
                             graph settings ["symbol:source" "--wat" "1"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                            (query/trace-command
                             graph settings ["symbol:source" "--depth" "0"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown trace source"
                            (query/transitive-callees graph "symbol:missing"))))))
