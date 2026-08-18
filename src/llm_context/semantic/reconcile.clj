(ns llm-context.semantic.reconcile
  "Translate durable dirty markers into coalesced symbol upserts/deletes by
  comparing authoritative graph documents with recorded NextPlaid state."
  (:require [clojure.string :as str]
            [llm-context.graph.read :as graph-read]
            [llm-context.semantic.document :as document]
            [llm-context.semantic.state :as state]
            [llm-context.store :as store]))

(def provider :lateon-code)
(def project-marker "project:*")

(defn enabled? [config]
  (let [lateon (get-in config [:semantic :lateon-code])]
    (and (:enabled lateon)
         (not= :disabled (:mode lateon))
         (contains? (set (get-in config [:semantic :providers])) provider))))

(defn dirty-marker
  ([file-id file-hash operation]
   (dirty-marker file-id file-hash operation (System/currentTimeMillis)))
  ([file-id file-hash operation now]
   (cond-> {:provider provider
            :file-id file-id
            :operation operation
            :created-at now}
     file-hash (assoc :file-hash file-hash))))

(defn dirty-entity
  ([file-id file-hash operation]
   (dirty-entity file-id file-hash operation (System/currentTimeMillis)))
  ([file-id file-hash operation now]
   (state/dirty-entity (dirty-marker file-id file-hash operation now))))

(defn mark-full! [graph]
  (state/mark-dirty! graph
                     (dirty-marker project-marker nil :reconcile-all)))

(defn- file-hashes [graph]
  (graph-read/file-hashes (store/database graph)))

(defn- path-for-file [graph file-id]
  (graph-read/file-path (store/database graph) file-id))

(defn- same-indexed? [lateon indexed desired]
  (and indexed
       (= (:document-hash desired)
          (:semantic.indexed/document-hash indexed))
       (= (:model-revision lateon)
          (:semantic.indexed/model-revision indexed))
       (= (:document-version lateon)
          (:semantic.indexed/document-version indexed))
       (= (count (:chunks desired))
          (:semantic.indexed/chunk-count indexed))))

(defn- desired-by-symbol [documents]
  (into (sorted-map)
        (map (juxt :symbol-id identity))
        (document/canonical-documents documents)))

(defn- reconciliation-plan
  [lateon provider file-id desired current pending now]
  (reduce
   (fn [result symbol-id]
     (let [wanted (get desired symbol-id)
           indexed-record (get current symbol-id)
           pending-record (get pending symbol-id)
           enqueue (fn [operation document-hash]
                     {:action :enqueue
                      :existing pending-record
                      :job (cond-> {:provider provider
                                    :symbol-id symbol-id
                                    :file-id file-id
                                    :operation operation
                                    :available-at now
                                    :updated-at now}
                             document-hash
                             (assoc :document-hash document-hash))})]
       (cond
         (and wanted (same-indexed? lateon indexed-record wanted))
         (if pending-record
           (-> result
               (update :unchanged inc)
               (update :cancelled inc)
               (update :actions conj {:action :cancel
                                      :existing pending-record}))
           (update result :unchanged inc))

         (and wanted (= :failed (:semantic.job/status pending-record)))
         ;; Terminal work remains inspectable until explicit semantic retry.
         (update result :unchanged inc)

         wanted
         (-> result
             (update :queued-upserts inc)
             (update :actions conj
                     (enqueue :upsert (:document-hash wanted))))

         indexed-record
         (-> result
             (update :queued-deletes inc)
             (update :actions conj (enqueue :delete nil)))

         pending-record
         (-> result
             (update :cancelled inc)
             (update :actions conj {:action :cancel
                                    :existing pending-record}))

         :else result)))
   {:queued-upserts 0 :queued-deletes 0
    :cancelled 0 :unchanged 0 :actions []}
   (sort (set (concat (keys desired) (keys current) (keys pending))))))

