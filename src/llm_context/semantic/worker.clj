(ns llm-context.semantic.worker
  "Single-writer background consumer for durable LateOn jobs."
  (:refer-clojure :exclude [run!])
  (:require [llm-context.semantic.document :as document]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.reconcile :as reconcile]
            [llm-context.semantic.state :as state]
            [llm-context.store :as store])
  (:import [java.util UUID]))

(defn- now [worker]
  ((:now-fn worker)))

(defn- sleep! [worker milliseconds]
  ((:sleep-fn worker) milliseconds))

(defn- with-graph-lock [worker f]
  (locking (:graph worker)
    (f)))

(defn- retry-delay [settings attempts]
  (let [shift (min 20 (max 0 attempts))
        calculated (* (:retry-base-ms settings)
                      (bit-shift-left 1 shift))]
    (min (:retry-max-ms settings) calculated)))

(defn- await-count!
  [worker job symbol-id document-hash predicate description]
  (let [settings (:settings worker)
        deadline (+ (now worker) (:visibility-timeout-ms settings))]
    (loop []
      (let [time (now worker)
            renewed? (with-graph-lock
                       worker
                       #(state/renew-job-lease!
                         (:graph worker) (:semantic.job/id job)
                         (:owner worker) time (:lease-ms settings)))
            _ (when-not renewed?
                (throw
                 (ex-info "Semantic job lease was superseded"
                          {:type :semantic/lease-lost
                           :retriable? false
                           :job-id (:semantic.job/id job)})))
            count (index/indexed-chunk-count
                   (:client worker) symbol-id document-hash)]
        (cond
          (predicate count) count
          (>= (now worker) deadline)
          (throw
           (ex-info (str "Timed out waiting for NextPlaid " description)
                    {:type :semantic/visibility-timeout
                     :retriable? true
                     :symbol-id symbol-id
                     :document-hash document-hash
                     :observed-count count}))
          :else
          (do
            (sleep! worker (:visibility-poll-ms settings))
            (recur)))))))

(defn- documents-for-jobs [worker jobs]
  (let [built (document/build-symbols
               (:graph worker) (:project worker) (:settings worker)
               (:semantic.job/file-id (first jobs))
               (mapv :semantic.job/symbol-id jobs))]
    (when-not (= :ready (:status built))
      (throw
       (ex-info "Source changed before semantic ingestion"
                {:type :semantic/source-not-ready
                 :retriable? true
                 :file-id (:semantic.job/file-id (first jobs))
                 :status (:status built)})))
    (into {} (map (juxt :symbol-id identity)) (:documents built))))

(defn- remove-visible-symbol! [worker job symbol-id]
  (when (pos? (index/indexed-chunk-count
               (:client worker) symbol-id nil))
    (index/delete-symbols! (:client worker) [symbol-id])
    (await-count! worker job symbol-id nil zero? "deletion")))

(defn- validate-desired! [job desired]
  (when-not desired
    (throw
     (ex-info "Semantic symbol no longer exists in its committed file"
              {:type :semantic/symbol-missing
               :retriable? true
               :symbol-id (:semantic.job/symbol-id job)})))
  (let [expected (:semantic.job/document-hash job)]
    (when-not (= expected (:document-hash desired))
      (throw
       (ex-info "Semantic job was superseded by current graph content"
                {:type :semantic/job-superseded
                 :retriable? true
                 :symbol-id (:semantic.job/symbol-id job)
                 :expected expected
                 :actual (:document-hash desired)}))))
  desired)

(defn- renew-lease! [worker job]
  (when-not
   (with-graph-lock
    worker
    #(state/renew-job-lease!
      (:graph worker) (:semantic.job/id job) (:owner worker)
      (now worker) (:lease-ms (:settings worker))))
    (throw
     (ex-info "Semantic job lease was superseded"
              {:type :semantic/lease-lost :retriable? false
               :job-id (:semantic.job/id job)}))))

