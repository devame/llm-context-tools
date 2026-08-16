(ns llm-context.service.daemon
  "Launch the project coordinator as a detached JVM using the current
  application classpath."
  (:require [llm-context.config :as config]
            [llm-context.logs :as logs]
            [llm-context.service.client :as client])
  (:import [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.nio.file Files Path Paths]))

(defn- windows? []
  (.startsWith (.toLowerCase (System/getProperty "os.name")) "windows"))

(defn java-executable []
  (let [name (if (windows?) "java.exe" "java")]
    (.resolve
     (.resolve
      (Paths/get (System/getProperty "java.home") (make-array String 0))
      "bin")
     name)))

(defn launch-command [project]
  [(str (java-executable))
   "--enable-native-access=ALL-UNNAMED"
   ;; The long-lived coordinator must not depend on the JVM's shared class
   ;; archive. On WSL/overlay-backed filesystems an inaccessible mapped CDS
   ;; page can turn a recoverable service failure into a native JVM crash.
   "-Xshare:off"
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "llm-context.main"
   "-C" (:root-str project)
   "service" "foreground"])

(defn start!
  "Start the project service without retaining the caller's terminal. Return
  after the graph endpoint is reachable, or a structured starting result when
  database initialization exceeds the bounded confirmation window."
  [project]
  (when (client/available? project)
    (throw (ex-info "A service is already running for this project"
                    {:exit-code 2})))
  (let [log-directory (.resolve ^Path (:state-dir project) "logs")
        log-path (.resolve log-directory "service.log")
        _ (Files/createDirectories
           log-directory
           (make-array java.nio.file.attribute.FileAttribute 0))
        _ (logs/rotate-before-start! log-path
                                     (:service (config/load-config project)))
        builder (doto (ProcessBuilder. ^java.util.List
                                       (launch-command project))
                  (.redirectErrorStream true)
                  (.redirectOutput
                   (ProcessBuilder$Redirect/appendTo (.toFile log-path))))
        process (.start builder)
        deadline (+ (System/currentTimeMillis) 30000)]
    (loop []
      (cond
        (client/available? project)
        {:status :running :pid (.pid process) :log-path (str log-path)}

        (not (.isAlive process))
        (throw
         (ex-info
          (str "Project service exited during startup; inspect " log-path)
          {:exit-code 1 :exit-code-child (.exitValue process)
           :log-path (str log-path)}))

        (>= (System/currentTimeMillis) deadline)
        {:status :starting :pid (.pid process) :log-path (str log-path)}

        :else
        (do
          (Thread/sleep 100)
          (recur))))))

(defn- await-exit? [^ProcessHandle handle timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (not (.isAlive handle)) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 100) (recur))))))

(defn stop!
  "Request graceful shutdown, then ensure the exact advertised service process
  and its owned child runtimes cannot remain orphaned after the descriptor is
  removed."
  [project]
  (let [descriptor (client/descriptor project)
        response (client/request project {:op :stop})
        handle (when-let [pid (:pid descriptor)]
                 (.orElse (ProcessHandle/of (long pid)) nil))]
    (when (and (:ok response) handle (.isAlive ^ProcessHandle handle))
      (when-not (await-exit? handle 15000)
        (.destroy ^ProcessHandle handle)
        (when-not (await-exit? handle 5000)
          (.destroyForcibly ^ProcessHandle handle)
          (await-exit? handle 5000))))
    response))
