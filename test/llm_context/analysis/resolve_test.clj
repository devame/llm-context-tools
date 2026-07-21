(ns llm-context.analysis.resolve-test
  (:refer-clojure :exclude [symbol])
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.resolve :as resolve]))

(defn symbol [id name file line]
  {:entity/type :entity.type/symbol :symbol/id id :symbol/name name
   :symbol/qualified-name (str file "/" name)
   :symbol/kind :symbol.kind/function :symbol/file file
   :source/start-line line :source/start-column 1
   :source/end-line (+ line 2) :source/end-column 1})

(defn call [id from target line]
  {:entity/type :entity.type/edge :edge/id id :edge/kind :edge.kind/calls
   :edge/from from :edge/target-text target
   :edge/resolution :resolution/unresolved :edge/confidence 0.0
   :source/start-line line :source/start-column 2
   :source/end-line line :source/end-column 12})

(deftest unique-and-ambiguous-structural-resolution
  (let [caller (symbol "symbol:caller" "caller" "file:a" 1)
        one (symbol "symbol:one" "unique" "file:b" 1)
        duplicate-a (symbol "symbol:dup-a" "duplicate" "file:b" 5)
        duplicate-b (symbol "symbol:dup-b" "duplicate" "file:c" 5)
        output {:file {:file/path "a.js"}
                :entities [caller one duplicate-a duplicate-b
                           (call "edge:one" "symbol:caller" "unique" 2)
                           (call "edge:dup" "symbol:caller" "duplicate" 3)]}
        resolved (:entities (first (resolve/resolve-outputs [output] nil)))
        edges (into {} (map (juxt :edge/id identity) (filter :edge/id resolved)))]
    (is (= :resolution/heuristic (get-in edges ["edge:one" :edge/resolution])))
    (is (= "symbol:one" (get-in edges ["edge:one" :edge/to])))
    (is (= :resolution/ambiguous (get-in edges ["edge:dup" :edge/resolution])))
    (is (nil? (get-in edges ["edge:dup" :edge/to])))))