(defn- reconcile-file-once!
  [graph project lateon marker now]
  (let [file-id (:semantic.dirty/file-id marker)
        operation (:semantic.dirty/operation marker)
        built (if (= :delete operation)
                {:status :deleted :file-id file-id
                 :documents [] :diagnostics []}
                (document/build-file graph project lateon file-id))]
    (if (or (= :source-changed (:status built))
            (seq (:diagnostics built)))
      {:status :deferred
       :file-id file-id
       :diagnostics (:diagnostics built)
       :queued-upserts 0 :queued-deletes 0
       :cancelled 0 :unchanged 0}
      (let [db (store/database graph)
            desired (desired-by-symbol (:documents built))
            current (graph-read/semantic-indexed-for-file
                     db provider file-id)
            pending (graph-read/semantic-jobs-for-file
                     db provider file-id)
            plan (reconciliation-plan lateon provider file-id desired
                                      current pending now)
            applied
            (locking graph
              (state/apply-reconciliation!
               graph {:provider provider
                      :file-id file-id
                      :file-hash (:semantic.dirty/file-hash marker)
                      :operation operation
                      :marker marker
                      :pending pending
                      :indexed current
                      :actions (:actions plan)}))]
        (if (:applied? applied)
          (-> plan
              (dissoc :actions)
              (assoc :status :reconciled
                     :file-id file-id
                     :diagnostics (:diagnostics built)))
          {:status :stale :file-id file-id :diagnostics []
           :queued-upserts 0 :queued-deletes 0
           :cancelled 0 :unchanged 0})))))

(defn- reconcile-file!
  [graph project lateon marker now]
  (loop [attempt 0 marker marker]
    (let [result (reconcile-file-once! graph project lateon marker now)]
      (if (and (= :stale (:status result)) (< attempt 2))
        (let [fresh-marker
              (if (:db/id marker)
                (some #(when (= (:semantic.dirty/file-id marker)
                                (:semantic.dirty/file-id %))
                         %)
                      (state/dirty-records graph provider))
                marker)]
          (if fresh-marker
            (recur (inc attempt) fresh-marker)
            (assoc result :status :superseded)))
        (if (= :stale (:status result))
          (assoc result
                 :status :deferred
                 :diagnostics
                 [{:level :warning :kind :semantic-reconciliation-stale
                   :file-id (:semantic.dirty/file-id marker)
                   :message "Graph state changed repeatedly during semantic reconciliation"}])
          result)))))

(defn- reconcile-file-safely!
  [graph project lateon marker now]
  (try
    (reconcile-file! graph project lateon marker now)
    (catch Exception error
      (let [file-id (:semantic.dirty/file-id marker)
            file-path (path-for-file graph file-id)]
        {:status :deferred
         :file-id file-id
         :diagnostics
         [(cond-> {:level :warning
                   :kind :semantic-file-failed
                   :file-id file-id
                   :message (.getMessage error)}
            file-path (assoc :file file-path))]
         :queued-upserts 0 :queued-deletes 0
         :cancelled 0 :unchanged 0}))))

