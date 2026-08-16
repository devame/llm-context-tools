(ns llm-context.accelerator
  "Resolve a NextPlaid inference device without confusing device visibility
  with a usable CUDA-enabled ONNX Runtime installation."
  (:require [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path Paths]))

(def accelerators #{:auto :cpu :cuda})
(def quantizations #{:auto :int8 :fp32})

(defn- regular-file? [^Path path]
  (Files/isRegularFile path (make-array LinkOption 0)))

(defn- nvidia-smi-ready? []
  (try
    (let [process (.start (ProcessBuilder.
                           ^java.util.List
                           ["nvidia-smi" "--query-gpu=name"
                            "--format=csv,noheader"]))]
      (and (.waitFor process 2 java.util.concurrent.TimeUnit/SECONDS)
           (zero? (.exitValue process))))
    (catch Throwable _ false)))

(defn cuda-device-visible? []
  (or (Files/exists (Paths/get "/dev/dxg" (make-array String 0))
                    (make-array LinkOption 0))
      (Files/exists (Paths/get "/dev/nvidia0" (make-array String 0))
                    (make-array LinkOption 0))
      (nvidia-smi-ready?)))

(defn cuda-provider-paths [^Path executable]
  (let [directory (.getParent executable)
        windows? (str/starts-with?
                  (str/lower-case (System/getProperty "os.name")) "windows")]
    {:cuda (.resolve directory (if windows?
                                 "onnxruntime_providers_cuda.dll"
                                 "libonnxruntime_providers_cuda.so"))
     :shared (.resolve directory (if windows?
                                   "onnxruntime_providers_shared.dll"
                                   "libonnxruntime_providers_shared.so"))}))

(defn- windows? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows"))

(defn cuda-library-directories [settings ^Path executable]
  (let [configured (map #(Paths/get ^String % (make-array String 0))
                        (:cuda-library-paths settings))]
    (vec
     (distinct
      (concat [(.getParent executable)]
              configured
              (if (windows?)
                (map #(Paths/get ^String % (make-array String 0))
                     (str/split (or (System/getenv "PATH") "") #";"))
                (map #(Paths/get ^String % (make-array String 0))
                     ["/usr/lib/wsl/lib" "/usr/local/cuda/lib64"
                      "/usr/lib/x86_64-linux-gnu" "/usr/lib64"])))))))

(defn- cudnn-present? [directories]
  (let [filename (if (windows?) "cudnn64_9.dll" "libcudnn.so.9")]
    (boolean (some #(regular-file? (.resolve ^Path % filename)) directories))))

(defn cuda-readiness
  "Return the independently inspectable prerequisites for local CUDA
  inference. Model paths are included because NextPlaid's CUDA mode loads the
  FP32 model rather than model_int8.onnx."
  [settings ^Path executable ^Path model-path]
  (let [{:keys [cuda shared] :as providers}
        (cuda-provider-paths executable)
        library-directories (cuda-library-directories settings executable)
        fp32-model (.resolve model-path "model.onnx")
        checks {:device-visible? (cuda-device-visible?)
                :cuda-provider-present? (regular-file? cuda)
                :shared-provider-present? (regular-file? shared)
                :cudnn-present? (cudnn-present? library-directories)
                :fp32-model-present? (regular-file? fp32-model)}]
    (assoc checks
           :ready? (every? true? (vals checks))
           :provider-paths (update-vals providers str)
           :library-paths (mapv str library-directories)
           :model-path (str fp32-model))))

(defn- unavailable-reasons [readiness]
  (cond-> []
    (not (:device-visible? readiness)) (conj :cuda-device-not-visible)
    (not (:cuda-provider-present? readiness)) (conj :cuda-provider-missing)
    (not (:shared-provider-present? readiness)) (conj :shared-provider-missing)
    (not (:cudnn-present? readiness)) (conj :cudnn-missing)
    (not (:fp32-model-present? readiness)) (conj :fp32-model-missing)))

(defn resolve-runtime
  "Resolve requested accelerator and precision into NextPlaid arguments.

  Explicit CUDA fails closed. Auto uses CUDA only when all locally testable
  prerequisites exist, otherwise it records why it selected CPU."
  [settings ^Path executable ^Path model-path]
  (let [requested-accelerator (:accelerator settings)
        requested-quantization (:quantization settings)
        readiness (cuda-readiness settings executable model-path)
        accelerator (case requested-accelerator
                      :cpu :cpu
                      :cuda (if (:ready? readiness)
                              :cuda
                              (throw
                               (ex-info
                                "CUDA was requested but its verified runtime prerequisites are unavailable"
                                {:type :accelerator/cuda-unavailable
                                 :reasons (unavailable-reasons readiness)
                                 :readiness readiness})))
                      :auto (if (:ready? readiness) :cuda :cpu))
        quantization (case requested-quantization
                       :auto (if (= :cuda accelerator) :fp32 :int8)
                       requested-quantization)
        _ (when (and (= :cuda accelerator) (= :int8 quantization))
            (throw (ex-info "NextPlaid CUDA requires the FP32 model"
                            {:type :accelerator/incompatible-quantization
                             :accelerator accelerator
                             :quantization quantization})))
        model-file (.resolve model-path
                             (if (= :int8 quantization)
                               "model_int8.onnx"
                               "model.onnx"))]
    (when-not (regular-file? model-file)
      (throw (ex-info "The model artifact required by the selected runtime is missing"
                      {:type :accelerator/model-artifact-missing
                       :accelerator accelerator
                       :quantization quantization
                       :path (str model-file)})))
    (cond->
     {:requested-accelerator requested-accelerator
      :accelerator accelerator
      :requested-quantization requested-quantization
      :quantization quantization
      :encoding-sessions (if (= :cuda accelerator)
                           (:cuda-encoding-sessions settings)
                           (:encoding-sessions settings))
      :encoding-batch-size (if (= :cuda accelerator)
                             (:cuda-encoding-batch-size settings)
                             (:encoding-batch-size settings))
      :arguments (cond-> []
                   (= :cuda accelerator) (conj "--cuda")
                   (= :int8 quantization) (conj "--int8"))
      :fallback-reasons (when (and (= :auto requested-accelerator)
                                   (= :cpu accelerator))
                          (unavailable-reasons readiness))
      :cuda-readiness readiness}
      (:update-concurrency settings)
      (assoc :update-concurrency
             (if (= :cuda accelerator)
               (:cuda-update-concurrency settings)
               (:update-concurrency settings))))))

(defn configure-process-environment!
  "Prepend configured CUDA dependency directories to a child process only."
  [^ProcessBuilder builder settings ^Path executable]
  (when (seq (:cuda-library-paths settings))
    (let [environment (.environment builder)
          variable (if (windows?) "PATH" "LD_LIBRARY_PATH")
          separator java.io.File/pathSeparator
          configured (map str (cuda-library-directories settings executable))
          existing (.get environment variable)]
      (.put environment variable
            (str/join separator (cond-> (vec configured)
                                  (seq existing) (conj existing))))))
  builder)

(defn describe [selection]
  (str (name (:accelerator selection)) "/"
       (name (:quantization selection))
       (when-let [reasons (seq (:fallback-reasons selection))]
         (str " (auto fallback: "
              (str/join ", " (map name reasons)) ")"))))
