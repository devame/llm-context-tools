(ns llm-context.semantic.worker
  "Single-writer background consumer for durable LateOn jobs."
  (:refer-clojure :exclude [run!])
  (:require [llm-context.semantic.document :as document]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.reconcile :as reconcile]
            [llm-context.semantic.state :as state]
            [llm-context.storage :as storage]
            [llm-context.store :as store])
  (:import [java.util UUID]
           [java.util.concurrent Callable ExecutionException Executors
            TimeUnit]))

(defn- now [worker]
  ((:now-fn worker)))

(defn- sleep! [worker milliseconds]
  ((:sleep-fn worker) milliseconds))

(def ^:private inventory-batch-size 128)

(defn- generation-symbol-id [generation]
  (str "semantic-generation:" generation))

(defn- generation-document [settings generation]
  (let [symbol-id (generation-symbol-id generation)]
    {:id (str symbol-id "#chunk-000")
     :symbol-id symbol-id
     :file-id "semantic-generation"
     :document-hash (str "generation:" generation)
     :model-revision (:model-revision settings)
     :document-version (:document-version settings)
     :chunk-index 0
     :chunk-count 1
     :text "llm-context semantic index generation marker"}))

(defn- with-graph-lock [worker f]
  (locking (:graph worker)
    (f)))

(defn- matching-indexed-record?
  [visible indexed]
  (let [matching
        (filter #(and (= (:semantic.indexed/symbol-id indexed)
                         (:symbol-id %))
                      (= (:semantic.indexed/document-hash indexed)
                         (:document-hash %))
                      (= (:semantic.indexed/model-revision indexed)
                         (:model-revision %))
                      (= (:semantic.indexed/document-version indexed)
                         (:document-version %)))
                visible)]
    (and (= (:semantic.indexed/chunk-count indexed) (count matching))
         (= (:semantic.indexed/chunk-count indexed)
            (count (filter #(= (:semantic.indexed/symbol-id indexed)
                               (:symbol-id %))
                           visible))))))

