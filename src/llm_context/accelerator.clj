(ns llm-context.accelerator
  "Resolve a NextPlaid inference device without confusing device visibility
  with a usable CUDA-enabled ONNX Runtime installation."
  (:require [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path Paths]))

(def accelerators #{:auto :cpu :cuda})
(def quantizations #{:auto :int8 :fp32})
(def minimum-cuda-driver "525.60.13")

(defn- regular-file? [^Path path]
  (Files/isRegularFile path (make-array LinkOption 0)))

(defn- windows? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows"))

(defn- nvidia-smi-candidates []
  (if (windows?)
    ["nvidia-smi.exe" "nvidia-smi"]
    ["nvidia-smi" "/usr/lib/wsl/lib/nvidia-smi"]))

(defn- nvidia-smi-info []
  (some
   (fn [command]
     (try
       (let [process (.start (ProcessBuilder.
                              ^java.util.List
                              [command "--query-gpu=name,driver_version"
                               "--format=csv,noheader,nounits"]))]
         (if (and (.waitFor process 2 java.util.concurrent.TimeUnit/SECONDS)
                  (zero? (.exitValue process)))
           (let [gpus (->> (slurp (.getInputStream process))
                           str/split-lines
                           (keep (fn [line]
                                   (let [[name driver] (str/split line #",\s*" 2)]
                                     (when (and (seq name) (seq driver))
                                       {:name (str/trim name)
                                        :driver-version (str/trim driver)}))))
                           vec)]
             (when (seq gpus)
               {:path command
                :gpus gpus
                :gpu-name (:name (first gpus))
                :driver-version (:driver-version (first gpus))}))
           nil))
     (catch Throwable _ nil)))
   (nvidia-smi-candidates)))

(defn- nvidia-smi-ready? []
  (some? (nvidia-smi-info)))

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

(defn cuda-library-directories [settings ^Path executable]
  (let [configured (map #(Paths/get ^String % (make-array String 0))
                        (:cuda-library-paths settings))]
    (vec
     (distinct
      (concat (when executable [(.getParent executable)])
              configured
              (if (windows?)
                (map #(Paths/get ^String % (make-array String 0))
                     (str/split (or (System/getenv "PATH") "") #";"))
                (map #(Paths/get ^String % (make-array String 0))
                      ["/usr/lib/wsl/lib" "/usr/local/cuda/lib64"
                       "/usr/lib/x86_64-linux-gnu" "/usr/lib64"])))))))

(defn- version-components [version]
  (when (and (string? version) (re-matches #"[0-9]+(?:[.][0-9]+){0,2}" version))
    (mapv #(Long/parseLong (or % "0"))
          (take 3 (concat (str/split version #"[.]")
                          ["0" "0" "0"])))))

(defn- version-at-least? [actual minimum]
  (when-let [actual (version-components actual)]
    (let [minimum (version-components minimum)]
      (when minimum
        (loop [index 0]
          (cond
            (= index 3) true
            (> (nth actual index) (nth minimum index)) true
            (< (nth actual index) (nth minimum index)) false
            :else (recur (inc index))))))))

(defn- library-present? [directories filename]
  (boolean (some #(regular-file? (.resolve ^Path % filename)) directories)))

(defn cuda-host-readiness
  "Inspect host GPU/driver prerequisites without requiring the packaged
  NextPlaid provider files. This is intentionally separate from the runtime
  probe: a visible GPU and a successful `nvidia-smi` call still do not prove
  that an ONNX CUDA provider can initialize."
  [settings ^Path executable]
  (let [directories (cuda-library-directories settings executable)
        smi (nvidia-smi-info)
        wsl? (Files/exists (Paths/get "/dev/dxg" (make-array String 0))
                           (make-array LinkOption 0))
        checks {:device-visible? (cuda-device-visible?)
                :driver-present? (some? smi)
                :driver-compatible? (and smi
                                         (version-at-least?
                                          (:driver-version smi)
                                          minimum-cuda-driver))
                :libcuda-present? (if (windows?)
                                    true
                                    (library-present? directories "libcuda.so.1"))
                :cuda-runtime-present? (if (windows?)
                                         true
                                         (library-present? directories "libcudart.so.12"))
                :cudnn-present? (if (windows?)
                                  (library-present? directories "cudnn64_9.dll")
                                  (library-present? directories "libcudnn.so.9"))}]
    (assoc checks
           :ready? (every? true? (vals checks))
           :wsl? wsl?
           :nvidia-smi smi
           :gpu-name (:gpu-name smi)
           :driver-version (:driver-version smi)
           :minimum-driver minimum-cuda-driver
           :library-paths (mapv str directories))))

(defn cuda-readiness
  "Return the independently inspectable prerequisites for local CUDA
  inference. Model paths are included because NextPlaid's CUDA mode loads the
  FP32 model rather than model_int8.onnx."
  [settings ^Path executable ^Path model-path]
  (let [{:keys [cuda shared] :as providers}
        (cuda-provider-paths executable)
        host (cuda-host-readiness settings executable)
        fp32-model (.resolve model-path "model.onnx")
        checks (merge (select-keys host [:device-visible?
                                        :driver-present?
                                        :driver-compatible?
                                        :libcuda-present?
                                        :cuda-runtime-present?
                                        :cudnn-present?])
                      {:cuda-provider-present? (regular-file? cuda)
                       :shared-provider-present? (regular-file? shared)
                       :fp32-model-present? (regular-file? fp32-model)})]
    (assoc checks
           :ready? (every? true? (vals checks))
           :provider-paths (update-vals providers str)
           :library-paths (:library-paths host)
           :model-path (str fp32-model)
           :host host)))

(defn- unavailable-reasons [readiness]
  (cond-> []
    (not (:device-visible? readiness)) (conj :cuda-device-not-visible)
    (and (contains? readiness :driver-present?)
         (not (:driver-present? readiness))) (conj :cuda-driver-missing)
    (and (:driver-present? readiness)
         (not (:driver-compatible? readiness))) (conj :cuda-driver-too-old)
    (and (contains? readiness :libcuda-present?)
         (not (:libcuda-present? readiness))) (conj :cuda-driver-library-missing)
    (and (contains? readiness :cuda-runtime-present?)
         (not (:cuda-runtime-present? readiness))) (conj :cuda-runtime-missing)
    (not (:cuda-provider-present? readiness)) (conj :cuda-provider-missing)
    (not (:shared-provider-present? readiness)) (conj :shared-provider-missing)
    (not (:cudnn-present? readiness)) (conj :cudnn-missing)
    (not (:fp32-model-present? readiness)) (conj :fp32-model-missing)))

(defn fallback-action [reason]
  (case reason
    :cuda-device-not-visible
    "make the NVIDIA GPU visible to this environment"
    :cuda-driver-missing
    "install an NVIDIA driver; for WSL install the Windows CUDA-enabled driver, not a Linux driver inside WSL"
    :cuda-driver-too-old
    (str "update the NVIDIA driver to " minimum-cuda-driver " or newer")
    :cuda-driver-library-missing
    "expose libcuda.so.1 from the NVIDIA driver to this process"
    :cuda-runtime-missing
    "install or expose the CUDA 12 runtime (libcudart.so.12)"
    :cuda-provider-missing
    "install a CUDA-enabled ONNX Runtime provider"
    :shared-provider-missing
    "install the ONNX Runtime shared provider library"
    :cudnn-missing
    "install cuDNN 9 and expose libcudnn.so.9"
    :fp32-model-missing
    "install the FP32 model.onnx artifact"
    (str "resolve " (name reason))))

(defn fallback-actions [reasons]
  (str/join "; " (map fallback-action reasons)))

(defn host-actions [host]
  (let [reasons (cond-> []
                  (not (:device-visible? host))
                  (conj :cuda-device-not-visible)
                  (not (:driver-present? host))
                  (conj :cuda-driver-missing)
                  (and (:driver-present? host)
                       (not (:driver-compatible? host)))
                  (conj :cuda-driver-too-old)
                  (not (:libcuda-present? host))
                  (conj :cuda-driver-library-missing)
                  (not (:cuda-runtime-present? host))
                  (conj :cuda-runtime-missing)
                  (not (:cudnn-present? host))
                  (conj :cudnn-missing))]
    (mapv fallback-action reasons)))

(defn describe-host [host]
  (let [gpu (or (:gpu-name host) "not detected")
        driver (or (:driver-version host) "not detected")
        driver-state (cond
                       (not (:driver-present? host)) "missing"
                       (not (:driver-compatible? host)) "too old"
                       :else "compatible")
        actions (host-actions host)]
    (str "GPU: " gpu
         "; device: " (if (:device-visible? host) "visible" "missing")
         "; NVIDIA driver: " driver " (" driver-state ", minimum "
         (:minimum-driver host) ")"
         "; libcuda.so.1: " (if (:libcuda-present? host) "present" "missing")
         "; WSL: " (if (:wsl? host) "yes" "no")
         "; cuDNN 9: " (if (:cudnn-present? host) "present" "missing")
         "; CUDA 12 runtime: "
         (if (:cuda-runtime-present? host) "present" "missing")
         (when (seq actions)
           (str "; action: " (str/join "; " actions))))))

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
              (str/join ", " (map name reasons))
              "; action: " (fallback-actions reasons) ")"))))
