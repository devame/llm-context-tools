(ns llm-context.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm-context.cli :as cli]
            [llm-context.main :as main]
            [llm-context.project :as project]))

(deftest basic-command-routing
  (testing "help is the default"
    (let [out (with-out-str (is (zero? (main/run []))))]
      (is (str/includes? out "Usage:"))))
  (testing "version is printable"
    (is (= "0.4.0\n" (with-out-str (main/run ["version"])))))
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
