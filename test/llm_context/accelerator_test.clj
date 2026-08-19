(ns llm-context.accelerator-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.accelerator :as accelerator])
  (:import [java.nio.file Files Path]))

(defn- temp-layout []
  (let [root (Files/createTempDirectory
              "llm-context-accelerator-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        executable (.resolve root "next-plaid-api")
        model (.resolve root "model")]
    (Files/createDirectories model
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString executable "runtime" (make-array java.nio.file.OpenOption 0))
    {:root root :executable executable :model model}))

(defn- touch [^Path path]
  (Files/writeString path "artifact" (make-array java.nio.file.OpenOption 0)))

(def complete-host
  {:device-visible? true
   :driver-present? true
   :driver-compatible? true
   :libcuda-present? true
   :cuda-runtime-present? true
   :cudnn-present? true
   :ready? true
   :wsl? false
   :gpu-name "Test GPU"
   :driver-version "591.86"
   :minimum-driver accelerator/minimum-cuda-driver})

(deftest auto-falls-back-to-cpu-int8-with-visible-reasons
  (let [{:keys [executable model]} (temp-layout)]
    (touch (.resolve model "model_int8.onnx"))
    (with-redefs [accelerator/cuda-device-visible? (constantly false)]
      (let [selection (accelerator/resolve-runtime
                       {:accelerator :auto :quantization :auto
                        :encoding-sessions 4 :encoding-batch-size 1
                        :cuda-encoding-sessions 1
                        :cuda-encoding-batch-size 8
                        :update-concurrency 4 :cuda-update-concurrency 1}
                       executable model)]
        (is (= :cpu (:accelerator selection)))
        (is (= :int8 (:quantization selection)))
        (is (= ["--int8"] (:arguments selection)))
        (is (= 4 (:encoding-sessions selection)))
        (is (= 1 (:encoding-batch-size selection)))
        (is (= 4 (:update-concurrency selection)))
        (is (some #{:cuda-device-not-visible}
                  (:fallback-reasons selection)))))))

(deftest explicit-cuda-fails-closed-when-runtime-is-incomplete
  (let [{:keys [executable model]} (temp-layout)]
    (touch (.resolve model "model.onnx"))
    (with-redefs [accelerator/cuda-device-visible? (constantly true)
                  accelerator/cuda-readiness
                  (constantly {:device-visible? true
                               :cuda-provider-present? false
                               :shared-provider-present? false
                               :cudnn-present? false
                               :fp32-model-present? true
                               :ready? false})]
      (let [error (try
                    (accelerator/resolve-runtime
                     {:accelerator :cuda :quantization :fp32
                      :encoding-sessions 4 :encoding-batch-size 1
                      :cuda-encoding-sessions 1
                      :cuda-encoding-batch-size 8
                      :update-concurrency 4 :cuda-update-concurrency 1}
                     executable model)
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= :accelerator/cuda-unavailable (:type (ex-data error))))
        (is (= #{:cuda-provider-missing :shared-provider-missing
                 :cudnn-missing}
               (set (:reasons (ex-data error)))))))))

(deftest complete-cuda-selection-emits-only-cuda-switch
  (let [{:keys [executable model root]} (temp-layout)]
    (doseq [path [(.resolve model "model.onnx")
                  (.resolve root "libonnxruntime_providers_cuda.so")
                  (.resolve root "libonnxruntime_providers_shared.so")
                  (.resolve root "libcudnn.so.9")]]
      (touch path))
    (with-redefs [accelerator/cuda-host-readiness (constantly complete-host)]
      (let [selection (accelerator/resolve-runtime
                       {:accelerator :auto :quantization :auto
                        :encoding-sessions 4 :encoding-batch-size 1
                        :cuda-encoding-sessions 1
                        :cuda-encoding-batch-size 8
                        :update-concurrency 4 :cuda-update-concurrency 1}
                       executable model)]
        (is (= :cuda (:accelerator selection)))
        (is (= :fp32 (:quantization selection)))
        (is (= ["--cuda"] (:arguments selection)))
        (is (= 1 (:encoding-sessions selection)))
        (is (= 8 (:encoding-batch-size selection)))
        (is (= 1 (:update-concurrency selection)))
        (is (nil? (:fallback-reasons selection)))))))

(deftest host-description-surfaces-corrective-action
  (let [description (accelerator/describe-host
                     (assoc complete-host
                            :driver-present? false
                            :driver-compatible? false
                            :cudnn-present? false
                            :ready? false))]
    (is (re-find #"GPU: Test GPU" description))
    (is (re-find #"NVIDIA driver: 591[.]86" description))
    (is (re-find #"cuDNN 9: missing" description))
    (is (re-find #"install an NVIDIA driver" description))
    (is (not (re-find #"install cuDNN 9 and expose" description)))))

(deftest cuda-dependency-installation-is-offered-for-compatible-gpu
  (is (accelerator/cudnn-installation-eligible?
       (assoc complete-host :cudnn-present? false)))
  (is (accelerator/cuda-dependency-installation-eligible?
       (assoc complete-host
              :cuda-runtime-present? false
              :cudnn-present? false)))
  (doseq [host [(assoc complete-host :device-visible? false :cudnn-present? false)
                (assoc complete-host :driver-present? false :cudnn-present? false)
                (assoc complete-host :driver-compatible? false :cudnn-present? false)
                complete-host]]
    (is (not (accelerator/cudnn-installation-eligible? host))))
  (is (not (some #{"install cuDNN 9"}
                 (accelerator/host-actions
                  (assoc complete-host
                         :device-visible? false
                         :cudnn-present? false)))))
  (is (some #{"install cuDNN 9 and expose libcudnn.so.9"}
            (accelerator/host-actions
             (assoc complete-host
                    :cuda-runtime-present? false
                    :cudnn-present? false)))))

(deftest selected-model-artifact-must-exist
  (let [{:keys [executable model]} (temp-layout)
        error (try
                (accelerator/resolve-runtime
                 {:accelerator :cpu :quantization :fp32
                  :encoding-sessions 4 :encoding-batch-size 1
                  :cuda-encoding-sessions 1
                  :cuda-encoding-batch-size 8
                  :update-concurrency 4 :cuda-update-concurrency 1}
                 executable model)
                nil
                (catch clojure.lang.ExceptionInfo error error))]
    (is (= :accelerator/model-artifact-missing (:type (ex-data error))))))
