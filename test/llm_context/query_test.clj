(ns llm-context.query-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.model.ids :as ids]
            [llm-context.project :as project]
            [llm-context.query :as query]
            [llm-context.store :as store])
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
      (is (= [{:id "symbol:callee" :name "sample/callee"}]
             (query/transitive-callees graph "symbol:caller")))
      (is (= :effect.kind/logging (:kind (first (query/effects graph)))))
      (is (= #{"symbol:caller"}
             (set (map :id (query/entry-points graph))))))))
