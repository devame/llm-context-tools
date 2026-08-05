(ns llm-context.semantic.evaluation
  "Validation and ranked-retrieval metrics for semantic evaluation corpora."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def corpus-version 2)
(def supported-corpus-versions #{1 2})

(def ^:private selector-fields
  [:id :qualified-name :name :platform :file :kind])

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- legacy-query? [query]
  (and (map? query) (nil? (:relevance query))
       (contains? query :expected)))

(defn- v1-relevance [query]
  (if (legacy-query? query)
    (zipmap (:expected query) (repeat 1))
    (:relevance query)))

(defn- normalize-selector [selector]
  (cond
    (string? selector) {:identity selector}
    (map? selector) selector
    :else selector))

(defn- normalize-relevance [version query]
  (if (= 2 version)
    (mapv normalize-selector (:relevance query))
    (mapv (fn [[identity grade]] {:identity identity :grade grade})
          (v1-relevance query))))

(defn- normalize-hard-negatives [version query]
  (mapv normalize-selector (or (:hard-negatives query) [])))

(defn normalize-query
  "Normalize one query into structured relevance and hard-negative selectors.
  The one-argument arity retains legacy/vector compatibility."
  ([query]
   (normalize-query (if (legacy-query? query) 0 1) query))
  ([version query]
   (assoc query
          :evaluation/corpus-version version
          :relevance (normalize-relevance version query)
          :hard-negatives (normalize-hard-negatives version query))))

(defn- selector-identity? [selector]
  (or (non-blank-string? (:id selector))
      (non-blank-string? (:qualified-name selector))))

(defn- v2-selector-errors [label selector relevance?]
  (cond-> []
    (not (map? selector))
    (conj (str label " must be a selector map"))

    (and (map? selector) (not (selector-identity? selector)))
    (conj (str label " must have a non-blank :id or :qualified-name"))

    (and (map? selector)
         (some #(and (contains? selector %)
                     (nil? (get selector %)))
               selector-fields))
    (conj (str label " selector fields cannot be nil"))

    (and relevance? (not (pos-int? (:grade selector))))
    (conj (str label " must have a positive integer :grade"))

    (and (not relevance?) (contains? selector :grade))
    (conj (str label " hard negative must not have :grade"))))

