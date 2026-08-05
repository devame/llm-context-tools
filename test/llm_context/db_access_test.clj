(ns llm-context.db-access-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [llm-context.test-support.db :as db-support]))

(deftest operation-instrumentation-counts-public-datalevin-calls
  (let [calls (atom [])
        result
        (with-redefs [d/q (fn [& _] (swap! calls conj :real-query) :answer)]
          (db-support/with-operation-counts
            (d/q :query :db)))]
    (is (= :answer (:value result)))
    (is (= [:real-query] @calls))
    (is (= 1 (get-in result [:counts :query])))
    (is (zero? (get-in result [:counts :pull])))
    (is (zero? (get-in result [:counts :transact])))))

(deftest monitor-probe-distinguishes-lock-free-and-monitor-bound-work
  (let [monitor (Object.)]
    (is (db-support/completes-while-monitor-held?
         monitor (constantly :done) 1000))
    (is (not (db-support/completes-while-monitor-held?
              monitor #(locking monitor :blocked) 100)))))