(defn reconcile!
  "Reconcile all durable LateOn dirty markers. Safe to call after every
  analysis and on every service start."
  ([graph project config]
   (reconcile! graph project config (System/currentTimeMillis)))
  ([graph project config now]
   (if-not (enabled? config)
     {:enabled? false :queued-upserts 0 :queued-deletes 0
      :cancelled 0 :unchanged 0 :deferred 0 :diagnostics []}
     (let [lateon (get-in config [:semantic :lateon-code])
           db (store/database graph)
           graph-revision (document/graph-revision db)
           markers (state/dirty-records graph provider)
           desired-symbol-ids (document/indexable-symbol-ids db)
           indexed-symbol-ids
           (set (map :semantic.indexed/symbol-id
                     (state/indexed-records graph provider)))
           job-symbol-ids
           (set (map :semantic.job/symbol-id
                     (state/job-records graph provider)))
           watermark-revision
           (:semantic.watermark/graph-revision
            (state/watermark graph provider))
           uncovered? (not= desired-symbol-ids
                            (into indexed-symbol-ids job-symbol-ids))
           stale-watermark? (and watermark-revision
                                 (not= graph-revision watermark-revision))
           recovery? (and (empty? markers)
                          (or uncovered? stale-watermark?))
           markers (if recovery?
                     [(state/dirty-entity
                       (dirty-marker project-marker nil
                                     :reconcile-all now))]
                     markers)
           full? (some #(= :reconcile-all
                           (:semantic.dirty/operation %))
                       markers)
           explicit (remove #(= :reconcile-all
                                (:semantic.dirty/operation %))
                            markers)
           marked-files (set (map :semantic.dirty/file-id explicit))
           graph-file-hashes (file-hashes graph)
           synthetic
           (when full?
             (for [[file-id file-hash] graph-file-hashes
                   :when (not (contains? marked-files file-id))]
               (state/dirty-entity
                (dirty-marker file-id file-hash :upsert now))))
           ;; Indexed files absent from the graph need deletion during a full
           ;; repair, including after an interrupted graph replacement.
           graph-files (set (keys graph-file-hashes))
           indexed-files
           (when full?
             (graph-read/semantic-indexed-file-ids
              (store/database graph) provider))
           missing
           (when full?
             (for [file-id (sort (remove graph-files indexed-files))
                   :when (not (contains? marked-files file-id))]
               (state/dirty-entity
                (dirty-marker file-id nil :delete now))))
           work (->> (concat explicit synthetic missing)
                     (sort-by :semantic.dirty/file-id)
                     vec)
           results (mapv #(reconcile-file-safely! graph project lateon % now)
                         work)
           deferred (count (filter #(= :deferred (:status %)) results))]
       (doseq [{:keys [status file-id diagnostics]} results
               :when (= :deferred status)]
         (state/record-dirty-diagnostic!
          graph provider file-id now
          (or (some->> diagnostics (map :message) (remove nil?) seq
                       (str/join "; "))
              "Semantic reconciliation deferred without details")))
       (when (and full? (zero? deferred))
         (state/clear-dirty! graph provider project-marker))
       {:enabled? true
        :dirty (count work)
        :queued-upserts (reduce + (map :queued-upserts results))
        :queued-deletes (reduce + (map :queued-deletes results))
        :cancelled (reduce + (map :cancelled results))
        :unchanged (reduce + (map :unchanged results))
        :deferred deferred
        :recovered-missing-markers? recovery?
        :graph-revision graph-revision
        :diagnostics (vec (mapcat :diagnostics results))}))))

(defn- retry-failures!
  [graph project config now failures]
  (let [db (store/database graph)
        hashes (graph-read/file-hashes db)
         eligible (filter #(contains? hashes (:file-id %)) failures)
         stale (remove #(contains? hashes (:file-id %)) failures)]
    (doseq [{:keys [symbol-id file-id]} eligible]
      (state/cancel-job! graph provider symbol-id)
      (state/mark-dirty!
       graph (dirty-marker file-id (get hashes file-id) :upsert now)))
    (doseq [{:keys [symbol-id]} stale]
      (state/cancel-job! graph provider symbol-id))
    (assoc (reconcile! graph project config now)
           :retried (count eligible)
           :discarded-stale (count stale))))

(defn retry-failed!
  "Cancel terminal jobs only when their source file still exists, mark those
  files dirty, and reconcile fresh desired documents."
  ([graph project config]
   (retry-failed! graph project config (System/currentTimeMillis)))
  ([graph project config now]
   (retry-failures! graph project config now
                    (state/failure-records graph provider))))

(def ^:private recoverable-infrastructure-error
  #"(?i)(NextPlaid request timed out|NextPlaid service is unavailable|provider became unavailable|CUDA_ERROR_NO_DEVICE|CUDA initialization error|connection refused)")

(defn retry-recoverable-failed!
  "Retry only legacy terminal jobs whose bounded error text identifies a known
  provider-wide infrastructure failure. Deterministic and unknown failures stay
  terminal for operator inspection."
  ([graph project config]
   (retry-recoverable-failed! graph project config (System/currentTimeMillis)))
  ([graph project config now]
   (let [failures (filter #(re-find recoverable-infrastructure-error
                                    (or (:last-error %) ""))
                          (state/failure-records graph provider))]
     (if (seq failures)
       (assoc (retry-failures! graph project config now failures)
              :automatic? true)
       {:enabled? (enabled? config) :retried 0 :discarded-stale 0
        :automatic? true}))))
