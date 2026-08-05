(ns llm-context.semantic.evaluation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm-context.semantic.evaluation :as evaluation]))

(defn- result
  ([qualified-name]
   (result qualified-name :clj))
  ([qualified-name platform]
   {:id (str "symbol:" (name platform) ":" qualified-name)
    :name (last (str/split qualified-name #"/"))
    :qualified-name qualified-name
    :platform platform
    :file "src/sample/core.cljc"
    :kind :symbol.kind/function}))

(deftest checked-in-retrieval-corpus-is-valid-and-balanced
  (let [corpus (evaluation/read-corpus-data
                "bench/retrieval-corpus/queries.edn")
        queries (:queries corpus)]
    (is (= 1 (:corpus/version corpus)))
    (is (= 24 (count queries)))
    (is (= {:clojure 12 :janet 10 :cross-language 2}
           (frequencies (map :language queries))))
    (is (= (count queries) (count (distinct (map :id queries)))))
    (is (every? seq (map :hard-negatives queries)))
    (is (every? #(some (comp #{3} :grade) (:relevance %)) queries))))

(deftest legacy-query-vectors-remain-compatible
  (is (= [{:query "authenticate a user"
           :expected ["auth/authenticate"]
           :evaluation/corpus-version 0
           :relevance [{:identity "auth/authenticate" :grade 1}]
           :hard-negatives []}]
         (evaluation/validate-corpus!
          [{:query "authenticate a user"
            :expected ["auth/authenticate"]}]))))

(deftest corpus-validation-rejects-conflicting-judgments
  (let [invalid {:corpus/version 2
                 :queries
                 [{:id :duplicate
                   :language :clojure
                   :query-type :behavior
                   :query "find it"
                   :relevance [{:qualified-name "sample/find" :grade 3}]
                   :hard-negatives [{:qualified-name "sample/find"}]}]}
        error (try
                (evaluation/validate-corpus! invalid)
                nil
                (catch clojure.lang.ExceptionInfo thrown thrown))]
    (is error)
    (is (some #(re-find #"same selector" %) (:errors (ex-data error))))))

(deftest format-two-requires-maintainable-selectors
  (let [invalid {:corpus/version 2
                 :queries
                 [{:id :name-only
                   :language :clojurescript
                   :query-type :behavior
                   :query "find the implementation"
                   :relevance [{:name "find" :grade 3}]
                   :hard-negatives []}]}
        error (try
                (evaluation/validate-corpus! invalid)
                nil
                (catch clojure.lang.ExceptionInfo thrown thrown))]
    (is error)
    (is (some #(re-find #":id or :qualified-name" %)
              (:errors (ex-data error))))))

(deftest ranked-metrics-reward-order-and-expose-hard-negatives
  (let [judgment
        {:relevance [{:qualified-name "sample/primary" :grade 3}
                     {:qualified-name "sample/helper" :grade 1}]
         :hard-negatives [{:qualified-name "sample/lookalike"}]}]
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

(deftest one-judgment-contributes-gain-only-once
  (let [judgment
        {:relevance [{:qualified-name "sample/shared" :grade 3}]
         :hard-negatives []}
        metrics (evaluation/ranked-metrics
                 [(result "sample/shared" :clj)
                  (result "sample/shared" :cljs)]
                 judgment)]
    (is (= 1.0 (:ndcg metrics)))
    (is (= 1.0 (:reciprocal-rank metrics)))))

(deftest selectors-distinguish-cljc-platforms
  (let [judgment
        {:relevance [{:qualified-name "sample/shared"
                      :platform :cljs :grade 3}]
         :hard-negatives [{:qualified-name "sample/shared"
                           :platform :clj}]}
        metrics (evaluation/ranked-metrics
                 [(result "sample/shared" :clj)
                  (result "sample/shared" :cljs)]
                 judgment)]
    (is (= 2 (:first-relevant-rank metrics)))
    (is (= 0.5 (:reciprocal-rank metrics)))
    (is (:hard-negative-before-relevant? metrics))))
