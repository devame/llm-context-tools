(ns llm-context.semantic.worker
  "Single-writer background consumer for durable LateOn jobs."
  (:refer-clojure :exclude [run!])
  (:require [llm-context.graph.read :as graph-read]
            [llm-context.semantic.document :as document]
            [llm-context.semantic.ingestion-plan :as ingestion-plan]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.progress :as semantic-progress]
            [llm-context.semantic.reconcile :as reconcile]
            [llm-context.semantic.state :as state]
            [llm-context.storage :as storage]
            [llm-context.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]
           [java.util.concurrent Callable ExecutionException
            ExecutorCompletionService Executors TimeUnit]))

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

(defn provider-failure?
  "True when an error describes provider/runtime availability rather than one
  poison document. These failures open the supervisor circuit and must not
  exhaust per-document retry budgets."
  [error]
  (let [{:keys [type status]} (ex-data error)]
    (or (contains? #{:next-plaid/transport-error
                     :semantic/not-ready
                     :semantic/visibility-timeout
                     :semantic/runtime-exited
                     :semantic/runtime-timeout
                     :accelerator/runtime-unavailable}
                   type)
        (and (= :next-plaid/api-error type)
             (or (= 429 status) (<= 500 (long (or status 0)) 599))))))

(defn provider-backpressure?
  "True for provider responses classified as bounded queue saturation."
  [error]
  (let [{:keys [type status]} (ex-data error)]
    (and (= :next-plaid/api-error type)
         (contains? #{429 503} status))))

(defn- record-provider-backpressure! [worker error]
  (let [time (now worker)
        cooldown (long (get-in worker [:settings :backpressure-cooldown-ms]))]
    (swap! (:backpressure worker)
           (fn [current]
             (-> current
                 (assoc :limited? true
                        :cooldown-until (+ time cooldown)
                        :successful-groups 0
                        :last-reduction-at time
                        :last-reduction-reason
                        (or (:code (ex-data error))
                            (:status (ex-data error))
                            :provider-backpressure))
                 (update :count (fnil inc 0)))))))

(defn- record-provider-success! [worker]
  (let [time (now worker)
        required
        (long (get-in worker [:settings :backpressure-recovery-successes]))]
    (swap! (:backpressure worker)
           (fn [{:keys [limited? cooldown-until successful-groups] :as current}]
             (if (and limited? (>= time (long cooldown-until)))
               (let [successes (inc (long successful-groups))]
                 (if (>= successes required)
                   (assoc current :limited? false :successful-groups successes)
                   (assoc current :successful-groups successes)))
               current)))))

(defn- effective-request-concurrency [worker configured]
  (if (:limited? @(:backpressure worker))
    1
    configured))

(defn- pending-operation-mix [worker]
  (with-graph-lock
    worker
    #(graph-read/semantic-pending-operation-counts
      (store/database (:graph worker)) reconcile/provider)))

(defn- ingestion-plan [worker]
  (let [settings (:settings worker)
        operation-mix (pending-operation-mix worker)
        pending (reduce + 0 (vals operation-mix))
        selected
        (ingestion-plan/plan
         {:pending-symbol-jobs pending
          ;; Until documents are rendered, one document per symbol is the
          ;; deterministic lower-bound estimate. Runtime submission still
          ;; enforces the exact rendered document bound.
          :pending-provider-documents-estimate pending
          :operation-mix operation-mix
          :accelerator (:accelerator settings)
          :provider-version (:next-plaid-version settings)
          :configured-request-batch (:update-batch-size settings)
          :configured-request-concurrency (:update-concurrency settings)
          :configured-max-inflight-documents
          (* (:update-batch-size settings) (:update-concurrency settings))
          :profile (:ingestion-profile settings)
          :previous-profile @(:active-profile worker)
          :cold-ingestion (:cold-ingestion settings)})
        effective
        (effective-request-concurrency
         worker (:request-concurrency-limit selected))
        selected (assoc selected
                        :request-concurrency-effective effective
                        :backpressure @(:backpressure worker))]
    (reset! (:active-profile worker) (:profile selected))
    selected))

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
        provider-failure? (provider-failure? error)
        backpressure? (provider-backpressure? error)
        max-attempts (cond
                       provider-failure? Long/MAX_VALUE
                       retriable? (:max-attempts settings)
                       :else 1)
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
             :max-attempts max-attempts
             :consume-attempt? (not provider-failure?)}))]
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
     :provider-failure? provider-failure?
     :provider-backpressure? backpressure?
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
  [worker jobs batches concurrency max-inflight]
  (let [threads (min (count batches) concurrency)
        executor (Executors/newFixedThreadPool threads)
        completions (ExecutorCompletionService. executor)
        submit! (fn [batch]
                  (.submit completions
                           ^Callable
                           (fn []
                             (index/add-documents! (:client worker) batch))))]
    (try
      (renew-leases! worker jobs)
      ;; Keep only active requests in the executor. As each request completes,
      ;; submit the next stable batch immediately instead of retaining the full
      ;; cold-ingestion lease in an unbounded executor queue.
      (let [largest-batch (reduce max 0 (map count batches))]
        (when (> (* threads largest-batch) max-inflight)
          (throw
           (ex-info "Semantic provider requests exceed their in-flight bound"
                    {:type :semantic/inflight-bound-exceeded
                     :request-concurrency threads
                     :largest-provider-request largest-batch
                     :max-inflight-provider-documents max-inflight}))))
      (let [[initial remaining] (split-at threads batches)]
        (doseq [batch initial]
          (submit! batch))
        (loop [remaining remaining
               in-flight (count initial)]
          (when (pos? in-flight)
            (let [future (.take completions)]
              (try
                (.get future)
                (catch ExecutionException error
                  (throw (.getCause error))))
              (if-let [batch (first remaining)]
                (do
                  (submit! batch)
                  (recur (rest remaining) in-flight))
                (recur remaining (dec in-flight)))))))
      (let [job-ids (mapv :semantic.job/id jobs)
            accepted-at (now worker)
            accepted
            (with-graph-lock
              worker
              #(state/mark-jobs-accepted!
                (:graph worker) job-ids (:owner worker) accepted-at))]
        (when-not (= (set job-ids) accepted)
          (throw
           (ex-info
            "Semantic provider accepted work after one or more leases changed"
            {:type :semantic/acceptance-lease-lost
             :retriable? true
             :submitted (count job-ids)
             :accepted (count accepted)})))
        (count accepted))
      (finally
        (.shutdown executor)
        (.awaitTermination executor 5 TimeUnit/SECONDS)))))

