(ns llm-context.service.progress-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.project :as project]
            [llm-context.service.progress :as progress])
  (:import [java.nio.file Files LinkOption]))

(deftest progress-is-readable-as-an-atomic-durable-snapshot
  (let [root (Files/createTempDirectory
              "llm-context-analysis-progress-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (project/context (str root))
        handle (progress/create context)]
    (is (= :idle (:state (progress/read-state context))))
    (progress/begin! handle :full-analysis)
    (progress/record! handle {:stage :parse-progress
                              :completed 3
                              :total 10
                              :file "src/example.clj"})
    (let [snapshot (progress/read-state context)]
      (is (= :running (:state snapshot)))
      (is (= :full-analysis (:operation snapshot)))
      (is (= :parse-progress (:stage snapshot)))
      (is (= 3 (:completed snapshot)))
      (is (Files/exists (progress/path context)
                        (make-array LinkOption 0))))
    (progress/complete! handle {:mode :full :files 10 :entities 42})
    (let [snapshot (progress/read-state context)]
      (is (= :complete (:state snapshot)))
      (is (= {:mode :full :files 10 :entities 42}
             (:result snapshot)))
      (is (nil? (:last-error snapshot))))))

(deftest a-new-service-marks-an-abandoned-analysis-interrupted
  (let [root (Files/createTempDirectory
              "llm-context-analysis-progress-restart-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (project/context (str root))
        first-handle (progress/create context)]
    (progress/begin! first-handle :full-analysis)
    (let [restarted (progress/create context)
          snapshot (progress/snapshot restarted)]
      (is (= :interrupted (:state snapshot)))
      (is (= "The previous analysis process stopped before completion"
             (:last-error snapshot)))
      (is (:finished-at snapshot)))))
