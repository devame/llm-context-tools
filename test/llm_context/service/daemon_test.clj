(ns llm-context.service.daemon-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.project :as project]
            [llm-context.service.daemon :as daemon])
  (:import [java.nio.file Files LinkOption]))

(deftest daemon-command-reuses-the-current-classpath-and-canonical-project
  (let [root (Files/createTempDirectory
              "llm-context-daemon-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        command (daemon/launch-command project)]
    (is (Files/isRegularFile
         (daemon/java-executable) (make-array LinkOption 0)))
    (is (= "--enable-native-access=ALL-UNNAMED" (nth command 1)))
    (is (= (System/getProperty "java.class.path") (nth command 3)))
    (is (= ["clojure.main" "-m" "llm-context.main"
            "-C" (:root-str project) "service" "foreground"]
           (subvec (vec command) 4)))))
