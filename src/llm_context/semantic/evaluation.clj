(ns llm-context.semantic.evaluation
  "Validation and ranked-retrieval metrics for semantic evaluation corpora."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def corpus-version 1)

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- legacy-query? [query]
  (and (nil? (:relevance query)) (contains? query :expected)))

(defn normalize-query
  "Normalize the legacy `:expected` query shape into relevance grade 1.
  Maintained corpora should use canonical qualified names in `:relevance`."
  [query]
  (if (legacy-query? query)
    (assoc query :relevance (zipmap (:expected query) (repeat 1)))
    query))

(defn- query-errors [index query]
  (let [legacy? (legacy-query? query)
        normalized (normalize-query query)
        relevance (:relevance normalized)
        hard-negatives (or (:hard-negatives normalized) [])
        label (or (:id normalized) index)]
    (cond-> []
      (not (map? query))
      (conj (str "query " label " must be a map"))

      (not (non-blank-string? (:query normalized)))
      (conj (str "query " label " must have a non-blank :query"))

      (and (not legacy?) (not (keyword? (:id normalized))))
      (conj (str "query " label " must have a keyword :id"))

      (and (not legacy?) (not (keyword? (:language normalized))))
      (conj (str "query " label " must have a keyword :language"))

      (and (not legacy?) (not (keyword? (:query-type normalized))))
      (conj (str "query " label " must have a keyword :query-type"))

      (not (and (map? relevance)
                (seq relevance)
                (every? non-blank-string? (keys relevance))
                (every? pos-int? (vals relevance))))
      (conj (str "query " label
                 " must have non-empty string-to-positive-integer :relevance"))

      (not (and (vector? hard-negatives)
                (every? non-blank-string? hard-negatives)))
      (conj (str "query " label " :hard-negatives must be a vector of strings"))

      (seq (set/intersection (set (keys relevance)) (set hard-negatives)))
      (conj (str "query " label
                 " cannot mark the same identity relevant and a hard negative")))))

(defn validate-corpus!
  "Validate and normalize a corpus. Legacy vectors remain accepted. The
  maintained format is `{:corpus/version 1 :queries [...]}`."
  [corpus]
  (let [legacy? (vector? corpus)
        queries (if legacy? corpus (:queries corpus))
        errors
        (cond-> []
          (and (not legacy?) (not= corpus-version (:corpus/version corpus)))
          (conj (str ":corpus/version must be " corpus-version))

          (not (and (vector? queries) (seq queries)))
          (conj ":queries must be a non-empty vector")

          (and (vector? queries)
               (not= (count (keep :id queries))
                     (count (distinct (keep :id queries)))))
          (conj "query :id values must be unique"))
        errors (into errors
                     (mapcat (fn [[index query]] (query-errors index query))
                             (map-indexed vector (or queries []))))]
    (when (seq errors)
      (throw (ex-info (str "Invalid semantic evaluation corpus: "
                           (str/join "; " errors))
                      {:exit-code 2 :errors errors})))
    (mapv normalize-query queries)))

(defn read-corpus [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (validate-corpus! (edn/read {:eof nil} reader))))

(defn identities [result]
  (set (remove nil? ((juxt :id :name :qualified-name) result))))

(defn grade
  "Return the highest relevance grade assigned to any identity of a result."
  [result relevance]
  (reduce max 0 (keep relevance (identities result))))

(defn hard-negative? [result hard-negatives]
  (boolean (some (set hard-negatives) (identities result))))

(defn- dcg [grades]
  (reduce + 0.0
          (map-indexed
           (fn [index relevance]
             (/ (- (Math/pow 2.0 relevance) 1.0)
                (/ (Math/log (+ index 2.0)) (Math/log 2.0))))
           grades)))

(defn ranked-metrics
  "Evaluate an ordered result collection against one normalized query."
  [results {:keys [relevance hard-negatives]}]
  (let [grades (mapv #(grade % relevance) results)
        first-rank (some (fn [[index value]]
                           (when (pos? value) (inc index)))
                         (map-indexed vector grades))
        ideal (->> (vals relevance) (sort >) (take (count results)))
        ideal-dcg (dcg ideal)
        first-hard-negative-rank
        (some (fn [[index result]]
                (when (hard-negative? result hard-negatives) (inc index)))
              (map-indexed vector results))]
    {:hit? (boolean first-rank)
     :first-relevant-rank first-rank
     :reciprocal-rank (if first-rank (/ 1.0 first-rank) 0.0)
     :ndcg (if (pos? ideal-dcg) (/ (dcg grades) ideal-dcg) 0.0)
     :hard-negative-before-relevant?
     (boolean (and first-hard-negative-rank
                   (or (nil? first-rank)
                       (< first-hard-negative-rank first-rank))))}))
