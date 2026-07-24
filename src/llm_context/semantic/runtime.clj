(ns llm-context.semantic.runtime
  "Project-scoped NextPlaid child-process lifecycle."
  (:require [clojure.string :as str]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.next-plaid :as next-plaid])
  (:import [java.io File]
           [java.net ServerSocket]
           [java.nio.file Files LinkOption Path Paths]
           [java.lang ProcessBuilder$Redirect]
           [java.util.concurrent TimeUnit]))

(defn- windows? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name"))
                    "windows"))

(defn- executable-candidates [command]
  (let [path (Paths/get command (make-array String 0))
        direct? (or (.isAbsolute path)
                    (str/includes? command File/separator))
        extensions (if (windows?) ["" ".exe" ".cmd" ".bat"] [""])]
    (if direct?
      (map #(Paths/get (str command %) (make-array String 0)) extensions)
      (for [directory (str/split (or (System/getenv "PATH") "")
                                 (re-pattern File/pathSeparator))
            extension extensions
            :when (seq directory)]
        (Paths/get directory
                   (into-array String [(str command extension)]))))))

(defn find-executable [command]
  (let [installed (System/getenv "LLM_CONTEXT_INSTALL_DIR")
        candidates
        (if (and installed
                 (not (.isAbsolute
                       (Paths/get command (make-array String 0))))
                 (not (str/includes? command File/separator)))
          (concat
           (executable-candidates
            (str (.resolve (Paths/get installed (make-array String 0))
                           command)))
           (executable-candidates command))
          (executable-candidates command))]
    (some #(when (and (Files/isRegularFile ^Path %
                                           (make-array LinkOption 0))
                      (or (windows?) (Files/isExecutable ^Path %)))
             (.toAbsolutePath ^Path %))
          candidates)))

(defn- default-model-cache []
  (if (windows?)
    (str (.resolve
          (Paths/get (or (System/getenv "LOCALAPPDATA")
                         (System/getProperty "user.home"))
                     (make-array String 0))
          "llm-context/models"))
    (str (.resolve
          (Paths/get (System/getProperty "user.home")
                     (make-array String 0))
          ".cache/llm-context/models"))))

(defn model-path [project settings]
  (if-let [configured (:model-path settings)]
    (let [path (Paths/get configured (make-array String 0))]
      (.normalize
       (if (.isAbsolute path)
         path
         (.resolve ^Path (:root project) path))))
    (let [cache-root
          (or (System/getenv "LLM_CONTEXT_MODEL_CACHE")
              (default-model-cache))
          model-directory (str/replace (:model settings) "/" "--")]
      (.resolve
       (.resolve
        (Paths/get cache-root (make-array String 0))
        model-directory)
       (:model-revision settings)))))

(defn- free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- process-command [executable port index-path model-path settings]
  (vec
   (concat
    [(str executable)
     "--host" "127.0.0.1"
     "--port" (str port)
     "--index-dir" (str index-path)
     "--model" (str model-path)]
    (when (= :int8 (:quantization settings)) ["--int8"])
    ["--parallel" (str (:encoding-sessions settings))
     "--batch-size" (str (:encoding-batch-size settings))
     "--document-length" (str (:model-document-length settings))])))

(defn onnx-runtime-path [^Path executable]
  (let [name (cond
               (windows?) "onnxruntime.dll"
               (str/includes?
                (str/lower-case (System/getProperty "os.name"))
                "mac")
               "libonnxruntime.dylib"
               :else "libonnxruntime.so")
        path (.resolve (.getParent executable) name)]
    (when (Files/isRegularFile path (make-array LinkOption 0))
      path)))

(defn- await-ready! [runtime settings]
  (let [deadline (+ (System/currentTimeMillis)
                    (:startup-timeout-ms settings))]
    (loop [last-error nil]
      (cond
        (not (.isAlive ^Process (:process runtime)))
        (throw
         (ex-info "NextPlaid exited before becoming ready"
                  {:type :semantic/runtime-exited
                   :exit-code (.exitValue ^Process (:process runtime))
                   :retriable? true}
                  last-error))

        (>= (System/currentTimeMillis) deadline)
        (throw
         (ex-info "Timed out waiting for the pinned NextPlaid model"
                  {:type :semantic/runtime-timeout :retriable? true}
                  last-error))

        :else
        (let [attempt
              (try
                {:health (index/index-health (:client runtime))}
                (catch Throwable error {:error error}))]
          (if (get-in attempt [:health :ready?])
            (:health attempt)
            (do
              (Thread/sleep 250)
              (recur
               (or (:error attempt)
                   (ex-info "NextPlaid health is not ready"
                            {:health (dissoc (:health attempt) :raw)}))))))))))

(defn start!
  "Start the configured pinned sidecar. Missing installation components return
  a structured unavailable result so the graph service can still run."
  [project config]
  (let [settings (get-in config [:semantic :lateon-code])
        command (:next-plaid-command settings)
        executable (find-executable (first command))
        model (model-path project settings)]
    (cond
      (nil? executable)
      {:status :unavailable
       :reason :executable-missing
       :detail (first command)}

      (not (Files/isDirectory model (make-array LinkOption 0)))
      {:status :unavailable
       :reason :model-missing
       :detail (str model)}

      :else
      (let [port (free-port)
            index-path (.normalize
                        (.resolve ^Path (:root project)
                                  (:index-path settings)))
            log-directory (.resolve ^Path (:state-dir project) "logs")
            log-path (.resolve log-directory "next-plaid.log")
            _ (Files/createDirectories
               index-path
               (make-array java.nio.file.attribute.FileAttribute 0))
            _ (Files/createDirectories
               log-directory
               (make-array java.nio.file.attribute.FileAttribute 0))
            full-command (concat (process-command executable port index-path
                                                    model settings)
                                 (next command))
            builder (doto (ProcessBuilder. ^java.util.List (vec full-command))
                      (.redirectErrorStream true)
                      (.redirectOutput
                       (ProcessBuilder$Redirect/appendTo (.toFile log-path))))
            _ (when-let [onnx-runtime (onnx-runtime-path executable)]
                (.put (.environment builder)
                      "ORT_DYLIB_PATH" (str onnx-runtime)))
            process (.start builder)
            endpoint (str "http://127.0.0.1:" port)
            client (next-plaid/create endpoint settings)
            runtime {:status :starting :process process :client client
                     :endpoint endpoint :log-path log-path}]
        (try
          (let [health (await-ready! runtime settings)]
            (assoc runtime :status :ready :health health))
          (catch Throwable error
            (.destroy process)
            (when-not (.waitFor process 5 TimeUnit/SECONDS)
              (.destroyForcibly process))
            (throw error)))))))

(defn stop! [runtime]
  (when-let [client (:client runtime)]
    (index/close-index! client))
  (when-let [process ^Process (:process runtime)]
    (.destroy process)
    (when-not (.waitFor process 5 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (.waitFor process 5 TimeUnit/SECONDS)))
  nil)