(defn- utf8-size [value]
  (alength (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- process-upsert-batch! [worker jobs plan]
  (let [prepare-start (System/nanoTime)
        prepared-results
        (mapcat #(prepare-upsert-group worker %)
                (partition-by
                 :semantic.job/file-id
                 (sort-by (juxt :semantic.job/file-id :semantic.job/id) jobs)))
        prepared (filterv :desired prepared-results)
        immediate (filterv :status prepared-results)
        prepared-chunks (vec (mapcat (comp :chunks :desired) prepared))
        prepared-bytes (reduce + 0 (map (comp utf8-size :text)
                                         prepared-chunks))
        prepare-ms (long (/ (- (System/nanoTime) prepare-start) 1000000))
        base-metrics
        {:prepared-symbol-jobs (count prepared)
         :prepared-provider-documents (count prepared-chunks)
         :prepared-text-bytes prepared-bytes
         :request-concurrency-effective
         (:request-concurrency-effective plan)}]
    (if (empty? prepared)
      {:results immediate
       :metrics (merge base-metrics
                       {:submitted-documents 0 :submitted-chunks 0
                        :accepted-documents 0 :reused-documents 0
                        :upload-batches 0 :delete-ms 0 :upload-ms 0
                        :prepare-ms prepare-ms :submit-ms 0
                        :visibility-ms 0 :completion-ms 0
                        :request-count 0 :request-provider-documents 0
                        :request-text-bytes 0 :accepted-symbol-jobs 0
                        :visible-symbol-jobs 0 :reused-symbol-jobs 0
                        :provider-backpressure-count 0
                        :provider-retry-count 0})}
      (try
        (let [jobs (mapv :job prepared)
              _ (renew-leases! worker jobs)
              visible (visible-documents worker prepared)
              reusable (filterv #(desired-visible? visible (:desired %))
                                prepared)
              replacements (filterv #(not (desired-visible?
                                            visible (:desired %)))
                                    prepared)
              replacement-jobs (mapv :job replacements)
              symbols (mapv (comp :symbol-id :desired) replacements)
              delete-start (System/nanoTime)
              existing-symbols (set (map :symbol-id visible))
              _ (when (some existing-symbols symbols)
                  (index/delete-symbols! (:client worker) symbols)
                  (await-documents! worker replacements empty?
                                    "batched replacement deletion"))
              delete-ms (long (/ (- (System/nanoTime) delete-start) 1000000))
              chunks (vec (mapcat (comp :chunks :desired) replacements))
              request-bytes (reduce + 0 (map (comp utf8-size :text) chunks))
              batches (mapv vec
                            (partition-all
                             (:request-provider-document-limit plan) chunks))
              upload-start (System/nanoTime)
              accepted-documents (if (seq batches)
                                   (submit-update-batches!
                                    worker replacement-jobs batches
                                    (:request-concurrency-effective plan)
                                    (:max-inflight-provider-documents plan))
                                   0)
              upload-ms (long (/ (- (System/nanoTime) upload-start) 1000000))
              visibility-start (System/nanoTime)
              _ (when (seq replacements)
                  (await-documents!
                   worker replacements
                   #(every? (fn [{:keys [desired]}]
                              (desired-visible? % desired))
                            replacements)
                   "batched upsert visibility"))
              visibility-ms
              (long (/ (- (System/nanoTime) visibility-start) 1000000))
              completion-start (System/nanoTime)
              results (into immediate (complete-prepared! worker prepared))
              completion-ms
              (long (/ (- (System/nanoTime) completion-start) 1000000))]
          {:results results
           :metrics
           (merge base-metrics
                  {:submitted-documents (count replacements)
                   :accepted-documents accepted-documents
                   :reused-documents (count reusable)
                   :submitted-chunks (count chunks)
                   :upload-batches (count batches)
                   :delete-ms delete-ms
                   :upload-ms upload-ms
                   :prepare-ms prepare-ms
                   :submit-ms upload-ms
                   :visibility-ms visibility-ms
                   :completion-ms completion-ms
                   :request-count (count batches)
                   :request-provider-documents (count chunks)
                   :request-text-bytes request-bytes
                   :accepted-symbol-jobs accepted-documents
                   :visible-symbol-jobs (count prepared)
                   :reused-symbol-jobs (count reusable)
                   :provider-backpressure-count 0
                   :provider-retry-count 0})})
        (catch Throwable error
          (let [provider-failure? (provider-failure? error)
                backpressure? (provider-backpressure? error)]
            {:results
             (into immediate
                   (mapv #(retry-job! worker (:job %) error) prepared))
             :metrics
             (merge base-metrics
                    {:submitted-documents 0 :submitted-chunks 0
                     :accepted-documents 0 :reused-documents 0
                     :upload-batches 0 :delete-ms 0 :upload-ms 0
                     :prepare-ms prepare-ms :submit-ms 0
                     :visibility-ms 0 :completion-ms 0
                     :request-count 0 :request-provider-documents 0
                     :request-text-bytes 0 :accepted-symbol-jobs 0
                     :visible-symbol-jobs 0 :reused-symbol-jobs 0
                     :provider-backpressure-count (if backpressure? 1 0)
                     :provider-retry-count
                     (if provider-failure? (count prepared) 0)})}))))))

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
  (let [storage-snapshot
        (if-let [guard (:storage-guard worker)]
          (storage/assert-operation-safe! guard)
          (storage/assert-headroom! (:project worker) (:config worker)
                                    :semantic-index-batch
                                    (get-in worker
                                            [:settings :index-path])))
        time (now worker)
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
        plan (ingestion-plan worker)
        jobs (with-graph-lock
               worker
               #(state/lease-jobs!
                 (:graph worker) reconcile/provider (:owner worker)
                 time (:lease-ms settings)
                 (:lease-symbol-limit plan)))]
    (if (empty? jobs)
      {:leased 0 :completed 0 :retried 0 :failed 0 :superseded 0
       :leased-symbol-jobs 0
       :request-concurrency-effective
       (:request-concurrency-effective plan)
       :ingestion-plan plan}
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
              upsert-result (process-upsert-batch! worker upserts plan)
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
              backpressure-error
              (some #(when (:provider-backpressure? %) (:error %)) results)
              _ (when backpressure-error
                  (record-provider-backpressure! worker backpressure-error))
              _ (when (and (nil? backpressure-error)
                           (pos? (get-in upsert-result
                                         [:metrics :request-count] 0)))
                  (record-provider-success! worker))
              provider-failure
              (some #(when (and (:provider-failure? %)
                                (not (:provider-backpressure? %)))
                       (:error %))
                    results)
              _ (when provider-failure
                  (throw
                   (ex-info "Semantic provider became unavailable; jobs were returned to pending"
                            {:type :semantic/provider-unavailable
                             :retriable? true}
                            provider-failure)))
              frequencies (frequencies (map :status results))
              effective-plan
              (cond-> (assoc plan :backpressure @(:backpressure worker))
                backpressure-error
                (assoc :request-concurrency-effective 1))
              summary (-> (merge
                           {:leased (count jobs)
                            :leased-symbol-jobs (count jobs)
                            :completed (get frequencies :completed 0)
                            :retried (get frequencies :pending 0)
                            :failed (get frequencies :failed 0)
                            :superseded (get frequencies :superseded 0)}
                           (:metrics upsert-result))
                          (assoc
                           :ingestion-plan effective-plan
                           :request-concurrency-effective
                           (:request-concurrency-effective effective-plan)))]
          (with-graph-lock
            worker
            #(state/record-watermark!
              (:graph worker)
              (cond
                backpressure-error
                {:provider reconcile/provider
                 :state :degraded
                 :last-error-at (now worker)
                 :last-error "NextPlaid ingestion queue is applying backpressure"
                 :graph-revision
                 (document/graph-revision
                  (store/database (:graph worker)))}

                (pos? (:failed summary))
                {:provider reconcile/provider
                 :state :degraded
                 :last-error-at (now worker)
                 :last-error "One or more semantic jobs exhausted retries"
                 :graph-revision
                 (document/graph-revision
                  (store/database (:graph worker)))}
                :else
                {:provider reconcile/provider
                 :state :ready
                 :last-success-at (now worker)
                 :graph-revision
                 (document/graph-revision
                  (store/database (:graph worker)))})))
          (cond-> summary
            (:sampled? storage-snapshot)
            (assoc :storage storage-snapshot)))))))

