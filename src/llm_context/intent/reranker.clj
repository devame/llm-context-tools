(ns llm-context.intent.reranker
  "Learned candidate reranking over a bounded retrieval prefix. Semantic
  ordering is model-owned; deterministic structural checks happen later."
  (:require [clojure.string :as str]
            [llm-context.semantic.next-plaid :as next-plaid])
  (:import [java.util LinkedHashMap Map]))

(defprotocol CandidateReranker
  (rerank [reranker query candidates]
    "Return the same candidates in learned relevance order with provenance."))

(defn- aggregate-document [aggregates]
  (when (seq aggregates)
    (str/join
     "\n"
     (mapcat
      (fn [{:keys [kind completeness member-count member-kind members]}]
        [(str "aggregate-kind: " (name kind))
         (str "aggregate-completeness: " (name completeness))
         (str "aggregate-member-kind: " (name member-kind))
         (str "aggregate-member-count: " member-count)
         (str "aggregate-members: "
              (str/join ", "
                        (map (fn [{:keys [key value]}]
                               (if key (str key " => " value) value))
                             (take 64 members))))])
      aggregates))))

(defn- candidate-document [candidate]
  (->> [[:qualified-name (:qualified-name candidate)]
        [:name (:name candidate)]
        [:kind (:kind candidate)]
        [:signature (:signature candidate)]
        [:documentation (:doc candidate)]
        [:source-path (:file candidate)]
        [:source-role (:source-role candidate)]
        [:aggregate-evidence (aggregate-document (:aggregates candidate))]]
       (keep (fn [[label value]]
               (when-not (str/blank? (str value))
                 (str (name label) ": " value))))
       (str/join "\n")))

(defn- lru-cache [maximum]
  (proxy [LinkedHashMap] [16 0.75 true]
    (removeEldestEntry [_]
      (> (.size ^Map this) maximum))))

(defn- cache-get [^Map cache key]
  (locking cache
    (when (.containsKey cache key)
      (.get cache key))))

(defn- cache-put! [^Map cache key value]
  (locking cache
    (.put cache key value))
  value)

(defn- remaining-ms [deadline]
  (max 0 (- deadline (System/currentTimeMillis))))

(defn- require-time! [deadline]
  (let [remaining (remaining-ms deadline)]
    (when (zero? remaining)
      (throw (ex-info "Learned candidate reranking timed out"
                      {:type :candidate-reranker/timed-out})))
    remaining))

(defn- dot-product
  [^floats left left-offset ^floats right right-offset dimension]
  (loop [component 0
         total 0.0]
    (if (= component dimension)
      total
      (recur (inc component)
             (+ total
                (* (double (aget left (+ left-offset component)))
                   (double (aget right (+ right-offset component)))))))))

(defn- max-sim
  [{query-tokens :tokens query-dimension :dimension query-values :values}
   {document-tokens :tokens document-dimension :dimension
    document-values :values}]
  (when-not (= query-dimension document-dimension)
    (throw (ex-info "Query and document embedding dimensions differ"
                    {:type :candidate-reranker/dimension-mismatch
                     :query-dimension query-dimension
                     :document-dimension document-dimension})))
  (when (or (zero? query-tokens) (zero? document-tokens))
    (throw (ex-info "Cannot score an empty token embedding"
                    {:type :candidate-reranker/empty-embedding})))
  (let [^floats query-values query-values
        ^floats document-values document-values
        dimension (long query-dimension)]
    (loop [query-token 0
           total 0.0]
      (if (= query-token query-tokens)
        (/ total (double query-tokens))
        (let [query-offset (* query-token dimension)
              best
              (loop [document-token 0
                     maximum Double/NEGATIVE_INFINITY]
                (if (= document-token document-tokens)
                  maximum
                  (let [document-offset (* document-token dimension)
                        similarity (dot-product
                                    query-values query-offset
                                    document-values document-offset dimension)]
                    (recur (inc document-token)
                           (max maximum similarity)))))]
          (recur (inc query-token) (+ total best)))))))

