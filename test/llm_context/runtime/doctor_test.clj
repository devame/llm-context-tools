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

(deftest embedded-store-check-is-live
  (let [root (Files/createTempDirectory "llm-context-doctor-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        checks (doctor/check (project/context (str root)) (config/defaults))]
    (is (:ok? (first (filter #(= :datalevin (:check %)) checks))))
    (is (= #{:next-plaid-api :onnx-runtime :lateon-model :project-service}
           (->> checks
                (remove :required?)
                (map :check)
                (filter #{:next-plaid-api :onnx-runtime
                          :lateon-model :project-service})
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
