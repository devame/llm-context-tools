(ns llm-context.service.daemon
  "Launch the project coordinator as a detached JVM using the current
  application classpath."
  (:require [llm-context.service.client :as client])
  (:import [java.lang ProcessBuilder$Redirect]
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