(defn- indexed-record [worker desired]
  {:provider reconcile/provider
   :symbol-id (:symbol-id desired)
   :file-id (:file-id desired)
   :document-hash (:document-hash desired)
   :model-revision (:model-revision desired)
   :document-version (:document-version desired)
   :chunk-count (count (:chunks desired))
   :updated-at (now worker)})

(defn- process-delete! [worker job]
  (remove-visible-symbol! worker job (:semantic.job/symbol-id job))
  nil)

(defn- complete-job! [worker job indexed]
  (let [operation (:semantic.job/operation job)
        completed-at (now worker)]
    (if (with-graph-lock
          worker
          #(state/complete-job!
            (:graph worker)
            {:job-id (:semantic.job/id job)
             :lease-owner (:owner worker)
             :indexed indexed
             :completed-at completed-at}))
      {:status :completed :operation operation}
      {:status :superseded :operation operation})))

(defn- process-delete-job! [worker job]
  (process-delete! worker job)
  (complete-job! worker job nil))

(defn- retry-job! [worker job error]
  (let [settings (:settings worker)
        attempts (:semantic.job/attempts job)
        failed-at (now worker)
        retriable? (not= false (:retriable? (ex-data error)))
        max-attempts (if retriable? (:max-attempts settings) 1)
        available-at (+ failed-at (retry-delay settings attempts))
        result
        (with-graph-lock
          worker
          #(state/retry-job!
            (:graph worker)
            {:job-id (:semantic.job/id job)
             :lease-owner (:owner worker)
             :failed-at failed-at
             :available-at available-at
             :error (.getMessage ^Throwable error)
             :max-attempts max-attempts}))]
    (when (= :failed (:status result))
      (binding [*out* *err*]
        (println
         (pr-str
          {:event :semantic-terminal-failure
           :symbol-id (:semantic.job/symbol-id job)
           :file-id (:semantic.job/file-id job)
           :attempts (:attempts result)
           :timestamp failed-at
           :error (.getMessage ^Throwable error)}))))
    {:status (or (:status result) :superseded)
     :operation (:semantic.job/operation job)
     :error error}))

(defn- safely [worker job f]
  (try
    (f)
    (catch Throwable error
      (retry-job! worker job error))))

