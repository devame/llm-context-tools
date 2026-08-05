(ns llm-context.semantic-benchmark
  "Evaluate a running project's hybrid retrieval against an EDN corpus."
  (:require [clojure.java.io :as io]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.evaluation :as evaluation]
            [llm-context.service.client :as client]
            [llm-context.version :as version])
  (:import [java.nio.file Files OpenOption]))

(def ^:private context-depth 4)
(def ^:private context-max-tokens 2000)

(defn- percentile [values fraction]
  (when (seq values)
    (let [sorted (vec (sort values))
          index (min (dec (count sorted))
                     (long (Math/ceil (* fraction (count sorted)))))]
      (nth sorted (max 0 (dec index))))))

(defn- elapsed-ms [started]
  (/ (- (System/nanoTime) started) 1000000.0))

(defn- qualified-seeds [selected packet-symbols]
  (let [symbols-by-id (into {} (map (juxt :id identity)) packet-symbols)]
    (mapv (fn [seed]
            ;; Focus-resolution records intentionally stay compact. Restore
            ;; canonical platform/file/kind qualifiers from the packet before
            ;; applying maintained format-2 selectors.
            (merge (get symbols-by-id (:id seed)) seed))
          selected)))

(defn- run-query
  [project {:keys [id query language query-type domain relevance]
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
                                     :depth context-depth
                                     :max-tokens context-max-tokens}})
          context-ms (elapsed-ms context-started)
          packet (when (:ok context-response) (:value context-response))
          selected (get-in packet [:focus-resolution :selected])
          packet-symbols (:symbols packet)
          qualified-selected (qualified-seeds selected packet-symbols)
          ranking (evaluation/ranked-metrics results judgment)]
      {:id id
       :query query
       :language language
       :query-type query-type
       :domain domain
       :search-ms search-ms
       :context-ms context-ms
       :search-hit? (:hit? ranking)
       :first-relevant-rank (:first-relevant-rank ranking)
       :reciprocal-rank (:reciprocal-rank ranking)
       :ndcg (:ndcg ranking)
       :hard-negative-before-relevant?
       (:hard-negative-before-relevant? ranking)
       :seed-hit?
       (boolean (some #(evaluation/relevant? % relevance)
                      qualified-selected))
       :packet-hit?
       (boolean (some #(evaluation/relevant? % relevance) packet-symbols))
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

(defn- latency-summary [times]
  {:mean (mean times)
   :p50 (percentile times 0.50)
   :p95 (percentile times 0.95)
   :max (apply max times)})

(defn- benchmark-config [settings]
  (let [lateon (get-in settings [:semantic :lateon-code])]
    {:scorer-version version/value
     :model (:model lateon)
     :model-revision (:model-revision lateon)
     :document-version (:document-version lateon)
     :candidate-count (:candidate-count lateon)
     :context-depth context-depth
     :context-max-tokens context-max-tokens}))

(defn- benchmark-result [project corpus settings]
  (let [queries (:queries corpus)
        results (mapv #(run-query project %) queries)
        search-times (mapv :search-ms results)
        context-times (mapv :context-ms results)
        query-count (count results)]
    {:benchmark/version 4
     :benchmark/config (benchmark-config settings)
     :corpus/version (:corpus/version corpus)
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
              :query-type (slices results :query-type)
              :domain (slices results :domain)}
     :lateon-query-rate
     (/ (count (filter :lateon? results)) (double query-count))
     :lateon-seed-rate
     (/ (count (filter :seed-lateon? results)) (double query-count))
     :search-latency-ms (latency-summary search-times)
     :context-latency-ms (latency-summary context-times)
     :search-misses
     (mapv #(select-keys % [:id :query :first-relevant-rank])
           (remove :search-hit? results))
     :hard-negative-errors
     (mapv #(select-keys % [:id :query :first-relevant-rank])
           (filter :hard-negative-before-relevant? results))
     :seed-misses (mapv #(select-keys % [:id :query])
                        (remove :seed-hit? results))
     :packet-misses (mapv #(select-keys % [:id :query])
                          (remove :packet-hit? results))
     :context-errors
     (mapv #(select-keys % [:id :query :context-error])
           (filter :context-error results))}))

(defn safe-summary
  "Remove query-level identifiers and text for privacy-safe console output."
  [result]
  (-> (select-keys
       result
       [:benchmark/version :benchmark/config :corpus/version :queries
        :search-recall-at-k :search-mrr :search-ndcg-at-k
        :hard-negative-before-relevant-rate :context-seed-recall-at-1
        :context-packet-recall :slices :lateon-query-rate :lateon-seed-rate
        :search-latency-ms :context-latency-ms])
      (assoc :search-miss-count (count (:search-misses result))
             :hard-negative-error-count (count (:hard-negative-errors result))
             :seed-miss-count (count (:seed-misses result))
             :packet-miss-count (count (:packet-misses result))
             :context-error-count (count (:context-errors result)))))

(defn write-result! [path result]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (Files/createDirectories (.toPath parent)
                               (make-array java.nio.file.attribute.FileAttribute
                                           0)))
    (Files/writeString (.toPath file) (str (pr-str result) "\n")
                       (make-array OpenOption 0))
    (.getPath file)))

(defn- parse-args [args]
  (let [[project-path query-path & options] args]
    (when-not (and project-path query-path)
      (throw
       (ex-info
        (str "Usage: clojure -M:semantic-bench PROJECT QUERY_SET.edn "
             "[--output RESULT.edn]")
        {:exit-code 2})))
    (loop [remaining options result {:project-path project-path
                                     :query-path query-path}]
      (if (empty? remaining)
        result
        (case (first remaining)
          "--output"
          (if-let [path (second remaining)]
            (recur (nnext remaining) (assoc result :output path))
            (throw (ex-info "--output requires a path" {:exit-code 2})))
          (throw (ex-info (str "Unknown semantic benchmark option: "
                               (first remaining))
                          {:exit-code 2})))))))

(defn -main [& args]
  (try
    (let [{:keys [project-path query-path output]} (parse-args args)
          project (project/context project-path)
          corpus (evaluation/read-corpus-data query-path)
          settings (config/load-config project)]
      (when-not (client/available? project)
        (throw
         (ex-info "Start the project service before semantic benchmarking"
                  {:exit-code 2})))
      (let [result (benchmark-result project corpus settings)]
        (if output
          (do
            (write-result! output result)
            (prn (safe-summary result)))
          (prn result))))
    (finally
      ;; Unix service requests use futures to enforce response timeouts.
      ;; Release their executor so this command exits after printing results.
      (shutdown-agents))))
