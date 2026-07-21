(ns llm-context.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm-context.main :as main]))

(deftest basic-command-routing
  (testing "help is the default"
    (let [out (with-out-str (is (zero? (main/run []))))]
      (is (str/includes? out "Usage:"))))
  (testing "version is printable"
    (is (str/includes? (with-out-str (main/run ["version"])) "0.4.0")))
  (testing "unknown commands are usage errors"
    (is (= 2 (main/run ["no-such-command"])))))