(defn- prepare-upsert-group [worker jobs]
  (try
    (let [documents (documents-for-jobs worker jobs)]
      (mapv
       (fn [job]
         (safely
          worker job
          #(let [desired (validate-desired!
                          job (get documents (:semantic.job/symbol-id job)))]
             ;; NextPlaid additions are append-only. Every replacement is
             ;; fully deleted before any member of this batch is submitted.
             (remove-visible-symbol! worker job (:symbol-id desired))
             (renew-lease! worker job)
             {:job job :desired desired})))
       jobs))
    (catch Throwable error
      (mapv #(retry-job! worker % error) jobs))))

(defn- process-upsert-batch! [worker jobs]
  (let [prepared-results
        (mapcat #(prepare-upsert-group worker %)
                (partition-by
                 :semantic.job/file-id
                 (sort-by (juxt :semantic.job/file-id :semantic.job/id) jobs)))
        prepared (filterv :desired prepared-results)
        immediate (filterv :status prepared-results)]
    (if (empty? prepared)
      immediate
      (try
        (doseq [batch (partition-all (:update-batch-size (:settings worker))
                                     (mapcat (comp :chunks :desired) prepared))]
          (index/add-documents! (:client worker) (vec batch)))
        (into immediate
              (mapv
               (fn [{:keys [job desired]}]
                 (safely
                  worker job
                  #(do
                     (await-count!
                      worker job (:symbol-id desired) (:document-hash desired)
                      (fn [observed]
                        (= (count (:chunks desired)) observed))
                      "upsert visibility")
                     (complete-job! worker job
                                    (indexed-record worker desired)))))
               prepared))
        (catch Throwable error
          (into immediate
                (mapv #(retry-job! worker (:job %) error) prepared)))))))

(defn prepare!
  "Recover state, reconcile graph changes, verify the exact model, and declare
  the project index before consuming jobs."
  [worker]
  (let [time (now worker)
        recovered (with-graph-lock
                    worker
                    #(state/recover-expired-leases!
                      (:graph worker) reconcile/provider time))
        planned (reconcile/reconcile! (:graph worker)
                                      (:project worker)
                                      (:config worker)
                                      time)
        health (index/index-health (:client worker))]
    (when-not (:ready? health)
      (with-graph-lock
        worker
        #(state/record-watermark!
          (:graph worker)
          {:provider reconcile/provider
           :state :degraded
           :last-error-at time
           :last-error "NextPlaid or its pinned model is not ready"}))
      (throw
       (ex-info "NextPlaid or its pinned LateOn model is not ready"
                {:type :semantic/not-ready
                 :retriable? true
                 :health (dissoc health :raw)})))
    (index/ensure-index! (:client worker))
    (with-graph-lock
      worker
      #(state/record-watermark!
        (:graph worker)
        {:provider reconcile/provider :state :idle
         :graph-revision (:graph-revision planned)}))
    {:recovered recovered :planned planned :health health}))

(defn process-once!
  "Lease and synchronously process one bounded job batch."
  [worker]
  (let [time (now worker)
        settings (:settings worker)
        _ (with-graph-lock
            worker
            #(state/recover-expired-leases!
              (:graph worker) reconcile/provider time))
        jobs (with-graph-lock
               worker
               #(state/lease-jobs!
                 (:graph worker) reconcile/provider (:owner worker)
                 time (:lease-ms settings) (:update-batch-size settings)))]
    (if (empty? jobs)
      {:leased 0 :completed 0 :retried 0 :failed 0 :superseded 0}
      (do
        (with-graph-lock
          worker
          #(state/record-watermark!
            (:graph worker)
            {:provider reconcile/provider :state :indexing}))
        (let [upserts (filterv #(= :upsert (:semantic.job/operation %)) jobs)
              deletes (filterv #(= :delete (:semantic.job/operation %)) jobs)
              invalid (remove #(#{:upsert :delete}
                                 (:semantic.job/operation %)) jobs)
              results
              (into (process-upsert-batch! worker upserts)
                    (concat
                     (map (fn [job]
                            (safely worker job
                                    #(process-delete-job! worker job)))
                          deletes)
                     (map (fn [job]
                            (retry-job!
                             worker job
                             (ex-info "Unknown semantic job operation"
                                      {:type :semantic/invalid-job
                                       :retriable? false
                                       :operation
                                       (:semantic.job/operation job)})))
                          invalid)))
              frequencies (frequencies (map :status results))
              summary {:leased (count jobs)
                       :completed (get frequencies :completed 0)
                       :retried (get frequencies :pending 0)
                       :failed (get frequencies :failed 0)
                       :superseded (get frequencies :superseded 0)}]
          (with-graph-lock
            worker
            #(state/record-watermark!
              (:graph worker)
              (if (pos? (:failed summary))
                {:provider reconcile/provider
                 :state :degraded
                 :last-error-at (now worker)
                 :last-error "One or more semantic jobs exhausted retries"
                 :graph-revision
                 (document/graph-revision
                  (store/database (:graph worker)))}
                {:provider reconcile/provider
                 :state :ready
                 :last-success-at (now worker)
                 :graph-revision
                 (document/graph-revision
                  (store/database (:graph worker)))})))
          summary)))))

(defn run!
  "Prepare and consume jobs until stop! is requested."
  [worker]
  (prepare! worker)
  (while (not @(:stop? worker))
    (let [result (process-once! worker)]
      (when (zero? (:leased result))
        (sleep! worker (:idle-poll-ms (:settings worker))))))
  :stopped)

(defn stop! [worker]
  (reset! (:stop? worker) true)
  nil)

(defn create
  ([graph project config client]
   (create graph project config client {}))
  ([graph project config client {:keys [owner now-fn sleep-fn]}]
   {:graph graph
    :project project
    :config config
    :settings (get-in config [:semantic :lateon-code])
    :client client
    :owner (or owner (str (UUID/randomUUID)))
    :now-fn (or now-fn #(System/currentTimeMillis))
    :sleep-fn (or sleep-fn #(Thread/sleep %))
    :stop? (atom false)}))
