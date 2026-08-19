(ns llm-context.service.server
  (:require [clojure.edn :as edn]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.context :as context]
            [llm-context.export :as export]
            [llm-context.health :as health]
            [llm-context.intent :as intent]
            [llm-context.intent.reranker :as candidate-reranker]
            [llm-context.intent.router :as intent-router]
            [llm-context.query :as query]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.runtime :as semantic-runtime]
            [llm-context.semantic.state :as semantic-state]
            [llm-context.semantic.worker :as semantic-worker]
            [llm-context.service.client :as client]
            [llm-context.service.contract :as service-contract]
            [llm-context.service.lifecycle :as lifecycle]
            [llm-context.service.progress :as analysis-progress]
            [llm-context.service.transport :as transport]
            [llm-context.service.watcher :as watcher]
            [llm-context.store :as store])
  (:import [java.io PushbackReader]
           [java.lang ProcessHandle]
           [java.net SocketException]
           [java.nio.file Files]
           [java.util UUID WeakHashMap]
           [java.util.concurrent ArrayBlockingQueue RejectedExecutionException
            ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit]))

(defn- query-search-term [args]
  (:term (query/parse-search-args args)))

(defn- query-search-options [args]
  (query/parse-search-args args))

(defn- query-value [graph semantic-client settings subcommand args]
  (let [argument (fn []
                   (or (first args)
                       (throw (ex-info (str "query " subcommand " requires an argument")
                                       {:exit-code 2}))))]
   (case subcommand
    "stats" (query/stats graph)
    "find-symbol" (query/find-symbol graph (argument))
    "search"
    (let [{:keys [term mode source-preference intent-rerank?
                  semantic-timeout-ms]} (query-search-options args)]
      (query/search-explain graph semantic-client settings term
                            {:mode mode :source-preference source-preference
                             :intent-rerank? intent-rerank?
                             :semantic-timeout-ms semantic-timeout-ms}))
    "callers" (query/callers graph (argument))
    "callees" (query/callees-command graph args)
    "trace" (query/trace-command graph settings args)
    "entry-points" (query/entry-points graph)
    "effects" (query/effects graph)
    "unresolved" (query/unresolved-command graph args)
    ("topics" "registrations" "dispatchers" "subscribers"
     "state-readers" "state-writers")
    (query/topics-command graph subcommand args)
    (throw (ex-info (str "Unknown query: " subcommand) {:exit-code 2})))))

(defn- runtime-view [runtime-state]
  (let [selected-runtime (select-keys runtime-state
                                      [:status :reason :detail :endpoint :log-path
                                       :inference :runtime-diagnostic :recovery
                                       :worker-status :worker-detail :worker-progress
                                       :recovery-attempt :last-recovery-at
                                       :watcher-status :watcher-detail
                                       :query-router-status :query-router-detail
                                       :query-router-recovery
                                       :query-router-inference
                                       :candidate-reranker-status
                                       :candidate-reranker-detail])
        runtime (if (:runtime-diagnostic selected-runtime)
                  selected-runtime
                  (if-let [diagnostic (semantic-runtime/runtime-diagnostic
                                       selected-runtime)]
                    (assoc selected-runtime :runtime-diagnostic diagnostic)
                    selected-runtime))]
    (cond-> runtime
      (:log-path runtime) (update :log-path str)
      (nil? (:inference runtime)) (dissoc :inference)
      (nil? (:query-router-inference runtime))
      (dissoc :query-router-inference))))

(defn- stall-window-ms [runtime-state]
  (let [settings (:semantic-settings runtime-state)]
    (max 30000
         (+ (long (or (:update-timeout-ms settings) 0))
            (long (or (:visibility-timeout-ms settings) 0))))))

(defn- with-health [status runtime-state]
  (let [snapshot (health/semantic-health status (System/currentTimeMillis)
                                         (stall-window-ms runtime-state))
        snapshot (if-let [project (:project runtime-state)]
                   (health/persist! project snapshot)
                   snapshot)
        result (assoc status :health snapshot)]
    result))