(defn- indexed-inventory-valid?
  [worker indexed-records]
  (every?
   true?
   (for [batch (partition-all inventory-batch-size indexed-records)
         :let [visible (index/indexed-documents
                        (:client worker)
                        (mapv :semantic.indexed/symbol-id batch))]]
     (every? #(matching-indexed-record? visible %) batch))))

(defn- generation-visible?
  [worker generation]
  (let [expected (generation-document (:settings worker) generation)
        attributes [:id :symbol-id :file-id :document-hash :model-revision
                    :document-version :chunk-index :chunk-count]
        visible (index/indexed-documents
                 (:client worker) [(:symbol-id expected)])]
    (and (= 1 (count visible))
         (= (select-keys expected attributes)
            (select-keys (first visible) attributes)))))

(defn- await-generation! [worker generation]
  (let [deadline (+ (now worker)
                    (get-in worker [:settings :visibility-timeout-ms]))]
    (loop []
      (cond
        (generation-visible? worker generation) true
        (>= (now worker) deadline)
        (throw
         (ex-info "Timed out waiting for NextPlaid generation marker"
                  {:type :semantic/visibility-timeout
                   :retriable? true
                   :index-generation generation}))
        :else
        (do
          (sleep! worker (get-in worker [:settings :visibility-poll-ms]))
          (recur))))))

(defn- create-generation! [worker]
  (let [generation (str (UUID/randomUUID))]
    (index/add-documents!
     (:client worker) [(generation-document (:settings worker) generation)])
    (await-generation! worker generation)
    generation))

(defn- invalidate-semantic-state! [worker]
  (with-graph-lock
    worker
    #(do
       (store/reset-semantic-state! (:graph worker))
       (reconcile/mark-full! (:graph worker)))))

(defn- ensure-index-generation!
  [worker index-state]
  (let [watermark (state/watermark (:graph worker) reconcile/provider)
        generation (:semantic.watermark/index-generation watermark)
        indexed-records (state/indexed-records (:graph worker)
                                               reconcile/provider)
        generation-valid? (and generation
                               (generation-visible? worker generation))
        inventory-valid? (and (not (:created? index-state))
                              (or generation-valid?
                                  (indexed-inventory-valid?
                                   worker indexed-records)))
        invalidated? (or (:created? index-state)
                         (and generation (not generation-valid?))
                         (and (seq indexed-records) (not inventory-valid?)))]
    (when invalidated?
      (invalidate-semantic-state! worker))
    (let [active-generation (if generation-valid?
                              generation
                              (create-generation! worker))]
      (with-graph-lock
        worker
        #(state/record-watermark!
          (:graph worker)
          {:provider reconcile/provider
           :state :idle
           :index-generation active-generation}))
      {:generation active-generation
       :created? (:created? index-state)
       :inventory-verified? inventory-valid?
       :invalidated? invalidated?})))

(defn- retry-delay [settings attempts]
  (let [shift (min 20 (max 0 attempts))
        calculated (* (:retry-base-ms settings)
                      (bit-shift-left 1 shift))]
    (min (:retry-max-ms settings) calculated)))

(defn- renew-leases! [worker jobs]
  (let [settings (:settings worker)
        time (now worker)
        job-ids (mapv :semantic.job/id jobs)
        renewed
        (with-graph-lock
          worker
          #(state/renew-job-leases!
            (:graph worker) job-ids (:owner worker) time
            (:lease-ms settings)))]
    (doseq [job jobs]
      (when-not (contains? renewed (:semantic.job/id job))
        (throw
         (ex-info "Semantic job lease was superseded"
                  {:type :semantic/lease-lost
                   :retriable? false
                   :job-id (:semantic.job/id job)}))))))

(defn- await-count!
  ([worker job symbol-id document-hash predicate description]
   (await-count! worker [job] job symbol-id document-hash
                 predicate description))
  ([worker lease-jobs job symbol-id document-hash predicate description]
   (let [settings (:settings worker)
         deadline (+ (now worker) (:visibility-timeout-ms settings))]
     (loop []
       (let [_ (renew-leases! worker lease-jobs)
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
             (recur))))))))

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