(defn- encode-documents
  [encode-fn cache candidates deadline]
  (let [entries (mapv (fn [candidate]
                        (let [document (candidate-document candidate)]
                          {:candidate candidate
                           :key [(:id candidate) document]
                           :document document}))
                      candidates)
        cached (mapv #(cache-get cache (:key %)) entries)
        missing-indexes (keep-indexed #(when (nil? %2) %1) cached)
        missing-entries (mapv entries missing-indexes)
        encoded
        (if (seq missing-entries)
          (encode-fn (mapv :document missing-entries)
                     {:input-type :document
                      :timeout-ms (require-time! deadline)})
          [])]
    (when-not (= (count missing-entries) (count encoded))
      (throw (ex-info "Candidate encoder returned an incomplete result"
                      {:type :candidate-reranker/invalid-response
                       :requested (count missing-entries)
                       :returned (count encoded)})))
    (doseq [[entry embedding] (map vector missing-entries encoded)]
      (cache-put! cache (:key entry) embedding))
    {:embeddings (mapv #(cache-get cache (:key %)) entries)
     :cache-hits (- (count entries) (count missing-entries))
     :cache-misses (count missing-entries)}))

(defrecord NextPlaidCandidateReranker
    [encode-fn cache settings model model-revision]
  CandidateReranker
  (rerank [_ query candidates]
    (let [started (System/nanoTime)
          limit (min (count candidates) (:candidate-count settings))
          ranked-candidates
          (mapv #(assoc %1 :pre-rerank-rank %2)
                candidates (range 1 (inc (count candidates))))
          prefix (vec (take limit ranked-candidates))
          suffix (vec (drop limit ranked-candidates))]
      (if (empty? prefix)
        {:results (vec candidates)
         :provider :mixedbread-32m
         :status :not-needed
         :candidate-count 0
         :cache-hits 0 :cache-misses 0
         :latency-ms 0 :reordered? false}
        (let [deadline (+ (System/currentTimeMillis)
                          (:query-timeout-ms settings))
              query-embedding
              (first
               (encode-fn [query]
                          {:input-type :query
                           :timeout-ms (require-time! deadline)}))
              _ (when-not query-embedding
                  (throw (ex-info "Query encoder returned no embedding"
                                  {:type :candidate-reranker/invalid-response})))
              {:keys [embeddings cache-hits cache-misses]}
              (encode-documents encode-fn cache prefix deadline)
              scored
              (mapv
               (fn [rank candidate embedding]
                 (require-time! deadline)
                 (assoc candidate
                        :pre-rerank-rank rank
                        :learned-score (max-sim query-embedding embedding)))
               (range 1 (inc limit)) prefix embeddings)
              ordered-prefix
              (vec (sort-by (juxt (comp - :learned-score)
                                  :pre-rerank-rank)
                            scored))
              model-ordered (vec (concat ordered-prefix suffix))
              enforce? (= :enforce (:mode settings :enforce))
              scores-by-id (into {} (map (juxt :id :learned-score) scored))
              shadow-order
              (mapv (fn [rank candidate]
                      (cond-> (assoc candidate
                                     :pre-rerank-rank rank
                                     :post-rerank-rank rank)
                        (contains? scores-by-id (:id candidate))
                        (assoc :learned-score
                               (get scores-by-id (:id candidate)))))
                    (range 1 (inc (count ranked-candidates)))
                    ranked-candidates)
              ordered (if enforce? model-ordered shadow-order)
              results (mapv #(assoc %1 :post-rerank-rank %2)
                            ordered (range 1 (inc (count ordered))))]
          {:results results
           :provider :mixedbread-32m
           :status (if enforce? :applied :shadowed)
           :mode (:mode settings :enforce)
           :model model
           :model-revision model-revision
           :candidate-count limit
           :cache-hits cache-hits
           :cache-misses cache-misses
           :latency-ms (long (/ (- (System/nanoTime) started) 1000000))
           :would-reorder?
           (not= (mapv :id candidates) (mapv :id model-ordered))
           :reordered? (and enforce?
                            (not= (mapv :id candidates)
                                  (mapv :id results)))})))))

(defn create-with-encoder
  "Construct a reranker from the exported encoding contract. This is also the
  stable provider seam for deterministic tests and future runtimes."
  [encode-fn model-settings reranker-settings]
  (->NextPlaidCandidateReranker
   encode-fn
   (lru-cache (:document-cache-size reranker-settings))
   reranker-settings
   (:model model-settings)
   (:model-revision model-settings)))

(defn create [client model-settings reranker-settings]
  (create-with-encoder
   #(next-plaid/encode-texts client %1 %2)
   model-settings reranker-settings))

(defn unavailable
  ([reason] (unavailable reason nil))
  ([reason detail]
   (reify CandidateReranker
     (rerank [_ _ candidates]
       (cond-> {:results (vec candidates)
                :provider :mixedbread-32m
                :status :unavailable
                :reason reason
                :candidate-count 0
                :cache-hits 0 :cache-misses 0
                :latency-ms 0 :reordered? false}
         detail (assoc :detail detail))))))

(defn- valid-result? [input result]
  (let [input-ids (mapv :id input)
        output-ids (mapv :id (:results result))]
    (and (= (count input-ids) (count output-ids))
         (= (set input-ids) (set output-ids))
         (= (count output-ids) (count (distinct output-ids))))))

(defn safely-rerank
  "Apply an optional provider. Every failure preserves the original ordering
  and remains visible in provenance."
  [provider query candidates]
  (if-not provider
    {:results (vec candidates)
     :provider :none :status :unavailable :reason :not-configured
     :candidate-count 0 :cache-hits 0 :cache-misses 0
     :latency-ms 0 :reordered? false}
    (let [started (System/nanoTime)]
      (try
        (let [result (rerank provider query candidates)]
        (if (valid-result? candidates result)
          result
          {:results (vec candidates)
           :provider (or (:provider result) :unknown)
           :status :failed :reason :identity-mismatch
           :candidate-count 0 :cache-hits 0 :cache-misses 0
           :latency-ms (:latency-ms result 0) :reordered? false}))
        (catch Throwable error
          (let [data (ex-data error)]
            {:results (vec candidates)
             :provider :mixedbread-32m
             :status (if (or (= :candidate-reranker/timed-out (:type data))
                             (:timeout? data))
                       :timed-out :failed)
             :reason (or (:type data) :provider-error)
             :detail (.getMessage error)
             :candidate-count 0 :cache-hits 0 :cache-misses 0
             :latency-ms
             (long (/ (- (System/nanoTime) started) 1000000))
             :reordered? false}))))))
