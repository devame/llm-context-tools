(ns llm-context.supervisor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm-context.project :as project]
            [llm-context.supervisor :as supervisor])
  (:import [java.nio.file Files]))

(defn- fixture []
  (let [root (Files/createTempDirectory
              "llm-context-supervisor-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        executable (.resolve root "llm-context")]
    (Files/writeString executable "fixture"
                       (make-array java.nio.file.OpenOption 0))
    (.setExecutable (.toFile executable) true)
    [(project/context (str root)) (str executable)]))

(deftest renders-host-native-restart-and-resource-controls
  (let [[project executable] (fixture)]
    (testing "systemd"
      (let [output (supervisor/render
                    project {:format :systemd :executable executable})]
        (is (str/includes? output "Restart=on-failure"))
        (is (str/includes? output "TasksMax=64"))
        (is (str/includes? output "StandardOutput=journal"))))
    (testing "launchd"
      (let [output (supervisor/render
                    project {:format :launchd :executable executable})]
        (is (str/includes? output "<key>KeepAlive</key>"))
        (is (str/includes? output "<key>ThrottleInterval</key>"))
        (is (str/includes? output "<key>NumberOfFiles</key>"))))
    (testing "Windows Task Scheduler"
      (let [output (supervisor/render
                    project {:format :windows :executable executable})]
        (is (str/includes? output "-RestartCount 10"))
        (is (str/includes? output "-MultipleInstances IgnoreNew"))))))
