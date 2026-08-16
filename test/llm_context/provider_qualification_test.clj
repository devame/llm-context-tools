(ns llm-context.provider-qualification-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.provider-qualification :as qualification]))

(deftest pinned-datalevin-provider-contract-is-qualified
  (let [result (qualification/qualify-datalevin)]
    (is (= :supported (:status result)) result)
    (is (= "1.0.0" (:version result)))
    (is (= {:transaction-report true
            :transact-async true
            :compact-copy true
            :bulk-init true
            :crash-boundary true}
           (:capabilities result)))
    (is (= :not-adopted
           (get-in result [:production-decisions :bulk-graph-build :status])))))