(defn- persist-runtime-health! [runtime-state]
  (when-let [project (:project runtime-state)]
    (try
      (let [baseline (or (:last-semantic-status runtime-state)
                         {:indexed 0 :desired 0 :pending 0 :leased 0
                          :failed 0 :dirty 0 :completeness :unknown})
            status (assoc baseline
                          :runtime (runtime-view runtime-state)
                          :analysis-progress (:analysis-progress runtime-state))]
        (health/persist!
         project
         (health/semantic-health status (System/currentTimeMillis)
                                 (stall-window-ms runtime-state))))
      (catch Throwable error
        (binding [*out* *err*]
          (println "Unable to persist project health:" (.getMessage error)))))))

(defn- semantic-status [graph runtime-state]
  (let [runtime (runtime-view runtime-state)
        summary (semantic-state/semantic-summary
                 graph semantic-reconcile/provider
                 (System/currentTimeMillis))]
    (with-health
      (assoc summary
             :graph-state :ready
             :service-state :running
             :availability
             (if (= :ready (:status runtime)) :available :unavailable)
             :analysis-progress (:analysis-progress runtime-state)
             :runtime runtime)
      runtime-state)))

(defn- updating-semantic-status [runtime-state]
  (let [runtime (runtime-view runtime-state)
        previous (:last-semantic-status runtime-state)
        baseline (or previous
                     {:indexed 0 :indexed-records 0 :desired 0
                      :pending 0 :leased 0 :accepted 0 :failed 0 :dirty 0
                      :coverage-percent 0.0 :completeness :unknown
                      :aggregate-analysis
                      {:aggregates 0 :memberships 0
                       :semantic-documents :unknown}})]
    (with-health
      (assoc baseline
             :graph-state :updating
             :service-state :running
             :availability
             (if (= :ready (:status runtime)) :available :unavailable)
             :analysis-progress (:analysis-progress runtime-state)
             :runtime runtime)
      runtime-state)))

(defn- current-semantic-status! [graph runtime-state]
  (let [status (semantic-status graph @runtime-state)]
    (swap! runtime-state assoc :last-semantic-status status)
    status))

(defn- service-progress! [event]
  (println
   (pr-str
    (assoc event
           :event :analysis-progress
           :timestamp (str (java.time.Instant/now)))))
  (flush))

(defn- with-graph-write [graph generation operation]
  (locking graph
    (swap! generation inc)
    (try
      (operation)
      (finally
        (swap! generation inc)))))

(defonce ^:private analysis-mutexes (WeakHashMap.))

(defn- analysis-mutex [graph]
  (locking analysis-mutexes
    (or (.get analysis-mutexes graph)
        (let [mutex (Object.)]
          (.put analysis-mutexes graph mutex)
          mutex))))

(defn- analyze!
  ([graph generation project settings force-full?]
   (analyze! graph generation project settings force-full? service-progress!))
  ([graph generation project settings force-full? progress-fn]
   ;; Finalization reads and writes semantic operational state. Keep it inside
   ;; the same operation mutex as preparation and graph commit so shutdown and
   ;; a second watcher event cannot close or reuse the graph midway through it.
   (locking (analysis-mutex graph)
     (let [full? (or force-full?
                     (not= :ready (store/graph-state graph))
                     (not (incremental/index-present? graph)))
           incremental-prepared
           (when-not full?
             (incremental/prepare-current
              graph project settings progress-fn))
           prepared
           (if (:complete-result incremental-prepared)
             incremental-prepared
             (let [candidate (if full?
                               (full/prepare-current
                                project settings progress-fn :full)
                               incremental-prepared)]
               (if (:stale? candidate)
                 candidate
                 {:full? full?
                  :result
                  (with-graph-write
                    graph generation
                    #(if full?
                       (full/commit-candidate!
                        graph project settings candidate progress-fn)
                       (incremental/commit-candidate!
                        graph project settings candidate)))})))]
       (cond
         (:complete-result prepared) (:complete-result prepared)
         (:stale? prepared) prepared
         (:full? prepared)
         (full/finish-candidate!
          graph project settings (:result prepared) progress-fn)
         :else
         (incremental/finish-candidate!
          graph project settings (:result prepared)))))))

