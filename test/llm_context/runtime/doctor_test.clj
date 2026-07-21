(ns llm-context.runtime.doctor-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.runtime.doctor :as doctor])
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
    (is (:ok? (first (filter #(= :datalevin (:check %)) checks))))))
