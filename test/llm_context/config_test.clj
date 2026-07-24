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
    (is (= ["."] (get-in loaded [:analysis :include])))
    (is (= ".llm-context/db" (get-in loaded [:store :path])))
    (is (= "lightonai/LateOn-Code"
           (get-in loaded [:semantic :lateon-code :model])))
    (is (= 40
           (count (get-in loaded [:semantic :lateon-code :model-revision]))))
    (is (= ["next-plaid-api"]
           (get-in loaded [:semantic :lateon-code :next-plaid-command])))
    (is (= 2048
           (get-in loaded [:semantic :lateon-code :model-document-length])))
    (is (= 120000
           (get-in loaded [:semantic :lateon-code :startup-timeout-ms])))))

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

(deftest invalid-lateon-settings-are-reported-together
  (let [context (temp-project)]
    (spit (str (:config-file context))
          (pr-str {:semantic
                   {:providers "lateon"
                    :lateon-code
                    {:model-revision "main"
                     :next-plaid-command []
                     :model-document-length 0
                     :startup-timeout-ms 0
                     :query-timeout-ms 0
                     :centroid-score-threshold -1}}}))
    (let [error (try (config/load-config context) nil
                     (catch clojure.lang.ExceptionInfo error error))
          errors (:errors (ex-data error))]
      (is (= 2 (:exit-code (ex-data error))))
      (is (some #(re-find #":providers" %) errors))
      (is (some #(re-find #":model-revision" %) errors))
      (is (some #(re-find #":next-plaid-command" %) errors))
      (is (some #(re-find #":model-document-length" %) errors))
      (is (some #(re-find #":startup-timeout-ms" %) errors))
      (is (some #(re-find #":query-timeout-ms" %) errors))
      (is (some #(re-find #":centroid-score-threshold" %) errors)))))
