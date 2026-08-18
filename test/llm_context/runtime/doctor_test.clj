(ns llm-context.runtime.doctor-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.runtime.doctor :as doctor]
            [llm-context.service.client :as service-client])
  (:import [java.nio.file Files]))

(deftest java-version-parsing
  (is (= 8 (doctor/java-feature "1.8.0_402")))
  (is (= 21 (doctor/java-feature "21.0.7")))
  (is (= 25 (doctor/java-feature "25-ea"))))

(deftest required-and-optional-health
  (is (doctor/healthy? [{:required? true :ok? true}
                        {:required? false :ok? false}]))
  (is (not (doctor/healthy? [{:required? true :ok? false}]))))

(deftest doctor-renders-degraded-optional-checks-as-warnings
  (let [output (with-out-str
                 (doctor/print-report
                  [{:check :semantic-accelerator
                    :required? false
                    :ok? true
                    :warning? true
                    :detail "cpu/int8 (auto fallback: cudnn-missing)"}]))]
    (is (re-find #"warn  semantic-accelerator" output))))

(deftest embedded-store-check-is-live
  (let [root (Files/createTempDirectory "llm-context-doctor-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        checks (doctor/check (project/context (str root)) (config/defaults))]
    (is (:ok? (first (filter #(= :datalevin (:check %)) checks))))
    (is (= #{:next-plaid-api :onnx-runtime :lateon-model :semantic-accelerator
             :cuda-host
             :query-router-model :project-service}
           (->> checks
                (remove :required?)
                (map :check)
                (filter #{:next-plaid-api :onnx-runtime
                          :lateon-model :semantic-accelerator :cuda-host
                          :query-router-model :project-service})
                set)))))

(deftest failed-semantic-worker-makes-project-service-check-actionable
  (let [root (Files/createTempDirectory
              "llm-context-doctor-worker-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))]
    (with-redefs [service-client/available? (constantly true)
                  service-client/request
                  (fn [_ _]
                    {:ok true
                     :value
                     {:runtime {:status :ready
                                :worker-status :failed
                                :worker-detail "fixture decoding failed"}}})]
      (let [service-check
            (first (filter #(= :project-service (:check %))
                           (doctor/check project (config/defaults))))]
        (is (false? (:ok? service-check)))
        (is (= (str "running; LateOn ready; worker failed: "
                    "fixture decoding failed")
               (:detail service-check)))))))

(deftest failed-semantic-runtime-includes-provider-diagnostic
  (let [root (Files/createTempDirectory
              "llm-context-doctor-runtime-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))]
    (with-redefs [service-client/available? (constantly true)
                  service-client/request
                  (fn [_ _]
                    {:ok true
                     :value
                     {:runtime {:status :failed
                                :reason :startup-failed
                                :detail "NextPlaid exited before becoming ready: CUDA support not compiled"
                                :worker-status :not-running}}})]
      (let [service-check
            (first (filter #(= :project-service (:check %))
                           (doctor/check project (config/defaults))))]
        (is (false? (:ok? service-check)))
        (is (re-find #"CUDA support not compiled"
                     (:detail service-check)))))))

(deftest ready-semantic-runtime-provider-warning-is-actionable
  (let [root (Files/createTempDirectory
              "llm-context-doctor-runtime-warning-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))]
    (with-redefs [service-client/available? (constantly true)
                  service-client/request
                  (fn [_ _]
                    {:ok true
                     :value
                     {:runtime {:status :ready
                                :worker-status :running
                                :runtime-diagnostic
                                {:detail "CUDA was selected, but the runtime could not detect a CUDA-capable device."
                                 :action "fix NVIDIA/WSL CUDA device visibility"}}}})]
      (let [service-check
            (first (filter #(= :project-service (:check %))
                           (doctor/check project (config/defaults))))]
        (is (:ok? service-check))
        (is (:warning? service-check))
        (is (re-find #"fix NVIDIA/WSL CUDA device visibility"
                     (:detail service-check)))))))