(defn- run-analysis! [graph generation project settings force-full? runtime-state]
  (let [progress-state (:progress-state @runtime-state)
        operation (if force-full? :full-analysis :incremental-analysis)
        _ (when progress-state
            (let [snapshot (analysis-progress/begin! progress-state operation)]
              (swap! runtime-state assoc :analysis-progress snapshot)))
        progress-fn
        (fn [event]
          (when progress-state
            (swap! runtime-state assoc
                   :analysis-progress
                   (analysis-progress/record! progress-state event)))
          (service-progress! event))]
    (try
      (let [result (analyze! graph generation project settings force-full?
                             progress-fn)]
        (when progress-state
          (swap! runtime-state assoc
                 :analysis-progress
                 (analysis-progress/complete! progress-state result)))
        result)
      (catch Throwable error
        (when progress-state
          (swap! runtime-state assoc
                 :analysis-progress
                 (analysis-progress/fail! progress-state error)))
        (throw error)))))

(defn- read-consistently
  "Run a multi-query read without acquiring the graph monitor. A concurrent
  graph write invalidates the result, which is discarded and retried."
  [graph generation validate? operation]
  (loop [attempt 0]
    (let [before @generation]
      (when (odd? before)
        (throw (ex-info "The project graph is being updated; retry shortly"
                        {:exit-code 1 :type :graph/update-in-progress})))
      (let [outcome
            (try
              (when validate?
                (store/assert-query-compatible! graph))
              {:value (operation graph)}
              (catch Throwable error {:error error}))
            after @generation]
        (if (= before after)
          (if-let [error (:error outcome)]
            (throw error)
            (:value outcome))
          (if (< attempt 2)
            (recur (inc attempt))
            (throw
             (ex-info "The project graph changed repeatedly during the read"
                      {:exit-code 1 :type :graph/read-retry-exhausted}))))))))

(defn- advisory-attempt [router query]
  (if router
    (try
      (intent-router/classify router query)
      (catch Throwable error
        {:provider :mixedbread-32m :status :failed
         :detail (.getMessage error)}))
    {:provider :mixedbread-32m :status :unavailable
     :reason :router-not-ready}))