(defn- complete-prepared! [worker prepared]
  (let [completed-at (now worker)
        completions
        (mapv (fn [{:keys [job desired]}]
                {:job-id (:semantic.job/id job)
                 :lease-owner (:owner worker)
                 :indexed (indexed-record worker desired)
                 :completed-at completed-at})
              prepared)
        completed
        (with-graph-lock
          worker
          #(state/complete-jobs! (:graph worker) completions))]
    (mapv (fn [{:keys [job]}]
            {:status (if (contains? completed (:semantic.job/id job))
                       :completed :superseded)
             :operation (:semantic.job/operation job)})
          prepared)))

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
             {:job job :desired desired})))
       jobs))
    (catch Throwable error
      (mapv #(retry-job! worker % error) jobs))))

(def ^:private visible-attributes
  [:id :symbol-id :file-id :document-hash :model-revision
   :document-version :chunk-index :chunk-count])

(defn- desired-visible?
  [visible desired]
  (let [expected (mapv #(select-keys % visible-attributes)
                       (:chunks desired))
        actual (mapv #(select-keys % visible-attributes)
                     (filter (fn [indexed]
                               (= (:symbol-id desired)
                                  (:symbol-id indexed)))
                             visible))]
    (and (= (count expected) (count actual))
         (= (set expected) (set actual)))))

(defn- visible-documents
  [worker prepared]
  (vec
   (mapcat
    #(index/indexed-documents (:client worker) %)
    (partition-all inventory-batch-size
                   (mapv (comp :symbol-id :desired) prepared)))))

(defn- await-documents!
  [worker prepared predicate description]
  (let [jobs (mapv :job prepared)
        deadline (+ (now worker) (:visibility-timeout-ms (:settings worker)))]
    (loop []
      (renew-leases! worker jobs)
      (let [visible (visible-documents worker prepared)]
        (cond
          (predicate visible) visible
          (>= (now worker) deadline)
          (throw
           (ex-info (str "Timed out waiting for NextPlaid " description)
                    {:type :semantic/visibility-timeout
                     :retriable? true
                     :symbol-count (count prepared)
                     :visible-chunks (count visible)}))
          :else
          (do
            (sleep! worker (:visibility-poll-ms (:settings worker)))
            (recur)))))))

(defn- submit-update-batches!
  [worker jobs batches]
  (let [threads (min (count batches)
                     (:update-concurrency (:settings worker)))
        executor (Executors/newFixedThreadPool threads)]
    (try
      (renew-leases! worker jobs)
      (let [futures
            (mapv
             (fn [batch]
               (.submit executor
                        ^Callable
                        (fn []
                          (index/add-documents! (:client worker) batch))))
             batches)]
        (doseq [future futures]
          (try
            (.get future)
            (catch ExecutionException error
              (throw (.getCause error))))))
      (finally
        (.shutdown executor)
        (.awaitTermination executor 5 TimeUnit/SECONDS)))))

(defn- process-upsert-batch! [worker jobs]
  (let [prepared-results
        (mapcat #(prepare-upsert-group worker %)
                (partition-by
                 :semantic.job/file-id
                 (sort-by (juxt :semantic.job/file-id :semantic.job/id) jobs)))
        prepared (filterv :desired prepared-results)
        immediate (filterv :status prepared-results)]
    (if (empty? prepared)
      {:results immediate
       :metrics {:submitted-documents 0 :submitted-chunks 0
                 :upload-batches 0 :delete-ms 0
                 :upload-ms 0 :visibility-ms 0}}
      (try
        (let [jobs (mapv :job prepared)
              _ (renew-leases! worker jobs)
              symbols (mapv (comp :symbol-id :desired) prepared)
              delete-start (System/nanoTime)
              existing (visible-documents worker prepared)
              _ (when (seq existing)
                  (index/delete-symbols! (:client worker) symbols)
                  (await-documents! worker prepared empty?
                                    "batched replacement deletion"))
              delete-ms (long (/ (- (System/nanoTime) delete-start) 1000000))
              chunks (vec (mapcat (comp :chunks :desired) prepared))
              batches (mapv vec
                            (partition-all
                             (:update-batch-size (:settings worker)) chunks))
              upload-start (System/nanoTime)
              _ (submit-update-batches! worker jobs batches)
              upload-ms (long (/ (- (System/nanoTime) upload-start) 1000000))
              visibility-start (System/nanoTime)
              _ (await-documents!
                 worker prepared
                 #(every? (fn [{:keys [desired]}]
                            (desired-visible? % desired))
                          prepared)
                 "batched upsert visibility")
              visibility-ms
              (long (/ (- (System/nanoTime) visibility-start) 1000000))
              results
              (into immediate (complete-prepared! worker prepared))]
          {:results results
           :metrics {:submitted-documents (count prepared)
                     :submitted-chunks (count chunks)
                     :upload-batches (count batches)
                     :delete-ms delete-ms
                     :upload-ms upload-ms
                     :visibility-ms visibility-ms}})
        (catch Throwable error
          {:results
           (into immediate
                 (mapv #(retry-job! worker (:job %) error) prepared))
           :metrics {:submitted-documents 0 :submitted-chunks 0
                     :upload-batches 0 :delete-ms 0
                     :upload-ms 0 :visibility-ms 0}})))))

(defn prepare!
  "Recover state, reconcile graph changes, verify the exact model, and declare
  the project index before consuming jobs."
  [worker]
  (let [time (now worker)
        recovered (with-graph-lock
                    worker
                    #(state/recover-expired-leases!
                      (:graph worker) reconcile/provider time))
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
    (let [index-state (index/ensure-index! (:client worker))
          generation (ensure-index-generation! worker index-state)
          planned (reconcile/reconcile! (:graph worker)
                                        (:project worker)
                                        (:config worker)
                                        time)]
    (with-graph-lock
      worker
      #(state/record-watermark!
        (:graph worker)
        {:provider reconcile/provider :state :idle
         :graph-revision (:graph-revision planned)}))
      {:recovered recovered :planned planned :health health
       :generation generation})))

(defn process-once!
  "Lease and synchronously process one bounded job batch."
  [worker]
  (storage/assert-headroom! (:project worker) (:config worker)
                            :semantic-index-batch)
  (let [time (now worker)
        settings (:settings worker)
        dirty? (seq (with-graph-lock
                      worker
                      #(state/dirty-records
                        (:graph worker) reconcile/provider)))
        _ (when dirty?
            (reconcile/reconcile! (:graph worker) (:project worker)
                                  (:config worker) time))
        _ (with-graph-lock
            worker
            #(state/recover-expired-leases!
              (:graph worker) reconcile/provider time))
        jobs (with-graph-lock
               worker
               #(state/lease-jobs!
                 (:graph worker) reconcile/provider (:owner worker)
                 time (:lease-ms settings)
                 (* (:update-batch-size settings)
                    (:update-concurrency settings))))]
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
              upsert-result (process-upsert-batch! worker upserts)
              results
              (into (:results upsert-result)
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
              summary (merge
                       {:leased (count jobs)
                        :completed (get frequencies :completed 0)
                        :retried (get frequencies :pending 0)
                        :failed (get frequencies :failed 0)
                        :superseded (get frequencies :superseded 0)}
                       (:metrics upsert-result))]
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

