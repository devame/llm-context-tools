(ns llm-context.public-semantic-evaluation-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.public-semantic-evaluation :as suite]))

(deftest checked-in-manifest-has-the-three-public-repositories
  (let [manifest (suite/read-manifest "bench/public-semantic-evaluation/manifest.edn")]
    (is (= #{:clojure-lsp :re-frame :metabase}
           (set (map :id (:repositories manifest)))))
    (is (= 120
           (reduce + (map #(reduce + (vals (:expected-queries %)))
                          (:repositories manifest)))))
    (is (identical? manifest (suite/validate-manifest! manifest)))))

(deftest semantic-preflight-requires-loopback-and-complete-coverage
  (let [complete {:completeness :complete
                  :pending 0 :leased 0 :failed 0 :dirty 0
                  :runtime {:endpoint "http://127.0.0.1:12345"}}]
    (is (suite/synchronized-status? complete))
    (is (not (suite/synchronized-status?
              (assoc-in complete [:runtime :endpoint]
                        "http://192.0.2.10:12345"))))
    (is (not (suite/synchronized-status?
              (assoc complete :pending 1))))))

(deftest bootstrap-confidence-interval-is-seed-deterministic
  (is (= (suite/bootstrap-ci [0.0 0.5 1.0] 42)
         (suite/bootstrap-ci [0.0 0.5 1.0] 42)))
  (is (= {:low 1.0 :high 1.0}
         (suite/bootstrap-ci [1.0 1.0] 42))))

(deftest aggregation-is-public-metadata-only
  (let [rows [{:id :query/one :language :clojure :query-type :behavior
               :domain :auth :search-hit? true
               :search-ms 10 :context-ms 20
               :search-recall-at-10? true :search-recall-at-20? true
               :search-recall-at-50? true :reciprocal-rank 1.0 :ndcg 1.0
               :hard-negative-before-relevant? false
               :seed-hit? true :packet-hit? true}
              {:id :query/two :language :clojure :query-type :state
               :domain :state :search-hit? false
               :search-ms 30 :context-ms 40
               :search-recall-at-10? false :search-recall-at-20? true
               :search-recall-at-50? true :reciprocal-rank 0.0 :ndcg 0.5
               :hard-negative-before-relevant? true
               :seed-hit? false :packet-hit? false}]
        report (suite/aggregate-mode
                [{:repository :example :split :development :mode :hybrid
                  :result {:query-results rows}}]
                :hybrid)
        rendered (pr-str report)]
    (is (= 2 (:queries report)))
    (is (= 1 (:repositories report)))
    (is (= 2 (get-in report [:by-split :development :queries])))
    (is (= 0.5 (get-in report [:query-weighted :search-hit? :mean])))
    (is (= 20.0 (get-in report [:latency-ms :search :mean])))
    (is (not (re-find #"query/one|query/two" rendered)))))