(defn- dispatch [project settings graph generation runtime-state request]
  (let [runtime @runtime-state]
    (case (:op request)
    :ping :pong
    :service-info (merge (service-contract/runtime-identity)
                         {:pid (.pid (ProcessHandle/current))})
    :analyze (run-analysis! graph generation project settings (:full? request)
                            runtime-state)
    :query
    (if (= "search" (:subcommand request))
      (let [{:keys [term mode source-preference intent-rerank?
                    semantic-timeout-ms]}
            (query-search-options (:args request))
            plan (intent/analyze
                  term {:seed-mode (if intent-rerank?
                                     (get-in settings [:context
                                                       :intent-seed-mode])
                                     :single)
                        :default-max-seeds
                        (get-in settings [:context :intent-max-seeds])
                        :default-candidate-count
                        (get-in settings [:semantic :lateon-code
                                          :candidate-count])
                        :multi-candidate-count
                        (get-in settings [:context :intent-candidate-count])})
            semantic-future
            (future
              (if (and (= :hybrid mode) (not intent-rerank?)
                       (nil? semantic-timeout-ms))
                ;; Keep the historical arity on the default path for callers
                ;; that instrument the resident service.
                (query/semantic-search-attempt (:client runtime) settings term)
                (query/semantic-search-attempt
                 (:client runtime) settings term
                 {:mode mode :semantic-timeout-ms semantic-timeout-ms
                  :candidate-count (:candidate-count plan)})))
            advisory-future
            (when intent-rerank?
              (future (advisory-attempt (:query-router runtime) term)))
            semantic-attempt @semantic-future
            advisory (when advisory-future @advisory-future)]
        (read-consistently
         graph generation true
         #(query/search-explain-with-attempt
           % settings term semantic-attempt
           {:mode mode :source-preference source-preference
            :intent-rerank? intent-rerank?
            :intent-advisory advisory
            :candidate-reranker (:candidate-reranker runtime)
            :seed-mode (when intent-rerank?
                         (get-in settings [:context :intent-seed-mode]))})))
      (read-consistently
       graph generation true
       #(query-value % (:client runtime) settings
                     (:subcommand request) (:args request))))
    :context
    (let [options (cond-> (:options request)
                    (and (get-in request [:options :intent?])
                         (nil? (get-in request [:options :source-preference])))
                    (assoc :source-preference
                           (get-in settings [:context :intent-source-preference]))
                    (and (get-in request [:options :intent?])
                         (nil? (get-in request [:options :seed-mode])))
                    (assoc :seed-mode
                           (get-in settings [:context :intent-seed-mode]))
                    (and (get-in request [:options :intent?])
                         (nil? (get-in request [:options :max-seeds])))
                    (assoc :max-seeds
                           (get-in settings [:context :intent-max-seeds]))
                    (and (get-in request [:options :intent?])
                         (nil? (get-in request [:options :intent-rerank?])))
                    (assoc :intent-rerank?
                           (get-in settings [:context :intent-rerank])))
          packet
          (if (:intent? options)
            (let [term (:focus options)
                  plan (intent/analyze
                        term {:seed-mode (:seed-mode options)
                              :max-seeds (:max-seeds options)
                              :default-max-seeds
                              (get-in settings [:context :intent-max-seeds])
                              :default-candidate-count
                              (get-in settings [:semantic :lateon-code
                                                :candidate-count])
                              :multi-candidate-count
                              (get-in settings [:context
                                                :intent-candidate-count])})
                  semantic-future
                  (future
                    (query/semantic-search-attempt
                     (:client runtime) settings term
                     {:mode :hybrid
                      :semantic-timeout-ms (:semantic-timeout-ms options)
                      :candidate-count (:candidate-count plan)}))
                  advisory-future
                  (when (:intent-rerank? options)
                    (future
                      (advisory-attempt (:query-router runtime) term)))
                  semantic-attempt @semantic-future
                  advisory (when advisory-future @advisory-future)
                  packet
                  (read-consistently
                   graph generation true
                   (fn [view]
                     (let [search
                           (query/search-explain-with-attempt
                            view settings term semantic-attempt
                            {:source-preference (:source-preference options)
                             :intent-rerank? (:intent-rerank? options)
                             :intent-advisory advisory
                             :candidate-reranker
                             (:candidate-reranker runtime)
                             :seed-mode (:seed-mode options)
                             :max-seeds (:max-seeds options)})
                           resolution
                           (context/resolve-intent-focus term search)]
                       (context/build-from-seeds view options resolution))))]
              packet)
            (read-consistently graph generation true
                               #(context/build % options)))]
      (if (= :markdown (:format options))
        (context/markdown packet)
        packet))
    :export (read-consistently graph generation true
                               #(export/render % (:format request)))
    :semantic-status
    (if (odd? @generation)
      (updating-semantic-status runtime)
      (read-consistently graph generation false
                         #(current-semantic-status! % runtime-state)))
    :semantic-failures
    (read-consistently graph generation false
                       #(semantic-state/failure-records
                         % semantic-reconcile/provider))
    :semantic-dirty
    (read-consistently graph generation false
                       #(semantic-state/dirty-details
                         % semantic-reconcile/provider))
    :semantic-retry-failed
    (with-graph-write
      graph generation
         #(do
         (semantic-reconcile/retry-failed! graph project settings)
         (current-semantic-status! graph runtime-state)))
    :semantic-sync
    (do
      ;; An explicit sync is also the operator's repair action for exhausted
      ;; jobs. Marking is a short mutation; the worker performs document
      ;; planning and source reads asynchronously outside request latency and
      ;; the project graph monitor.
      (with-graph-write graph generation
                        #(semantic-reconcile/mark-full! graph))
      (read-consistently graph generation false
                         #(current-semantic-status! % runtime-state)))
    :maintenance-compact-copy
    (with-graph-write
      graph generation
      #(store/compact-copy! graph
                            (java.nio.file.Paths/get
                             (:destination request)
                             (make-array String 0))))
    :stop :stopping
    (throw (ex-info (str "Unknown service operation: " (:op request))
                    {:exit-code 2})))))

(defn- handle! [socket token project settings graph generation runtime-state]
  (with-open [socket socket
              reader (PushbackReader. (java.io.InputStreamReader.
                                       (transport/input-stream socket)))
              writer (java.io.PrintWriter.
                      (transport/output-stream socket) true)]
    (let [request (edn/read {:eof nil} reader)
          response
          (try
            (when-not (= token (:token request))
              (throw (ex-info "Invalid service token" {:exit-code 2})))
            {:ok true :value
             (dispatch project settings graph generation runtime-state request)}
            (catch Throwable error
              (cond-> {:ok false :error (.getMessage error)
                       :exit-code (or (:exit-code (ex-data error)) 1)}
                (:type (ex-data error))
                (assoc :type (:type (ex-data error))))))]
      (.println writer (pr-str response))
      (and (:ok response) (= :stop (:op request))))))

(defn- reject-busy! [socket]
  (with-open [socket socket
              writer (java.io.PrintWriter.
                      (transport/output-stream socket) true)]
    (.println writer
              (pr-str {:ok false
                       :error "Project service is busy; retry shortly"
                       :exit-code 1
                       :type :service/busy}))))

(defn- request-executor [settings]
  (let [thread-number (atom 0)
        thread-factory
        (reify java.util.concurrent.ThreadFactory
          (newThread [_ runnable]
            (doto (Thread. runnable
                           (str "llm-context-request-" (swap! thread-number inc)))
              (.setDaemon true))))]
    (ThreadPoolExecutor.
     (int (get-in settings [:service :request-threads]))
     (int (get-in settings [:service :request-threads]))
     0 TimeUnit/MILLISECONDS
     (ArrayBlockingQueue.
      (int (get-in settings [:service :request-queue-capacity])))
     thread-factory
     (ThreadPoolExecutor$AbortPolicy.))))

(defn- acquire-service-lock [project]
  (lifecycle/acquire! project))

(defn- supervisor-delay-ms [settings attempt]
  (min (long (:retry-max-ms settings))
       (* (long (:retry-base-ms settings))
          (bit-shift-left 1 (min 10 (max 0 attempt))))))

(defn- sleep-while-running! [running delay-ms]
  (let [deadline (+ (System/currentTimeMillis) delay-ms)]
    (loop []
      (when (and @running (< (System/currentTimeMillis) deadline))
        (Thread/sleep (min 250 (- deadline (System/currentTimeMillis))))
        (recur)))))

(defn- semantic-worker-settings [settings runtime]
  (let [lateon (get-in settings [:semantic :lateon-code])]
    (assoc lateon
           :update-concurrency
           (or (get-in runtime [:inference :update-concurrency])
               (:update-concurrency lateon)))))

(defn- retriable-supervisor-error? [error]
  (let [data (ex-data error)]
    (or (= true (:retriable? data))
        (= :store/insufficient-space (:type data))
        (instance? java.io.IOException error))))

(defn- run-semantic-supervisor!
  [graph project settings runtime-state worker-state running runtime-factory]
  (let [lateon (get-in settings [:semantic :lateon-code])]
    (loop [attempt 0 initial? true]
      (when @running
        (swap! runtime-state
               (fn [current]
                 (-> current
                     (dissoc :client :process :endpoint :log-path :inference
                             :runtime-diagnostic :recovery :reason :detail)
                     (merge
                      {:status (if initial? :starting :recovering)
                       :worker-status (if initial? :starting :recovering)
                       :recovery-attempt attempt
                       :last-recovery-at (when-not initial?
                                           (System/currentTimeMillis))}))))
        (let [runtime-attempt
              (try {:runtime (runtime-factory project settings)}
                   (catch Throwable error {:error error}))]
          (if-let [error (:error runtime-attempt)]
            (let [retriable? (retriable-supervisor-error? error)]
              (swap! runtime-state merge
                     {:status (if retriable? :recovering :failed)
                      :reason :startup-failed
                      :detail (.getMessage error)
                      :worker-status (if retriable? :recovering :failed)
                      :worker-detail (.getMessage error)})
              (semantic-state/record-watermark!
               graph {:provider semantic-reconcile/provider
                      :state (if retriable? :degraded :failed)
                      :last-error-at (System/currentTimeMillis)
                      :last-error (.getMessage error)})
              (when retriable?
                (sleep-while-running! running
                                      (supervisor-delay-ms lateon attempt))
                (recur (inc attempt) false)))
            (let [runtime (:runtime runtime-attempt)]
              (swap! runtime-state merge
                     (assoc runtime :worker-status
                            (if (= :ready (:status runtime))
                              :starting :not-running)))
              (if-not (= :ready (:status runtime))
                (do
                  (sleep-while-running! running
                                        (supervisor-delay-ms lateon attempt))
                  (recur (inc attempt) false))
                (let [_ (locking (analysis-mutex graph)
                          (semantic-reconcile/retry-recoverable-failed!
                           graph project settings))
                      worker (semantic-worker/create
                              graph project settings (:client runtime)
                              {:settings (semantic-worker-settings settings runtime)
                               :progress-fn
                               #(swap! runtime-state assoc :worker-progress %)})
                      _ (reset! worker-state worker)
                      _ (swap! runtime-state assoc
                               :worker-status :running
                               :worker-detail nil
                               :worker-progress @(:progress worker)
                               :recovery-attempt 0)
                      worker-attempt
                      (try {:result (semantic-worker/run! worker)}
                           (catch Throwable error {:error error}))]
                  (if (or (not @running) (nil? (:error worker-attempt)))
                    (:result worker-attempt)
                    (let [error (:error worker-attempt)
                          retriable? (retriable-supervisor-error? error)]
                      (try (semantic-runtime/stop! runtime)
                           (catch Throwable _))
                      (reset! worker-state nil)
                      (swap! runtime-state merge
                             {:status (if retriable? :recovering :failed)
                              :detail (.getMessage error)
                              :worker-status
                              (if retriable? :recovering :failed)
                              :worker-detail (.getMessage error)})
                      (semantic-state/record-watermark!
                       graph {:provider semantic-reconcile/provider
                              :state (if retriable? :degraded :failed)
                              :last-error-at (System/currentTimeMillis)
                              :last-error (.getMessage error)})
                      (when retriable?
                        (sleep-while-running!
                         running (supervisor-delay-ms lateon attempt))
                        (recur (inc attempt) false)))))))))))))

(defn- run-router-supervisor!
  [project settings runtime-state running router-factory]
  (let [router-enabled? (get-in settings [:context :query-router :enabled])
        reranker-enabled? (get-in settings
                                  [:context :candidate-reranker :enabled])
        retry-settings (get-in settings [:semantic :lateon-code])]
    (loop [attempt 0]
      (when @running
        (when (pos? attempt)
          (swap! runtime-state assoc
                 :query-router-status (if router-enabled? :recovering :disabled)
                 :candidate-reranker-status
                 (if reranker-enabled? :recovering :disabled)))
        (let [outcome (try {:runtime (router-factory project settings)}
                           (catch Throwable error {:error error}))
              runtime (:runtime outcome)
              error (:error outcome)]
          (if (and runtime (= :ready (:status runtime)))
            (do
              (swap! runtime-state assoc
                     :query-router-status
                     (if router-enabled? :ready :disabled)
                     :query-router-detail nil
                     :query-router-recovery (:recovery runtime)
                     :query-router (:client runtime)
                     :candidate-reranker-status
                     (if reranker-enabled? :ready :disabled)
                     :candidate-reranker-detail nil
                     :candidate-reranker (:reranker runtime)
                     :query-router-inference (:inference runtime)
                     :query-router-runtime runtime)
              (while (and @running
                          (or (nil? (:process runtime))
                              (.isAlive ^Process (:process runtime))))
                (Thread/sleep 1000))
              (intent-router/stop! runtime)
              (when @running
                (recur (inc attempt))))
            (do
              (swap! runtime-state assoc
                     :query-router-status
                     (if router-enabled?
                       (if error :recovering (:status runtime)) :disabled)
                     :query-router-detail
                     (or (some-> error .getMessage) (:detail runtime))
                     :candidate-reranker-status
                     (if reranker-enabled?
                       (if error :recovering (:status runtime)) :disabled)
                     :candidate-reranker-detail
                     (or (some-> error .getMessage) (:detail runtime))
                     :query-router
                     (intent-router/unavailable
                      :startup-failed (some-> error .getMessage))
                     :candidate-reranker
                     (candidate-reranker/unavailable
                      :startup-failed (some-> error .getMessage)))
              (sleep-while-running!
               running (supervisor-delay-ms retry-settings attempt))
              (when @running
                (recur (inc attempt))))))))))

(defn start!
  "Run a foreground loopback-only service for one project."
  ([project]
   (start! project {}))
  ([project {:keys [runtime-factory router-factory]
             :or {runtime-factory semantic-runtime/start!
                  router-factory intent-router/start!}}]
   (when (client/available? project)
     (throw (ex-info "A service is already running for this project"
                     {:exit-code 2})))
   (Files/createDirectories (:state-dir project)
                            (make-array java.nio.file.attribute.FileAttribute 0))
   (let [service-lease (acquire-service-lock project)
          settings (config/load-config project)
          token (str (UUID/randomUUID))
          instance-id (str (UUID/randomUUID))
          shutdown-hook
          (Thread.
           ^Runnable
           #(try
              (lifecycle/delete-owned! project instance-id)
              (catch Throwable _))
           "llm-context-service-cleanup")
          shutdown-hook-registered? (atom false)
          running (atom true)
          graph-generation (atom 0)
          semantic-enabled? (semantic-reconcile/enabled? settings)
          progress-state (analysis-progress/create project)
          runtime-state (atom {:project project
                               :status (if semantic-enabled?
                                         :starting :disabled)
                               :worker-status (if semantic-enabled?
                                                :starting :disabled)
                               :query-router-status
                               (if (get-in settings
                                           [:context :query-router :enabled])
                                 :starting :disabled)
                               :candidate-reranker-status
                               (if (get-in settings
                                           [:context :candidate-reranker
                                            :enabled])
                                 :starting :disabled)
                               :progress-state progress-state
                               :semantic-settings
                               (get-in settings [:semantic :lateon-code])
                               :watcher-status
                               (if (get-in settings [:service :watch])
                                 :starting :disabled)
                               :analysis-progress
                               (analysis-progress/snapshot progress-state)})
          worker-state (atom nil)]
      (with-open [service-lock service-lease]
       (try
        (store/recover-legacy-full-replacement! project settings)
        (with-open [graph (store/open project settings)
                    server (transport/open-listener project)]
          (add-watch runtime-state ::durable-health
                     (fn [_ _ _ current]
                       (persist-runtime-health! current)))
          ;; Capture a last committed snapshot before any later analysis can
          ;; make the generation sentinel odd. Status can then report the
          ;; previous graph while a replacement is being committed.
          (try
            (current-semantic-status! graph runtime-state)
            (catch Throwable error
              (swap! runtime-state assoc
                     :last-semantic-status
                     {:graph-state :unknown
                      :availability :unavailable
                      :analysis-progress
                      (analysis-progress/snapshot progress-state)
                      :runtime {:status :starting
                                :detail (.getMessage error)}})))
          (lifecycle/write-descriptor!
           project
           (merge
            (transport/endpoint-descriptor server)
            (merge (service-contract/runtime-identity)
                   {:started-at (System/currentTimeMillis)
                    :token token
             :instance-id instance-id
             :pid (.pid (ProcessHandle/current))
                    :semantic-status (:status @runtime-state)})))
          (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
          (reset! shutdown-hook-registered? true)
          (let [semantic-supervisor-future
                (when semantic-enabled?
                  (future
                    (run-semantic-supervisor!
                     graph project settings runtime-state worker-state running
                     runtime-factory)))
                router-future
                (when (or (get-in settings [:context :query-router :enabled])
                          (get-in settings
                                  [:context :candidate-reranker :enabled]))
                  (future
                    (run-router-supervisor!
                     project settings runtime-state running router-factory)))
                project-watcher
                (when (get-in settings [:service :watch])
                  (watcher/start!
                   (assoc
                    (watcher/create
                     project settings
                     (fn []
                      (try
                        (let [result (run-analysis! graph graph-generation
                                                     project settings false
                                                     runtime-state)]
                          (println
                           (format
                            "Watched analysis: %d files, %d changed, %d deleted"
                            (:files result)
                            (or (:changed result) (:files result))
                            (or (:deleted result) 0))))
                        (catch Throwable error
                          (binding [*out* *err*]
                            (println "Watched analysis failed:"
                                     (.getMessage error)))))))
                    :status-fn
                    (fn [{:keys [status detail]}]
                      (swap! runtime-state assoc
                             :watcher-status status
                             :watcher-detail detail)))))
                requests (request-executor settings)]
            (println "llm-context service listening on"
                     (pr-str (transport/endpoint-descriptor server)))
            (when-not semantic-enabled?
              (println "LateOn semantic runtime:"
                       (name (:status @runtime-state))))
            (try
              (while @running
                (try
                  (let [socket (transport/accept server)]
                    (try
                      (.execute
                       requests
                       ^Runnable
                       (fn []
                         (when (handle! socket token project settings
                                        graph graph-generation runtime-state)
                           (reset! running false)
                           (try
                             (.close server)
                             (catch Throwable _)))))
                      (catch RejectedExecutionException _
                        (reject-busy! socket))))
                  (catch java.nio.channels.AsynchronousCloseException error
                    (when @running
                      (throw error)))
                  (catch SocketException error
                    (when @running
                      (throw error)))))
              (finally
                (.shutdown requests)
                (when-not (.awaitTermination requests 10 TimeUnit/SECONDS)
                  (.shutdownNow requests))
                (when-let [worker @worker-state]
                  (semantic-worker/stop! worker))
                (when project-watcher
                  (watcher/stop! project-watcher))
                (when semantic-supervisor-future
                  (when (= :timeout
                           (deref semantic-supervisor-future 10000 :timeout))
                    (future-cancel semantic-supervisor-future)))
                ;; Wait for in-flight preparation/commit before closing graph.
                (locking (analysis-mutex graph) nil)
                (when (and router-future
                           (not (future-done? router-future)))
                  (future-cancel router-future))))))
        (finally
          (when @shutdown-hook-registered?
            (try
              (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
              (catch IllegalStateException _)))
          (remove-watch runtime-state ::durable-health)
          (lifecycle/delete-owned! project instance-id)
          (when-let [router-runtime (:query-router-runtime @runtime-state)]
            (intent-router/stop! router-runtime))
          (semantic-runtime/stop! @runtime-state)))))
   0))