(defn- v1-query-errors [label query]
  (let [relevance (v1-relevance query)
        hard-negatives (or (:hard-negatives query) [])]
    (cond-> []
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

(defn- v2-query-errors [label query]
  (let [relevance (:relevance query)
        hard-negatives (or (:hard-negatives query) [])
        relevance-errors
        (when (vector? relevance)
          (mapcat (fn [[index selector]]
                    (v2-selector-errors
                     (str "query " label " relevance " index)
                     selector true))
                  (map-indexed vector relevance)))
        hard-negative-errors
        (when (vector? hard-negatives)
          (mapcat (fn [[index selector]]
                    (v2-selector-errors
                     (str "query " label " hard negative " index)
                     selector false))
                  (map-indexed vector hard-negatives)))
        relevant-selectors
        (if (vector? relevance)
          (set (keep #(when (map? %) (dissoc % :grade)) relevance))
          #{})
        negative-selectors
        (if (vector? hard-negatives)
          (set (filter map? hard-negatives))
          #{})]
    (cond-> (vec (concat relevance-errors hard-negative-errors))
      (not (and (vector? relevance) (seq relevance)))
      (conj (str "query " label " :relevance must be a non-empty vector"))

      (not (vector? hard-negatives))
      (conj (str "query " label " :hard-negatives must be a vector"))

      (seq (set/intersection relevant-selectors negative-selectors))
      (conj (str "query " label
                 " cannot mark the same selector relevant and a hard negative")))))

(defn- query-errors [version index query]
  (let [query-map? (map? query)
        legacy? (and query-map? (legacy-query? query))
        label (if query-map? (or (:id query) index) index)]
    (cond-> []
      (not query-map?)
      (conj (str "query " label " must be a map"))

      (and query-map? (not (non-blank-string? (:query query))))
      (conj (str "query " label " must have a non-blank :query"))

      (and query-map? (not legacy?) (not (keyword? (:id query))))
      (conj (str "query " label " must have a keyword :id"))

      (and query-map? (not legacy?) (not (keyword? (:language query))))
      (conj (str "query " label " must have a keyword :language"))

      (and query-map? (not legacy?) (not (keyword? (:query-type query))))
      (conj (str "query " label " must have a keyword :query-type"))

      (and query-map? (contains? #{0 1} version))
      (into (v1-query-errors label query))

      (and query-map? (= 2 version))
      (into (v2-query-errors label query)))))

(defn validate-corpus-data!
  "Validate a corpus and return its version plus normalized queries. Legacy
  query vectors are reported as version 0."
  [corpus]
  (let [legacy? (vector? corpus)
        version (if legacy? 0 (:corpus/version corpus))
        queries (if legacy? corpus (:queries corpus))
        errors
        (cond-> []
          (and (not legacy?)
               (not (contains? supported-corpus-versions version)))
          (conj (str ":corpus/version must be one of "
                     (sort supported-corpus-versions)))

          (not (and (vector? queries) (seq queries)))
          (conj ":queries must be a non-empty vector")

          (and (vector? queries)
               (not= (count (keep :id queries))
                     (count (distinct (keep :id queries)))))
          (conj "query :id values must be unique"))
        errors
        (if (contains? (conj supported-corpus-versions 0) version)
          (into errors
                (mapcat (fn [[index query]]
                          (query-errors version index query))
                        (map-indexed vector (or queries []))))
          errors)]
    (when (seq errors)
      (throw (ex-info (str "Invalid semantic evaluation corpus: "
                           (str/join "; " errors))
                      {:exit-code 2 :errors errors})))
    {:corpus/version version
     :queries (mapv #(normalize-query version %) queries)}))

(defn validate-corpus! [corpus]
  (:queries (validate-corpus-data! corpus)))

(defn read-corpus-data [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (validate-corpus-data! (edn/read {:eof nil} reader))))

(defn read-corpus [path]
  (:queries (read-corpus-data path)))

(defn identities [result]
  (set (remove nil? ((juxt :id :name :qualified-name) result))))

(defn selector-match?
  "True when a search result satisfies every identity and qualifier supplied
  by a normalized selector."
  [result selector]
  (and
   (if-let [identity (:identity selector)]
     (contains? (identities result) identity)
     true)
   (every? (fn [field]
             (or (not (contains? selector field))
                 (= (get selector field) (get result field))))
           selector-fields)))

(defn- normalized-relevance [relevance]
  (if (map? relevance)
    (mapv (fn [[identity grade]] {:identity identity :grade grade}) relevance)
    (vec relevance)))

(defn grade
  "Return the highest relevance grade assigned to a result. This compatibility
  helper does not perform cross-result judgment deduplication."
  [result relevance]
  (reduce max 0
          (keep #(when (selector-match? result %) (:grade %))
                (normalized-relevance relevance))))

(defn relevant? [result relevance]
  (boolean (some #(selector-match? result %)
                 (normalized-relevance relevance))))

(defn hard-negative? [result hard-negatives]
  (boolean
   (some #(selector-match? result (normalize-selector %)) hard-negatives)))

(defn- dcg [grades]
  (reduce + 0.0
          (map-indexed
           (fn [index relevance]
             (/ (- (Math/pow 2.0 relevance) 1.0)
                (/ (Math/log (+ index 2.0)) (Math/log 2.0))))
           grades)))

(defn- ranked-grades [results relevance]
  (:grades
   (reduce
    (fn [{:keys [matched] :as state} result]
      (let [candidate
            (->> relevance
                 (map-indexed vector)
                 (remove (comp matched first))
                 (filter #(selector-match? result (second %)))
                 (sort-by (juxt (comp - :grade second) first))
                 first)]
        (if candidate
          (-> state
              (update :matched conj (first candidate))
              (update :grades conj (:grade (second candidate))))
          (update state :grades conj 0))))
    {:matched #{} :grades []}
    results)))

(defn ranked-metrics
  "Evaluate ordered results while allowing each relevance judgment to
  contribute gain at most once."
  [results {:keys [relevance hard-negatives]}]
  (let [relevance (normalized-relevance relevance)
        grades (ranked-grades results relevance)
        first-rank (some (fn [[index value]]
                           (when (pos? value) (inc index)))
                         (map-indexed vector grades))
        ideal (->> relevance (map :grade) (sort >) (take (count results)))
        ideal-dcg (dcg ideal)
        first-hard-negative-rank
        (some (fn [[index result]]
                (when (hard-negative? result hard-negatives) (inc index)))
              (map-indexed vector results))
        ndcg (if (pos? ideal-dcg) (/ (dcg grades) ideal-dcg) 0.0)]
    {:hit? (boolean first-rank)
     :first-relevant-rank first-rank
     :reciprocal-rank (if first-rank (/ 1.0 first-rank) 0.0)
     :ndcg (min 1.0 ndcg)
     :hard-negative-before-relevant?
     (boolean (and first-hard-negative-rank
                   (or (nil? first-rank)
                       (< first-hard-negative-rank first-rank))))}))
