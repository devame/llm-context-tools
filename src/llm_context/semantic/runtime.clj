(ns llm-context.semantic.runtime
  "Project-scoped NextPlaid child-process lifecycle."
  (:require [clojure.string :as str]
            [llm-context.accelerator :as accelerator]
            [llm-context.logs :as logs]
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

(defn process-command [executable port index-path model-path settings selection]
  (vec
   (concat
    [(str executable)
     "--host" "127.0.0.1"
     "--port" (str port)
     "--index-dir" (str index-path)
     "--model" (str model-path)]
    (:arguments selection)
    ["--parallel" (str (:encoding-sessions selection))
     "--batch-size" (str (:encoding-batch-size selection))
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

(def ^:private diagnostic-tail-bytes 131072)

(defn- recent-log-lines [runtime]
  (when-let [log-path (:log-path runtime)]
    (try
      (with-open [file (java.io.RandomAccessFile. (str log-path) "r")]
        (let [size (.length file)
              byte-count (int (min diagnostic-tail-bytes size))]
          (when (pos? byte-count)
            (.seek file (- size byte-count))
            (let [bytes (byte-array byte-count)]
              (.readFully file bytes)
              (str/split-lines
               (String. bytes java.nio.charset.StandardCharsets/UTF_8))))))
      (catch Throwable _ nil))))

(defn- latest-line [lines pattern]
  (some (fn [line]
          (when (re-find pattern line)
            (str/trim line)))
        (reverse lines)))

(defn- cuda-diagnostic [line]
  (cond
    (re-find #"(?i)CUDA support not compiled" line)
    {:kind :cuda-build-missing
     :detail "The installed NextPlaid binary does not include CUDA support."
     :action (str "install the CUDA-enabled package with "
                  "LLM_CONTEXT_ACCELERATOR_PACKAGE=cuda sh install.sh, "
                  "or set :accelerator :cpu")
     :line line}

    (re-find #"(?i)(no CUDA-capable device|CUDA_ERROR_NO_DEVICE|CUDA failure 100)"
             line)
    {:kind :cuda-device-unavailable
     :detail "CUDA was selected, but the runtime could not detect a CUDA-capable device."
     :action (str "fix NVIDIA/WSL CUDA device visibility, then restart the service, "
                  "or set :accelerator :cpu to use CPU/INT8")
     :line line}

    (re-find #"(?i)(libcudnn[.]so[.]9|cudnn).*not found" line)
    {:kind :cudnn-unavailable
     :detail "CUDA could not load cuDNN 9 (libcudnn.so.9)."
     :action "install cuDNN 9 and expose libcudnn.so.9 to the service"
     :line line}

    (re-find #"(?i)(CUDA initialization error|Falling back to CPU|No execution providers from session options registered successfully)"
             line)
    {:kind :cuda-provider-failed
     :detail "NextPlaid failed to initialize its CUDA provider and is falling back to CPU."
     :action "run llm-context doctor, fix the CUDA runtime, or set :accelerator :cpu"
     :line line}))

(defn runtime-diagnostic
  "Return a live, actionable provider diagnostic from the current NextPlaid
  log. This complements static prerequisite checks: a CUDA provider can exist
  on disk while failing to discover a usable device at runtime."
  [runtime]
  (when-let [lines (recent-log-lines runtime)]
    (when-let [line (or (latest-line lines
                                     #"(?i)(no CUDA-capable device|CUDA_ERROR_NO_DEVICE|CUDA failure 100)")
                        (latest-line lines #"(?i)CUDA support not compiled")
                        (latest-line lines #"(?i)(libcudnn[.]so[.]9|cudnn).*not found")
                        (latest-line lines
                                     #"(?i)(CUDA initialization error|Falling back to CPU|No execution providers from session options registered successfully)"))]
      (cuda-diagnostic line))))

(defn- startup-diagnostic [runtime]
  (when-let [lines (recent-log-lines runtime)]
    (latest-line lines #"(?i)(error|failed|cuda|cudnn)")))

(defn- await-ready! [runtime settings]
  (let [deadline (+ (System/currentTimeMillis)
                    (:startup-timeout-ms settings))]
    (loop [last-error nil]
      (cond
        (not (.isAlive ^Process (:process runtime)))
        (let [diagnostic (startup-diagnostic runtime)]
          (throw
           (ex-info
            (str "NextPlaid exited before becoming ready"
                 (when diagnostic (str ": " diagnostic)))
            {:type :semantic/runtime-exited
             :exit-code (.exitValue ^Process (:process runtime))
             :log-path (some-> (:log-path runtime) str)
             :startup-diagnostic diagnostic
             :retriable? true}
            last-error)))

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
      (let [selection (accelerator/resolve-runtime settings executable model)
            port (free-port)
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
            _ (logs/rotate-before-start! log-path (:service config))
            full-command (concat (process-command executable port index-path
                                                    model settings selection)
                                 (next command))
            builder (doto (ProcessBuilder. ^java.util.List (vec full-command))
                      (.redirectErrorStream true)
                      (.redirectOutput
                       (ProcessBuilder$Redirect/appendTo (.toFile log-path))))
            _ (when-let [onnx-runtime (onnx-runtime-path executable)]
                (.put (.environment builder)
                      "ORT_DYLIB_PATH" (str onnx-runtime)))
            _ (accelerator/configure-process-environment!
               builder settings executable)
            process (.start builder)
            endpoint (str "http://127.0.0.1:" port)
            client (next-plaid/create endpoint settings)
            runtime {:status :starting :process process :client client
                     :endpoint endpoint :log-path log-path
                     :inference selection}]
        (try
          (let [health (await-ready! runtime settings)
                diagnostic (runtime-diagnostic runtime)]
            (when (and diagnostic
                       (= :cuda (:requested-accelerator selection)))
              (throw
               (ex-info
                (str "NextPlaid CUDA runtime is unavailable: "
                     (:detail diagnostic) " Action: " (:action diagnostic))
                {:type :accelerator/runtime-unavailable
                 :diagnostic diagnostic
                 :retriable? true})))
            (cond-> (assoc runtime :status :ready :health health)
              diagnostic (assoc :runtime-diagnostic diagnostic)))
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
