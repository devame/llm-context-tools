(ns llm-context.semantic.progress
  "Constant-space cumulative and recent-window semantic worker telemetry.")

(def ^:private default-window-ms 60000)
(def ^:private default-idle-ms 10000)
(def ^:private maximum-samples 64)

(def ^:private cumulative-keys
  [:prepared-symbol-jobs :prepared-provider-documents :prepared-text-bytes
   :leased-symbol-jobs :request-count :request-provider-documents
   :request-text-bytes :accepted-symbol-jobs :visible-symbol-jobs
   :reused-symbol-jobs :prepare-ms :submit-ms :visibility-ms :completion-ms
   :provider-backpressure-count :provider-retry-count])

(def ^:private sample-keys
  [:completed :visible-symbol-jobs :request-provider-documents
   :request-text-bytes :prepare-ms :submit-ms :visibility-ms :completion-ms])

(defn initial
  ([now] (initial now {}))
  ([now {:keys [window-ms idle-ms]}]
   (merge
    {:started-at now
     :last-progress-at now
     :window-ms (long (or window-ms default-window-ms))
     :idle-ms (long (or idle-ms default-idle-ms))
     :recent-samples []
     :leased 0 :completed 0 :retried 0 :failed 0 :superseded 0
     :submitted-documents 0 :accepted-documents 0 :reused-documents 0
     :submitted-chunks 0 :upload-batches 0 :delete-ms 0 :upload-ms 0
     :visibility-ms 0
     :request-concurrency-effective 1
     :provider-queue-metrics :unavailable
     :recent-completed-symbols-per-second 0.0
     :recent-provider-documents-per-second 0.0
     :recent-text-bytes-per-second 0.0
     :recent-stage-percentages
     {:prepare 0.0 :submit 0.0 :visibility 0.0 :completion 0.0}}
    (zipmap cumulative-keys (repeat 0)))))

(defn- sum-key [samples key]
  (reduce + 0 (map #(long (or (get % key) 0)) samples)))

(defn- stage-percentages [samples]
  (let [values {:prepare (sum-key samples :prepare-ms)
                :submit (sum-key samples :submit-ms)
                :visibility (sum-key samples :visibility-ms)
                :completion (sum-key samples :completion-ms)}
        total (reduce + 0 (vals values))]
    (into {}
          (map (fn [[stage value]]
                 [stage (if (zero? total)
                          0.0
                          (* 100.0 (/ (double value) total)))])
               values))))

(defn- trim-samples [samples now window-ms]
  (let [cutoff (- now window-ms)]
    (->> samples
         (filter #(>= (:at %) cutoff))
         (take-last maximum-samples)
         vec)))

(defn- with-recent-rates [snapshot now]
  (let [samples (:recent-samples snapshot)
        idle? (> (- now (:last-progress-at snapshot)) (:idle-ms snapshot))
        start (max (:started-at snapshot)
                   (- now (:window-ms snapshot)))
        elapsed-ms (max 1 (- now start))
        per-second (fn [key]
                     (if (or idle? (empty? samples))
                       0.0
                       (* 1000.0
                          (/ (double (sum-key samples key)) elapsed-ms))))]
    (assoc snapshot
           :recent-completed-symbols-per-second (per-second :completed)
           :recent-provider-documents-per-second
           (per-second :request-provider-documents)
           :recent-text-bytes-per-second (per-second :request-text-bytes)
           :recent-stage-percentages (stage-percentages samples))))

(defn record
  "Record one bounded worker result and derive cumulative and recent metrics."
  [current result now]
  (let [leased (long (or (:leased result) 0))
        sample (merge {:at now} (select-keys result sample-keys))
        active? (pos? leased)
        snapshot
        (-> current
            (update :leased + leased)
            (update :completed + (long (or (:completed result) 0)))
            (update :retried + (long (or (:retried result) 0)))
            (update :failed + (long (or (:failed result) 0)))
            (update :superseded + (long (or (:superseded result) 0)))
            (update :submitted-documents +
                    (long (or (:submitted-documents result) 0)))
            (update :accepted-documents +
                    (long (or (:accepted-documents result) 0)))
            (update :reused-documents +
                    (long (or (:reused-documents result) 0)))
            (update :submitted-chunks +
                    (long (or (:submitted-chunks result) 0)))
            (update :upload-batches + (long (or (:upload-batches result) 0)))
            (update :delete-ms + (long (or (:delete-ms result) 0)))
            (update :upload-ms + (long (or (:upload-ms result) 0)))
            (cond-> active? (assoc :last-progress-at now))
            (cond-> (:ingestion-plan result)
              (assoc :ingestion-plan (:ingestion-plan result)))
            (assoc :request-concurrency-effective
                   (long (or (:request-concurrency-effective result)
                             (:request-concurrency-effective current)
                             1)))
            (cond-> active?
              (update :recent-samples
                      #(trim-samples (conj (vec %) sample) now
                                     (:window-ms current))))
            (cond-> (not active?)
              (update :recent-samples trim-samples now (:window-ms current))))
        snapshot
        (reduce (fn [value key]
                  (update value key + (long (or (get result key) 0))))
                snapshot cumulative-keys)
        elapsed-ms (max 1 (- now (:started-at snapshot)))]
    (-> snapshot
        (assoc :elapsed-ms elapsed-ms
               :documents-per-minute
               (* 60000.0 (/ (:completed snapshot) elapsed-ms)))
        (with-recent-rates now))))
