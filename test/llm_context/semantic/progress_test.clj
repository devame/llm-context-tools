(ns llm-context.semantic.progress-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.semantic.progress :as progress]))

(def result
  {:leased 4 :leased-symbol-jobs 4 :completed 4
   :prepared-symbol-jobs 4 :prepared-provider-documents 5
   :prepared-text-bytes 1000 :request-count 2
   :request-provider-documents 5 :request-text-bytes 1000
   :accepted-symbol-jobs 4 :visible-symbol-jobs 4 :reused-symbol-jobs 0
   :prepare-ms 100 :submit-ms 500 :visibility-ms 300 :completion-ms 100
   :request-concurrency-effective 2})

(deftest progress-distinguishes-symbols-provider-documents-and-requests
  (let [snapshot (progress/record (progress/initial 0) result 1000)]
    (is (= 4 (:leased-symbol-jobs snapshot)))
    (is (= 5 (:prepared-provider-documents snapshot)))
    (is (= 2 (:request-count snapshot)))
    (is (= 5 (:request-provider-documents snapshot)))
    (is (= 1000 (:request-text-bytes snapshot)))
    (is (= 2 (:request-concurrency-effective snapshot)))
    (is (= 4.0 (:recent-completed-symbols-per-second snapshot)))
    (is (= 5.0 (:recent-provider-documents-per-second snapshot)))
    (is (= 50.0 (get-in snapshot [:recent-stage-percentages :submit])))))

(deftest recent-window-is-constant-space-and-resets-after-idle
  (let [active
        (reduce (fn [snapshot time]
                  (progress/record snapshot result time))
                (progress/initial 0 {:window-ms 1000000 :idle-ms 1000})
                (range 1 1001))
        idle (progress/record active {:leased 0} 2500)]
    (is (= 64 (count (:recent-samples active))))
    (is (zero? (:recent-completed-symbols-per-second idle)))
    (is (= 4000 (:completed idle)))))
