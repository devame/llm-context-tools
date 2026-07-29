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

(defn- elapsed-ms [started]
  (/ (- (System/nanoTime) started) 1000000.0))

(defn- run-query [project {:keys [query expected]}]
  (let [search-started (System/nanoTime)
        search-response
        (client/request project
                        {:op :query :subcommand "search"
                         :args [query]})
        search-ms (elapsed-ms search-started)]
    (when-not (:ok search-response)
      (throw (ex-info (or (:error search-response)
                          "Project service is not reachable")
                      {:query query :response search-response})))
    (let [search-value (:value search-response)
          results (if (map? search-value)
                    (:results search-value)
                    search-value)
          context-started (System/nanoTime)
          context-response
          (client/request project
                          {:op :context
                           :options {:focus query
                                     :intent? true
                                     :format :edn
                                     :depth 4
                                     :max-tokens 2000}})
          context-ms (elapsed-ms context-started)
          packet (when (:ok context-response) (:value context-response))
          expected (set expected)
          selected (get-in packet [:focus-resolution :selected])
          packet-symbols (:symbols packet)]
      {:query query
       :search-ms search-ms
       :context-ms context-ms
       :search-hit? (boolean (some #(expected? % expected) results))
       :seed-hit? (boolean (some #(expected? % expected) selected))
       :packet-hit? (boolean (some #(expected? % expected) packet-symbols))
       :lateon? (boolean
                 (some #(contains? (:matched-by %) :lateon) results))
       :seed-lateon?
       (boolean
        (some #(contains? (:matched-by %) :lateon) selected))
       :result-count (count results)
       :context-error (when-not (:ok context-response)
                        (:error context-response))})))

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
          search-times (mapv :search-ms results)
          context-times (mapv :context-ms results)
          query-count (count results)]
      (prn
       {:benchmark/version 2
        :queries query-count
        :search-recall-at-k
        (/ (count (filter :search-hit? results)) (double query-count))
        :context-seed-recall-at-1
        (/ (count (filter :seed-hit? results)) (double query-count))
        :context-packet-recall
        (/ (count (filter :packet-hit? results)) (double query-count))
        :lateon-query-rate
        (/ (count (filter :lateon? results)) (double query-count))
        :lateon-seed-rate
        (/ (count (filter :seed-lateon? results)) (double query-count))
        :search-latency-ms
        {:mean (/ (reduce + search-times) query-count)
         :p50 (percentile search-times 0.50)
         :p95 (percentile search-times 0.95)
         :max (apply max search-times)}
        :context-latency-ms
        {:mean (/ (reduce + context-times) query-count)
         :p50 (percentile context-times 0.50)
         :p95 (percentile context-times 0.95)
         :max (apply max context-times)}
        :search-misses (mapv :query (remove :search-hit? results))
        :seed-misses (mapv :query (remove :seed-hit? results))
        :packet-misses (mapv :query (remove :packet-hit? results))
        :context-errors
        (mapv #(select-keys % [:query :context-error])
              (filter :context-error results))}))))
