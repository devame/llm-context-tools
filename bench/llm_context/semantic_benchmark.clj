(ns llm-context.semantic-benchmark
  "Evaluate a running project's hybrid retrieval against an EDN query set."
  (:require [llm-context.project :as project]
            [llm-context.semantic.evaluation :as evaluation]
            [llm-context.service.client :as client]))

(defn- percentile [values fraction]
  (when (seq values)
    (let [sorted (vec (sort values))
          index (min (dec (count sorted))
                     (long (Math/ceil (* fraction (count sorted)))))]
      (nth sorted (max 0 (dec index))))))

(defn- expected? [result relevance]
  (pos? (evaluation/grade result relevance)))

(defn- elapsed-ms [started]
  (/ (- (System/nanoTime) started) 1000000.0))

(defn- run-query
  [project {:keys [id query language query-type relevance]
            :as judgment}]
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
          selected (get-in packet [:focus-resolution :selected])
          packet-symbols (:symbols packet)
          ranking (evaluation/ranked-metrics results judgment)]
      {:id id
       :query query
       :language language
       :query-type query-type
       :search-ms search-ms
       :context-ms context-ms
       :search-hit? (:hit? ranking)
       :first-relevant-rank (:first-relevant-rank ranking)
       :reciprocal-rank (:reciprocal-rank ranking)
       :ndcg (:ndcg ranking)
       :hard-negative-before-relevant?
       (:hard-negative-before-relevant? ranking)
       :seed-hit? (boolean (some #(expected? % relevance) selected))
       :packet-hit? (boolean (some #(expected? % relevance) packet-symbols))
       :lateon? (boolean
                 (some #(contains? (:matched-by %) :lateon) results))
       :seed-lateon?
       (boolean
        (some #(contains? (:matched-by %) :lateon) selected))
       :result-count (count results)
       :context-error (when-not (:ok context-response)
                        (:error context-response))})))

(defn- mean [values]
  (if (seq values)
    (/ (reduce + 0.0 values) (count values))
    0.0))

(defn- quality-summary [results]
  {:queries (count results)
   :search-recall-at-k (mean (map #(if (:search-hit? %) 1.0 0.0) results))
   :search-mrr (mean (map :reciprocal-rank results))
   :search-ndcg-at-k (mean (map :ndcg results))
   :hard-negative-before-relevant-rate
   (mean (map #(if (:hard-negative-before-relevant? %) 1.0 0.0) results))
   :context-seed-recall-at-1
   (mean (map #(if (:seed-hit? %) 1.0 0.0) results))
   :context-packet-recall
   (mean (map #(if (:packet-hit? %) 1.0 0.0) results))})

(defn- slices [results dimension]
  (->> results
       (filter dimension)
       (group-by dimension)
       (map (fn [[value members]] [value (quality-summary members)]))
       (into (sorted-map))))

(defn -main [& [project-path query-path]]
  (try
    (when-not (and project-path query-path)
      (throw
       (ex-info
        "Usage: clojure -M:semantic-bench PROJECT QUERY_SET.edn"
        {:exit-code 2})))
    (let [project (project/context project-path)
          queries (evaluation/read-corpus query-path)]
      (when-not (client/available? project)
        (throw
         (ex-info "Start the project service before semantic benchmarking"
                  {:exit-code 2})))
      (let [results (mapv #(run-query project %) queries)
            search-times (mapv :search-ms results)
            context-times (mapv :context-ms results)
            query-count (count results)]
        (prn
         {:benchmark/version 3
          :corpus/version evaluation/corpus-version
          :queries query-count
          :search-recall-at-k
          (/ (count (filter :search-hit? results)) (double query-count))
          :context-seed-recall-at-1
          (/ (count (filter :seed-hit? results)) (double query-count))
          :context-packet-recall
          (/ (count (filter :packet-hit? results)) (double query-count))
          :search-mrr (mean (map :reciprocal-rank results))
          :search-ndcg-at-k (mean (map :ndcg results))
          :hard-negative-before-relevant-rate
          (mean (map #(if (:hard-negative-before-relevant? %) 1.0 0.0)
                     results))
          :slices {:language (slices results :language)
                   :query-type (slices results :query-type)}
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
          :search-misses
          (mapv #(select-keys % [:id :query :first-relevant-rank])
                (remove :search-hit? results))
          :hard-negative-errors
          (mapv #(select-keys % [:id :query :first-relevant-rank])
                (filter :hard-negative-before-relevant? results))
          :seed-misses (mapv :query (remove :seed-hit? results))
          :packet-misses (mapv :query (remove :packet-hit? results))
          :context-errors
          (mapv #(select-keys % [:query :context-error])
                (filter :context-error results))})))
    (finally
      ;; Unix service requests use futures to enforce response timeouts.
      ;; Release their executor so this command exits after printing results.
      (shutdown-agents))))
