(ns llm-context.analysis.resolve-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.resolve :as resolve]))

(deftest unique-and-ambiguous-structural-resolution
  (let [symbols [{:symbol-id "symbol:one" :name "unique"
                  :qualified-name "file:b/unique"}
                 {:symbol-id "symbol:dup-a" :name "duplicate"
                  :qualified-name "file:b/duplicate"}
                 {:symbol-id "symbol:dup-b" :name "duplicate"
                  :qualified-name "file:c/duplicate"}]
        edges [{:edge-id "edge:one" :kind :edge.kind/calls
                :target-text "unique" :resolution :resolution/unresolved}
               {:edge-id "edge:dup" :kind :edge.kind/calls
                :target-text "duplicate" :resolution :resolution/unresolved}]
        decisions (into {} (map (juxt :edge-id identity))
                        (resolve/resolution-decisions symbols edges {}))]
    (is (= :resolution/heuristic
           (get-in decisions ["edge:one" :resolution])))
    (is (= "symbol:one" (get-in decisions ["edge:one" :target-id])))
    (is (= :resolution/ambiguous
           (get-in decisions ["edge:dup" :resolution])))
    (is (nil? (get-in decisions ["edge:dup" :target-id])))))

(deftest incremental-decisions-retract-stale-unique-targets
  (let [symbols [{:symbol-id "symbol:a" :name "target" :qualified-name "a/target"}
                 {:symbol-id "symbol:b" :name "target" :qualified-name "b/target"}]
        edge {:edge-id "edge:call" :kind :edge.kind/calls
              :target-text "target" :current-target "symbol:a"
              :resolution :resolution/heuristic}
        decision (first (resolve/resolution-decisions symbols [edge] {}))]
    (is (= :resolution/ambiguous (:resolution decision)))
    (is (nil? (:target-id decision)))))