(defn- report-progress! [worker result]
  (let [time (now worker)
        before @(:progress worker)
        snapshot (swap! (:progress worker)
                        semantic-progress/record result time)
        event (cond-> (assoc snapshot :phase :semantic-indexing)
                (:storage result) (assoc :storage (:storage result)))
        rate-changed?
        (not= (:recent-completed-symbols-per-second before)
              (:recent-completed-symbols-per-second snapshot))]
    (when (and (:progress-fn worker)
               (or (pos? (:leased result)) rate-changed?))
      ((:progress-fn worker) event))))

(defn- assert-provider-health! [worker]
  (let [time (now worker)
        interval (max 1000 (long (or (get-in worker [:settings :health-timeout-ms])
                                     2000)))]
    (when (>= (- time @(:last-health-at worker)) interval)
      (let [health (index/index-health (:client worker))]
        (reset! (:last-health-at worker) time)
        (when-not (:ready? health)
          (throw
           (ex-info "NextPlaid became unhealthy"
                    {:type :semantic/provider-unavailable
                     :retriable? true
                     :health (dissoc health :raw)})))))))

(defn run!
  "Prepare and consume jobs until stop! is requested."
  [worker]
  (prepare! worker)
  (while (not @(:stop? worker))
    (assert-provider-health! worker)
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
    :last-health-at (atom 0)
    :storage-guard
    (when (and project config)
      (storage/operation-guard project config :semantic-indexing
                               #{:semantic-index}))
    :active-profile (atom :steady)
    :backpressure
    (atom {:limited? false
           :cooldown-until 0
           :successful-groups 0
           :count 0
           :last-reduction-at nil
           :last-reduction-reason nil})
    :progress (atom (semantic-progress/initial (now-fn)))
    :stop? (atom false)})))