(defn- report-progress! [worker result]
  (when (pos? (:leased result))
    (let [snapshot
          (swap! (:progress worker)
                 (fn [current]
                   (-> current
                       (update :leased + (:leased result 0))
                       (update :completed + (:completed result 0))
                       (update :retried + (:retried result 0))
                       (update :failed + (:failed result 0))
                       (update :submitted-documents +
                               (:submitted-documents result 0))
                       (update :submitted-chunks +
                               (:submitted-chunks result 0))
                       (update :upload-batches + (:upload-batches result 0))
                       (update :delete-ms + (:delete-ms result 0))
                       (update :upload-ms + (:upload-ms result 0))
                       (update :visibility-ms + (:visibility-ms result 0)))))
          elapsed-ms (max 1 (- (now worker) (:started-at snapshot)))
          event (assoc snapshot
                       :phase :semantic-indexing
                       :elapsed-ms elapsed-ms
                       :documents-per-minute
                       (* 60000.0 (/ (:completed snapshot) elapsed-ms)))]
      (when-let [progress-fn (:progress-fn worker)]
        (progress-fn event)))))

(defn run!
  "Prepare and consume jobs until stop! is requested."
  [worker]
  (prepare! worker)
  (while (not @(:stop? worker))
    (let [result (process-once! worker)]
      (report-progress! worker result)
      (when (zero? (:leased result))
        (sleep! worker (:idle-poll-ms (:settings worker))))))
  :stopped)

(defn stop! [worker]
  (reset! (:stop? worker) true)
  nil)

(defn create
  ([graph project config client]
   (create graph project config client {}))
  ([graph project config client
    {:keys [owner now-fn sleep-fn progress-fn settings]}]
   (let [now-fn (or now-fn #(System/currentTimeMillis))]
    {:graph graph
    :project project
    :config config
    :settings (or settings (get-in config [:semantic :lateon-code]))
    :client client
    :owner (or owner (str (UUID/randomUUID)))
    :now-fn now-fn
    :sleep-fn (or sleep-fn #(Thread/sleep %))
    :progress-fn progress-fn
    :progress (atom {:started-at (now-fn)
                     :leased 0 :completed 0 :retried 0 :failed 0
                     :submitted-documents 0 :submitted-chunks 0
                     :upload-batches 0 :delete-ms 0
                     :upload-ms 0 :visibility-ms 0})
    :stop? (atom false)})))
