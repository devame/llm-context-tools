(ns llm-context.service.server
  (:require [clojure.edn :as edn]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.context :as context]
            [llm-context.export :as export]
            [llm-context.query :as query]
            [llm-context.service.client :as client]
            [llm-context.store :as store])
  (:import [java.io PushbackReader]
           [java.lang ProcessHandle]
           [java.net InetAddress ServerSocket]
           [java.nio.file Files OpenOption StandardOpenOption]
           [java.util UUID]))

(defn- with-graph [project settings f]
  (store/with-store [graph project settings]
    (f graph)))

(defn- query-value [graph subcommand args]
  (let [argument (fn []
                   (or (first args)
                       (throw (ex-info (str "query " subcommand " requires an argument")
                                       {:exit-code 2}))))]
   (case subcommand
    "stats" (query/stats graph)
    "find-symbol" (query/symbols graph (argument))
    "callers" (query/callers graph (argument))
    "callees" (query/callees graph (argument))
    "trace" (query/transitive-callees graph (argument))
    "entry-points" (query/entry-points graph)
    "effects" (query/effects graph)
    "unresolved" (query/unresolved graph)
    (throw (ex-info (str "Unknown query: " subcommand) {:exit-code 2})))))

(defn- dispatch [project settings request]
  (case (:op request)
    :ping :pong
    :query (with-graph project settings
             #(query-value % (:subcommand request) (:args request)))
    :context (with-graph project settings
               (fn [graph]
                 (let [packet (context/build graph (:options request))]
                   (if (= :markdown (get-in request [:options :format]))
                     (context/markdown packet)
                     packet))))
    :export (with-graph project settings #(export/render % (:format request)))
    :analyze (let [full? (or (:full? request)
                             (not (incremental/index-present? project settings)))]
               (if full?
                 (full/analyze! project settings)
                 (incremental/analyze! project settings)))
    :stop :stopping
    (throw (ex-info (str "Unknown service operation: " (:op request))
                    {:exit-code 2}))))

(defn- handle! [socket token project settings]
  (with-open [socket socket
              reader (PushbackReader. (java.io.InputStreamReader.
                                       (.getInputStream socket)))
              writer (java.io.PrintWriter. (.getOutputStream socket) true)]
    (let [request (edn/read {:eof nil} reader)
          response
          (try
            (when-not (= token (:token request))
              (throw (ex-info "Invalid service token" {:exit-code 2})))
            {:ok true :value (dispatch project settings request)}
            (catch Throwable error
              {:ok false :error (.getMessage error)
               :exit-code (or (:exit-code (ex-data error)) 1)}))]
      (.println writer (pr-str response))
      (and (:ok response) (= :stop (:op request))))))

(defn start!
  "Run a foreground loopback-only service for one project."
  [project]
  (let [descriptor-path (client/descriptor-path project)]
    (when (client/available? project)
      (throw (ex-info "A service is already running for this project"
                      {:exit-code 2})))
    (Files/createDirectories (:state-dir project)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (let [settings (config/load-config project)
          token (str (UUID/randomUUID))
          running (atom true)]
      (with-open [server (ServerSocket. 0 50 (InetAddress/getLoopbackAddress))]
        (Files/writeString descriptor-path
                           (pr-str {:port (.getLocalPort server)
                                    :token token
                                    :pid (.pid (ProcessHandle/current))})
                           (into-array OpenOption [StandardOpenOption/CREATE
                                                   StandardOpenOption/TRUNCATE_EXISTING
                                                   StandardOpenOption/WRITE]))
        (println "llm-context service listening on loopback port" (.getLocalPort server))
        (try
          (while @running
            (when (handle! (.accept server) token project settings)
              (reset! running false)))
          (finally
            (Files/deleteIfExists descriptor-path))))))
  0)
