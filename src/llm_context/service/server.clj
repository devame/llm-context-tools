(ns llm-context.service.server
  (:require [clojure.edn :as edn]
            [llm-context.config :as config]
            [llm-context.context :as context]
            [llm-context.export :as export]
            [llm-context.query :as query]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.runtime :as semantic-runtime]
            [llm-context.semantic.state :as semantic-state]
            [llm-context.semantic.worker :as semantic-worker]
            [llm-context.service.client :as client]
            [llm-context.store :as store])
  (:import [java.io PushbackReader]
           [java.lang ProcessHandle]
           [java.net InetAddress ServerSocket]
           [java.nio.file Files OpenOption StandardOpenOption]
           [java.util UUID]))

(defn- query-value [graph semantic-client settings subcommand args]
  (let [argument (fn []
                   (or (first args)
                       (throw (ex-info (str "query " subcommand " requires an argument")
                                       {:exit-code 2}))))]
   (case subcommand
    "stats" (query/stats graph)
    "find-symbol" (query/symbols graph (argument))
    "search" (query/search graph semantic-client settings (argument))
    "callers" (query/callers graph (argument))
    "callees" (query/callees graph (argument))
    "trace" (query/transitive-callees graph (argument))
    "entry-points" (query/entry-points graph)
    "effects" (query/effects graph)
    "unresolved" (query/unresolved graph)
    (throw (ex-info (str "Unknown query: " subcommand) {:exit-code 2})))))

(defn- semantic-status [graph runtime-state]
  (assoc (semantic-state/semantic-summary
          graph semantic-reconcile/provider (System/currentTimeMillis))
         :runtime
         (select-keys runtime-state
                      [:status :reason :detail :endpoint :log-path])))

(defn- dispatch [project settings graph runtime-state request]
  (case (:op request)
    :ping :pong
    :query (query-value graph (:client runtime-state) settings
                        (:subcommand request) (:args request))
    :context
    (let [packet (context/build graph (:options request))]
      (if (= :markdown (get-in request [:options :format]))
        (context/markdown packet)
        packet))
    :export (export/render graph (:format request))
    :semantic-status (semantic-status graph runtime-state)
    :semantic-sync
    (do
      (semantic-reconcile/reconcile! graph project settings)
      (semantic-status graph runtime-state))
    :stop :stopping
    (throw (ex-info (str "Unknown service operation: " (:op request))
                    {:exit-code 2}))))

(defn- handle! [socket token project settings graph runtime-state]
  (with-open [socket socket
              reader (PushbackReader. (java.io.InputStreamReader.
                                       (.getInputStream socket)))
              writer (java.io.PrintWriter. (.getOutputStream socket) true)]
    (let [request (edn/read {:eof nil} reader)
          response
          (try
            (when-not (= token (:token request))
              (throw (ex-info "Invalid service token" {:exit-code 2})))
            {:ok true :value
             (dispatch project settings graph runtime-state request)}
            (catch Throwable error
              {:ok false :error (.getMessage error)
               :exit-code (or (:exit-code (ex-data error)) 1)}))]
      (.println writer (pr-str response))
      (and (:ok response) (= :stop (:op request))))))

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
    (let [settings (config/load-config project)
          token (str (UUID/randomUUID))
          running (atom true)
          runtime-state
          (if (semantic-reconcile/enabled? settings)
            (try
              (runtime-factory project settings)
              (catch Throwable error
                {:status :failed :reason :startup-failed
                 :detail (.getMessage error)}))
            {:status :disabled})]
      (try
        (with-open [graph (store/open project settings)
                    server (ServerSocket. 0 50
                                         (InetAddress/getLoopbackAddress))]
          (let [worker (when (= :ready (:status runtime-state))
                         (semantic-worker/create
                          graph project settings (:client runtime-state)))
                worker-future
                (when worker
                  (future
                    (try
                      (semantic-worker/run! worker)
                      (catch Throwable error
                        (semantic-state/record-watermark!
                         graph {:provider semantic-reconcile/provider
                                :state :failed
                                :last-error-at (System/currentTimeMillis)
                                :last-error (.getMessage error)})
                        :failed))))]
            (Files/writeString
             descriptor-path
             (pr-str {:port (.getLocalPort server)
                      :token token
                      :pid (.pid (ProcessHandle/current))
                      :semantic-status (:status runtime-state)})
             (into-array OpenOption [StandardOpenOption/CREATE
                                     StandardOpenOption/TRUNCATE_EXISTING
                                     StandardOpenOption/WRITE]))
            (println "llm-context service listening on loopback port"
                     (.getLocalPort server))
            (when-not (= :ready (:status runtime-state))
              (println "LateOn semantic runtime:"
                       (name (:status runtime-state))
                       (or (:detail runtime-state)
                           (some-> (:reason runtime-state) name))))
            (try
              (while @running
                (when (handle! (.accept server) token project settings
                               graph runtime-state)
                  (reset! running false)))
              (finally
                (when worker
                  (semantic-worker/stop! worker))
                (when worker-future
                  (deref worker-future 10000 :timeout))
                (Files/deleteIfExists descriptor-path)))))
        (finally
          (semantic-runtime/stop! runtime-state)))))
   0))
