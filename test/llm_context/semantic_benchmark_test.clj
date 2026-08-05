(ns llm-context.semantic-benchmark-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [llm-context.semantic-benchmark :as benchmark]))

(def private-result
  {:benchmark/version 4
   :benchmark/config {:scorer-version "test" :candidate-count 50}
   :corpus/version 2
   :queries 1
   :search-recall-at-k 0.0
   :search-mrr 0.0
   :search-ndcg-at-k 0.0
   :hard-negative-before-relevant-rate 1.0
   :context-seed-recall-at-1 0.0
   :context-packet-recall 0.0
   :slices {:language {:clojurescript {:queries 1}}}
   :lateon-query-rate 1.0
   :lateon-seed-rate 0.0
   :search-latency-ms {:mean 1.0}
   :context-latency-ms {:mean 2.0}
   :search-misses [{:id :synthetic/private-query
                    :query "private natural-language question"}]
   :hard-negative-errors [{:id :synthetic/private-query}]
   :seed-misses [{:id :synthetic/private-query}]
   :packet-misses [{:id :synthetic/private-query}]
   :context-errors []})

(deftest safe-summary-redacts-query-level-details
  (let [summary (benchmark/safe-summary private-result)
        rendered (pr-str summary)]
    (is (= 1 (:search-miss-count summary)))
    (is (= 1 (:hard-negative-error-count summary)))
    (is (not (re-find #"private-query|natural-language" rendered)))
    (is (not-any? #(contains? summary %)
                  [:search-misses :hard-negative-errors :seed-misses
                   :packet-misses :context-errors]))))

(deftest result-file-retains-private-diagnostics
  (let [directory (.toFile
                   (java.nio.file.Files/createTempDirectory
                    "semantic-benchmark-test"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        output (io/file directory "nested" "result.edn")]
    (try
      (benchmark/write-result! output private-result)
      (testing "the private file retains the complete result"
        (is (= private-result (edn/read-string (slurp output)))))
      (finally
        (doseq [file (reverse (file-seq directory))]
          (io/delete-file file true))))))
