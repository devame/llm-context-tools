(ns llm-context.semantic.evaluation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm-context.semantic.evaluation :as evaluation]))

(defn- result [qualified-name]
  {:id (str "symbol:" qualified-name)
   :name (last (str/split qualified-name #"/"))
   :qualified-name qualified-name})

(deftest checked-in-retrieval-corpus-is-valid-and-balanced
  (let [queries (evaluation/read-corpus
                 "bench/retrieval-corpus/queries.edn")]
    (is (= 24 (count queries)))
    (is (= {:clojure 12 :janet 10 :cross-language 2}
           (frequencies (map :language queries))))
    (is (= (count queries) (count (distinct (map :id queries)))))
    (is (every? seq (map :hard-negatives queries)))
    (is (every? #(some #{3} (vals (:relevance %))) queries))))

(deftest legacy-query-vectors-remain-compatible
  (is (= [{:query "authenticate a user"
           :expected ["auth/authenticate"]
           :relevance {"auth/authenticate" 1}}]
         (evaluation/validate-corpus!
          [{:query "authenticate a user"
            :expected ["auth/authenticate"]}]))))

(deftest corpus-validation-rejects-ambiguous-judgments
  (let [invalid {:corpus/version 1
                 :queries
                 [{:id :duplicate
                   :language :clojure
                   :query-type :behavior
                   :query "find it"
                   :relevance {"sample/find" 3}
                   :hard-negatives ["sample/find"]}]}
        error (try
                (evaluation/validate-corpus! invalid)
                nil
                (catch clojure.lang.ExceptionInfo thrown thrown))]
    (is error)
    (is (some #(re-find #"same identity" %) (:errors (ex-data error))))))

(deftest ranked-metrics-reward-order-and-expose-hard-negatives
  (let [judgment {:relevance {"sample/primary" 3 "sample/helper" 1}
                  :hard-negatives ["sample/lookalike"]}]
    (testing "ideal ranking"
      (is (= {:hit? true
              :first-relevant-rank 1
              :reciprocal-rank 1.0
              :ndcg 1.0
              :hard-negative-before-relevant? false}
             (evaluation/ranked-metrics
              [(result "sample/primary") (result "sample/helper")]
              judgment))))
    (testing "a lookalike ahead of the answer is visible"
      (let [metrics (evaluation/ranked-metrics
                     [(result "sample/lookalike")
                      (result "sample/primary")]
                     judgment)]
        (is (= 2 (:first-relevant-rank metrics)))
        (is (= 0.5 (:reciprocal-rank metrics)))
        (is (< (:ndcg metrics) 1.0))
        (is (:hard-negative-before-relevant? metrics))))))
