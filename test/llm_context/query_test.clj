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
                  :source/start-line line :source/start-column 1
                  :source/end-line (+ line 2) :source/end-column 1})
        caller (symbol "symbol:caller" "caller" 1)
        callee (symbol "symbol:callee" "callee" 5)
        edge {:entity/type :entity.type/edge :edge/id "edge:call"
              :edge/kind :edge.kind/calls :edge/from (:symbol/id caller)
              :edge/to (:symbol/id callee) :edge/target-text "callee"
              :edge/resolution :resolution/exact :edge/confidence 1.0
              :source/start-line 2 :source/start-column 2
              :source/end-line 2 :source/end-column 10}
        effect {:entity/type :entity.type/effect :effect/id "effect:log"
                :effect/kind :effect.kind/logging :effect/symbol (:symbol/id caller)
                :effect/detail "println" :effect/confidence 0.99
                :source/start-line 2 :source/start-column 2
                :source/end-line 2 :source/end-column 10}]
    {:project project :file file :entities [caller callee edge effect]}))

(deftest datalog-query-surface
  (let [{:keys [project file entities]} (fixture)]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file entities)
      (is (= 5 (:entities (query/stats graph))))
      (is (= 1 (:files (query/stats graph))))
      (is (= "sample/callee" (:qualified-name (first (query/symbols graph "callee")))))
      (is (= "sample/caller" (:name (first (query/callers graph "symbol:callee")))))
      (is (= "sample/callee" (:name (first (query/callees graph "symbol:caller")))))
      (is (= [{:id "symbol:callee" :name "sample/callee"}]
             (query/transitive-callees graph "symbol:caller")))
      (is (= :effect.kind/logging (:kind (first (query/effects graph)))))
      (is (= #{"symbol:caller"}
             (set (map :id (query/entry-points graph))))))))
