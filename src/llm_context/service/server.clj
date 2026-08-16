(ns llm-context.service.server
  (:require [clojure.edn :as edn]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.context :as context]
            [llm-context.export :as export]
            [llm-context.intent :as intent]
            [llm-context.intent.reranker :as candidate-reranker]
            [llm-context.intent.router :as intent-router]
            [llm-context.query :as query]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.runtime :as semantic-runtime]
            [llm-context.semantic.state :as semantic-state]
            [llm-context.semantic.worker :as semantic-worker]
            [llm-context.service.client :as client]
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
  (let [runtime (select-keys runtime-state
                             [:status :reason :detail :endpoint :log-path
                              :worker-status :worker-detail :worker-progress
                              :query-router-status :query-router-detail
                              :candidate-reranker-status
                              :candidate-reranker-detail])]
    (cond-> runtime
      (:log-path runtime) (update :log-path str))))

(defn- semantic-status [graph runtime-state]
  (let [runtime (runtime-view runtime-state)
        summary (semantic-state/semantic-summary
                 graph semantic-reconcile/provider
                 (System/currentTimeMillis))]
    (assoc summary
           :graph-state :ready
           :availability
           (if (= :ready (:status runtime)) :available :unavailable)
           :analysis-progress (:analysis-progress runtime-state)
           :runtime runtime)))

(defn- updating-semantic-status [runtime-state]
  (let [runtime (runtime-view runtime-state)
        previous (:last-semantic-status runtime-state)
        baseline (or previous
                     {:indexed 0 :indexed-records 0 :desired 0
                      :pending 0 :leased 0 :failed 0 :dirty 0
                      :coverage-percent 0.0 :completeness :unknown})]
    (assoc baseline
           :graph-state :updating
           :availability
           (if (= :ready (:status runtime)) :available :unavailable)
           :analysis-progress (:analysis-progress runtime-state)
           :runtime runtime)))

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
  (let [prepared
        (locking (analysis-mutex graph)
          (let [full? (or force-full?
                          (not= :ready (store/graph-state graph))
                          (not (incremental/index-present? graph)))
                incremental-prepared
                (when-not full?
                  (incremental/prepare-current
                   graph project settings progress-fn))]
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
                         graph project settings candidate)))})))))]
    (cond
      (:complete-result prepared) (:complete-result prepared)
      (:stale? prepared)
      prepared
      (:full? prepared)
        (full/finish-candidate!
         graph project settings (:result prepared) progress-fn)
      :else
      (incremental/finish-candidate!
       graph project settings (:result prepared))))))

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
          runtime-state (atom {:status (if semantic-enabled?
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
                               :analysis-progress
                               (analysis-progress/snapshot progress-state)})
          worker-state (atom nil)]
      (with-open [service-lock service-lease]
       (try
        (store/recover-legacy-full-replacement! project settings)
        (with-open [graph (store/open project settings)
                    server (transport/open-listener project)]
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
            {:token token
             :instance-id instance-id
             :pid (.pid (ProcessHandle/current))
             :semantic-status (:status @runtime-state)}))
          (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
          (reset! shutdown-hook-registered? true)
          (let [runtime-future
                (when semantic-enabled?
                  (future
                    (try
                      (let [runtime (runtime-factory project settings)]
                        (swap! runtime-state
                               merge
                               (assoc runtime :worker-status
                                      (if (= :ready (:status runtime))
                                        :starting :not-running))))
                      (catch Throwable error
                        (swap! runtime-state
                               merge
                               {:status :failed
                                :reason :startup-failed
                                :detail (.getMessage error)
                                :worker-status :not-running})))))
                router-future
                (when (or (get-in settings [:context :query-router :enabled])
                          (get-in settings
                                  [:context :candidate-reranker :enabled]))
                  (future
                    (try
                      (let [router-runtime (router-factory project settings)]
                        (swap! runtime-state assoc
                               :query-router-status
                               (if (get-in settings
                                           [:context :query-router :enabled])
                                 (:status router-runtime) :disabled)
                               :query-router (:client router-runtime)
                               :candidate-reranker-status
                               (if (get-in settings
                                           [:context :candidate-reranker
                                            :enabled])
                                 (:status router-runtime) :disabled)
                               :candidate-reranker (:reranker router-runtime)
                               :query-router-runtime router-runtime))
                      (catch Throwable error
                        (swap! runtime-state assoc
                               :query-router-status :failed
                               :query-router-detail (.getMessage error)
                               :candidate-reranker-status :failed
                               :candidate-reranker-detail (.getMessage error)
                               :query-router
                               (intent-router/unavailable
                                :startup-failed (.getMessage error))
                               :candidate-reranker
                               (candidate-reranker/unavailable
                                :startup-failed (.getMessage error)))))))
                worker-future
                (when semantic-enabled?
                  (future
                    (when runtime-future
                      @runtime-future)
                    (when (= :ready (:status @runtime-state))
                      (let [worker
                            (semantic-worker/create
                             graph project settings
                             (:client @runtime-state)
                             {:progress-fn
                              #(swap! runtime-state assoc
                                      :worker-progress %)})]
                        (reset! worker-state worker)
                        (swap! runtime-state assoc :worker-status :running)
                        (try
                          (semantic-worker/run! worker)
                          (catch Throwable error
                            (swap! runtime-state assoc
                                   :worker-status :failed
                                   :worker-detail (.getMessage error))
                            (semantic-state/record-watermark!
                             graph {:provider semantic-reconcile/provider
                                    :state :failed
                                    :last-error-at
                                    (System/currentTimeMillis)
                                    :last-error (.getMessage error)})
                            :failed))))))
                project-watcher
                (when (get-in settings [:service :watch])
                  (watcher/start!
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
                                     (.getMessage error)))))))))
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
                (when worker-future
                  (when (= :timeout (deref worker-future 10000 :timeout))
                    (future-cancel worker-future)))
                ;; Wait for in-flight preparation/commit before closing graph.
                (locking (analysis-mutex graph) nil)
                (when (and runtime-future
                           (not (future-done? runtime-future)))
                  (future-cancel runtime-future))
                (when (and router-future
                           (not (future-done? router-future)))
                  (future-cancel router-future))))))
        (finally
          (when @shutdown-hook-registered?
            (try
              (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
              (catch IllegalStateException _)))
          (lifecycle/delete-owned! project instance-id)
          (when-let [router-runtime (:query-router-runtime @runtime-state)]
            (intent-router/stop! router-runtime))
          (semantic-runtime/stop! @runtime-state)))))
   0))
