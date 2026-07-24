(ns llm-context.semantic-benchmark
  "Evaluate a running project's hybrid retrieval against an EDN query set."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [llm-context.project :as project]
            [llm-context.service.client :as client]))

(defn- percentile [values fraction]
  (when (seq values)
    (let [sorted (vec (sort values))
          index (min (dec (count sorted))
                     (long (Math/ceil (* fraction (count sorted)))))]
      (nth sorted (max 0 (dec index))))))

(defn- expected? [result expected]
  (let [identities (set ((juxt :id :name :qualified-name) result))]
    (boolean (some identities expected))))

(defn- run-query [project {:keys [query expected]}]
  (let [started (System/nanoTime)
        response (client/request project
                                 {:op :query :subcommand "search"
                                  :args [query]})
        milliseconds (/ (- (System/nanoTime) started) 1000000.0)]
    (when-not (:ok response)
      (throw (ex-info (or (:error response)
                          "Project service is not reachable")
                      {:query query :response response})))
    (let [results (:value response)]
      {:query query
       :milliseconds milliseconds
       :hit? (boolean (some #(expected? % (set expected)) results))
       :lateon? (boolean
                 (some #(contains? (:matched-by %) :lateon) results))
       :result-count (count results)})))

(defn -main [& [project-path query-path]]
  (when-not (and project-path query-path)
    (throw
     (ex-info
      "Usage: clojure -M:semantic-bench PROJECT QUERY_SET.edn"
      {:exit-code 2})))
  (let [project (project/context project-path)
        queries (with-open [reader (java.io.PushbackReader.
                                    (io/reader query-path))]
                  (edn/read {:eof []} reader))]
    (when-not (client/available? project)
      (throw
       (ex-info "Start the project service before semantic benchmarking"
                {:exit-code 2})))
    (when-not (and (vector? queries)
                   (seq queries)
                   (every? #(and (string? (:query %))
                                 (sequential? (:expected %))
                                 (seq (:expected %)))
                           queries))
      (throw
       (ex-info
        "Query set must be a non-empty vector of {:query string :expected [...]}"
        {:exit-code 2})))
    (let [results (mapv #(run-query project %) queries)
          times (mapv :milliseconds results)
          count (count results)]
      (prn
       {:benchmark/version 1
        :queries count
        :recall-at-k (/ (count (filter :hit? results)) (double count))
        :lateon-query-rate
        (/ (count (filter :lateon? results)) (double count))
        :latency-ms
        {:mean (/ (reduce + times) count)
         :p50 (percentile times 0.50)
         :p95 (percentile times 0.95)
         :max (apply max times)}
        :misses (mapv :query (remove :hit? results))}))))
