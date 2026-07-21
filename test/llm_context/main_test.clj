(ns llm-context.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm-context.cli :as cli]
            [llm-context.config :as config]
            [llm-context.main :as main]
            [llm-context.project :as project]
            [llm-context.version :as version])
  (:import [java.nio.file Files]))

(deftest basic-command-routing
  (testing "help is the default"
    (let [out (with-out-str (is (zero? (main/run []))))]
      (is (str/includes? out "Usage:"))))
  (testing "version is printable"
    (is (= (str version/value "\n")
           (with-out-str (main/run ["version"])))))
  (testing "unknown commands are usage errors"
    (is (= 2 (main/run ["no-such-command"])))))

(deftest global-argument-parsing
  (is (= {:project "/tmp/example"
          :quiet? true
          :command "analyze"
          :args ["--full"]}
         (cli/parse-args ["--quiet" "analyze" "-C" "/tmp/example" "--full"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires a path"
                        (cli/parse-args ["analyze" "--project"]))))

(deftest project-context-is-canonical
  (let [context (project/context ".")]
    (is (.isAbsolute (:root context)))
    (is (= (.resolve (:root context) ".llm-context/db") (:db-dir context)))))

(deftest initialization-confirms-the-project-root
  (let [root (Files/createTempDirectory
              "llm-context-init-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? false})
        output (with-in-str "yes\n"
                 (with-out-str (is (zero? (cli/execute context "init" [])))))]
    (is (str/includes? output (str root)))
    (is (= ["."] (get-in (config/load-config context) [:analysis :include]))))
  (let [root (Files/createTempDirectory
              "llm-context-init-cancel-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? false})]
    (with-in-str "no\n"
      (is (zero? (cli/execute context "init" []))))
    (is (not (Files/exists (:config-file context)
                           (make-array java.nio.file.LinkOption 0)))))
  (let [root (Files/createTempDirectory
              "llm-context-init-yes-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? false})]
    (is (zero? (cli/execute context "init" ["--yes"])))
    (is (Files/exists (:config-file context)
                      (make-array java.nio.file.LinkOption 0)))))
