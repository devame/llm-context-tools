(ns llm-context.model.canonical-hash-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.model.canonical-hash :as canonical-hash]))

(deftest canonical-hash-is-order-invariant-for-unordered-values
  (let [left {:a 1 :b {:x #{3 2 1} :y "value"}}
        right (array-map :b (array-map :y "value" :x #{1 3 2}) :a 1)]
    (is (= (canonical-hash/hash-values [left])
           (canonical-hash/hash-values [right])))))

(deftest canonical-hash-is-type-tagged-and-sensitive
  (testing "different scalar types and semantic values cannot alias"
    (is (not= (canonical-hash/hash-values [1])
              (canonical-hash/hash-values ["1"])))
    (is (not= (canonical-hash/hash-values [{:value 1}])
              (canonical-hash/hash-values [{:value 2}])))))

(deftest canonical-ordering-uses-the-selected-identity
  (is (= ["a" "b"]
         (mapv :id
               (canonical-hash/order-by :id [{:id "b"} {:id "a"}])))))
