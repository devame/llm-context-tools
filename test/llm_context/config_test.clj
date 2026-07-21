(ns llm-context.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.config :as config]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(defn temp-project []
  (project/context (str (Files/createTempDirectory "llm-context-config-"
                                                   (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest defaults-are-valid
  (let [loaded (config/load-config (temp-project))]
    (is (= ["src" "test"] (get-in loaded [:analysis :include])))
    (is (= ".llm-context/db" (get-in loaded [:store :path])))))

(deftest user-configuration-deep-merges
  (let [context (temp-project)]
    (spit (str (:config-file context)) "{:analysis {:include [\"lib\"]}}")
    (let [loaded (config/load-config context)]
      (is (= ["lib"] (get-in loaded [:analysis :include])))
      (is (pos-int? (get-in loaded [:analysis :max-file-bytes]))))))

(deftest init-never-overwrites
  (let [context (temp-project)]
    (config/init! context)
    (is (Files/exists (:config-file context)
                      (make-array java.nio.file.LinkOption 0)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"already exists"
                          (config/init! context)))))

(deftest invalid-settings-are-reported-together
  (let [context (temp-project)]
    (spit (str (:config-file context))
          "{:analysis {:include :everything :max-file-bytes 0}}")
    (let [error (try (config/load-config context) nil
                     (catch clojure.lang.ExceptionInfo error error))]
      (is (= 2 (:exit-code (ex-data error))))
      (is (= 2 (count (:errors (ex-data error))))))))
