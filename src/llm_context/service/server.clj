(ns llm-context.service.server
  (:require [clojure.edn :as edn]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.context :as context]
            [llm-context.export :as export]
            [llm-context.query :as query]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.runtime :as semantic-runtime]
            [llm-context.semantic.state :as semantic-state]
            [llm-context.semantic.worker :as semantic-worker]
            [llm-context.service.client :as client]
            [llm-context.service.transport :as transport]
            [llm-context.service.watcher :as watcher]
            [llm-context.store :as store])
  (:import [java.io PushbackReader]
           [java.lang ProcessHandle]
           [java.net SocketException]
           [java.nio.channels FileChannel OverlappingFileLockException]
           [java.nio.file Files OpenOption StandardOpenOption]
           [java.util UUID]
           [java.util.concurrent ArrayBlockingQueue RejectedExecutionException
            ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit]))

(defn- query-search-term [args]
  (let [term
        (or (first args)
            (throw (ex-info "query search requires an argument"
                            {:exit-code 2})))]
    (when-let [unknown (first (remove #{"--explain"} (next args)))]
      (throw (ex-info (str "Unknown query search option: " unknown)
                      {:exit-code 2})))
    term))

(defn- query-value [graph semantic-client settings subcommand args]
  (let [argument (fn []
                   (or (first args)
                       (throw (ex-info (str "query " subcommand " requires an argument")
                                       {:exit-code 2}))))]
   (case subcommand
    "stats" (query/stats graph)
    "find-symbol" (query/find-symbol graph (argument))
    "search" (query/search-explain graph semantic-client settings
                                   (query-search-term args))
    "callers" (query/callers graph (argument))
    "callees" (query/callees-command graph args)
    "trace" (query/transitive-callees graph (argument))
    "entry-points" (query/entry-points graph)
    "effects" (query/effects graph)
    "unresolved" (query/unresolved-command graph args)
    ("topics" "registrations" "dispatchers" "subscribers"
     "state-readers" "state-writers")
    (query/topics-command graph subcommand args)
    (throw (ex-info (str "Unknown query: " subcommand) {:exit-code 2})))))

(defn- semantic-status [graph runtime-state]
  (let [runtime (select-keys runtime-state
                             [:status :reason :detail :endpoint :log-path
                              :worker-status :worker-detail])]
    (let [summary (semantic-state/semantic-summary
                   graph semantic-reconcile/provider
                   (System/currentTimeMillis))
          runtime
          (cond-> runtime
            (:log-path runtime) (update :log-path str))]
      (assoc summary
             :availability
             (if (= :ready (:status runtime)) :available :unavailable)
             :runtime runtime))))

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

(defn- analyze! [graph generation project settings force-full?]
  (with-graph-write
    graph generation
    #(if (and (not force-full?)
              (= :ready (store/graph-state graph))
              (incremental/index-present? graph))
       (incremental/analyze! graph project settings)
       (full/analyze! graph project settings service-progress!))))

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

(defn- dispatch [project settings graph generation runtime-state request]
  (let [runtime @runtime-state]
    (case (:op request)
    :ping :pong
    :analyze (analyze! graph generation project settings (:full? request))
    :query
    (if (= "search" (:subcommand request))
      (let [term (query-search-term (:args request))
            semantic-attempt
            (query/semantic-search-attempt (:client runtime) settings term)]
        (read-consistently
         graph generation true
         #(query/search-explain-with-attempt
           % settings term semantic-attempt)))
      (read-consistently
       graph generation true
       #(query-value % (:client runtime) settings
                     (:subcommand request) (:args request))))
    :context
    (let [options (:options request)
          packet
          (if (:intent? options)
            (let [term (:focus options)
                  semantic-attempt
                  (query/semantic-search-attempt
                   (:client runtime) settings term)
                  packet
                  (read-consistently
                   graph generation true
                   (fn [view]
                     (let [search
                           (query/search-explain-with-attempt
                            view settings term semantic-attempt)
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
    :semantic-status (read-consistently graph generation false
                                        #(semantic-status % runtime))
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
         (semantic-status graph runtime)))
    :semantic-sync
    (do
      ;; An explicit sync is also the operator's repair action for exhausted
      ;; jobs. Marking is a short mutation; document planning and source reads
      ;; remain outside the project graph monitor.
      (with-graph-write graph generation
                        #(semantic-reconcile/mark-full! graph))
      (semantic-reconcile/reconcile! graph project settings)
      (read-consistently graph generation false
                         #(semantic-status % runtime)))
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
  (let [path (.resolve ^java.nio.file.Path (:state-dir project) "service.lock")
        channel
        (FileChannel/open
         path
         (into-array java.nio.file.OpenOption
                     [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
        lock (try
               (.tryLock channel)
               (catch OverlappingFileLockException _ nil))]
    (if lock
      {:channel channel :lock lock}
      (do
        (.close channel)
        (throw
         (ex-info "A service already owns this project"
                  {:exit-code 2 :type :service/already-owned}))))))

(defn start!
  "Run a foreground loopback-only service for one project."
  ([project]
   (start! project {}))
  ([project {:keys [runtime-factory]
             :or {runtime-factory semantic-runtime/start!}}]
   (let [descriptor-path (client/descriptor-path project)]
    (when (client/available? project)
      (throw (ex-info "A service is already running for this project"
                      {:exit-code 2})))
    (Files/createDirectories (:state-dir project)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (let [{:keys [channel lock]} (acquire-service-lock project)
          settings (config/load-config project)
          token (str (UUID/randomUUID))
          running (atom true)
          graph-generation (atom 0)
          semantic-enabled? (semantic-reconcile/enabled? settings)
          runtime-state (atom {:status (if semantic-enabled?
                                         :starting :disabled)
                               :worker-status (if semantic-enabled?
                                                :starting :disabled)})
          worker-state (atom nil)]
      (with-open [lock-channel channel
                  service-lock lock]
       (try
        (with-open [graph (store/open project settings)
                    server (transport/open-listener project)]
          (Files/writeString
           descriptor-path
           (pr-str
            (merge
             (transport/endpoint-descriptor server)
             {:token token
              :pid (.pid (ProcessHandle/current))
              :semantic-status (:status @runtime-state)}))
           (into-array OpenOption [StandardOpenOption/CREATE
                                   StandardOpenOption/TRUNCATE_EXISTING
                                   StandardOpenOption/WRITE]))
          (transport/secure-owner-only! descriptor-path)
          (let [runtime-future
                (when semantic-enabled?
                  (future
                    (try
                      (let [runtime (runtime-factory project settings)]
                        (reset! runtime-state
                                (assoc runtime :worker-status
                                       (if (= :ready (:status runtime))
                                         :starting :not-running))))
                      (catch Throwable error
                        (reset! runtime-state
                                {:status :failed
                                 :reason :startup-failed
                                 :detail (.getMessage error)
                                 :worker-status :not-running})))))
                worker-future
                (when semantic-enabled?
                  (future
                    (when runtime-future
                      @runtime-future)
                    (when (= :ready (:status @runtime-state))
                      (let [worker
                            (semantic-worker/create
                             graph project settings
                             (:client @runtime-state))]
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
                        (let [result (analyze! graph graph-generation
                                               project settings false)]
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
                ;; Wait for an in-flight watched analysis before closing graph.
                (locking graph nil)
                (when (and runtime-future
                           (not (future-done? runtime-future)))
                  (future-cancel runtime-future))
                (Files/deleteIfExists descriptor-path)))))
        (finally
          (semantic-runtime/stop! @runtime-state))))))
   0))
