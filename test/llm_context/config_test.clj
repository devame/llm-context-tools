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
    (is (= 4000 (get-in loaded [:store :max-transaction-weight])))
    (is (= 10737418240
           (get-in loaded [:store :minimum-free-space-bytes])))
    (is (nil? (get-in loaded [:store :free-space-probe-path])))
    (is (= "lightonai/LateOn-Code"
           (get-in loaded [:semantic :lateon-code :model])))
    (is (= 40
           (count (get-in loaded [:semantic :lateon-code :model-revision]))))
    (is (= ["next-plaid-api"]
           (get-in loaded [:semantic :lateon-code :next-plaid-command])))
    (is (= :auto (get-in loaded [:semantic :lateon-code :accelerator])))
    (is (= :auto (get-in loaded [:semantic :lateon-code :quantization])))
    (is (= 2048
           (get-in loaded [:semantic :lateon-code :model-document-length])))
    (is (= 4
           (get-in loaded [:semantic :lateon-code :update-concurrency])))
    (is (= 120000
           (get-in loaded [:semantic :lateon-code :startup-timeout-ms])))
    (is (= 4 (get-in loaded [:context :trace-depth])))
    (is (= 200 (get-in loaded [:context :trace-limit])))
    (is (= :auto (get-in loaded [:context :intent-source-preference])))
    (is (= :auto (get-in loaded [:context :intent-seed-mode])))
    (is (= 4 (get-in loaded [:context :intent-max-seeds])))
    (is (true? (get-in loaded [:context :intent-rerank])))
    (is (= 100 (get-in loaded [:context :intent-candidate-count])))
    (is (true? (get-in loaded [:context :candidate-reranker :enabled])))
    (is (= :shadow (get-in loaded [:context :candidate-reranker :mode])))
    (is (= 50 (get-in loaded [:context :candidate-reranker
                              :candidate-count])))
    (is (= 5000 (get-in loaded [:context :candidate-reranker
                                :query-timeout-ms])))
    (is (= "mixedbread-ai/mxbai-edge-colbert-v0-32m"
           (get-in loaded [:context :query-router :model])))
    (is (= :cpu (get-in loaded [:context :query-router :accelerator])))
    (is (= :int8 (get-in loaded [:context :query-router :quantization])))
    (is (= 250 (get-in loaded [:context :query-router :query-timeout-ms])))
    (is (= 0.02 (get-in loaded [:context :query-router :minimum-margin])))
    (is (= [] (get-in loaded [:context :source-role-overrides])))))

(deftest trace-bounds-must-be-positive
  (let [context (temp-project)]
    (spit (str (:config-file context))
          "{:context {:trace-depth 0 :trace-limit -1}}")
    (let [error (try (config/load-config context) nil
                     (catch clojure.lang.ExceptionInfo error error))]
      (is (= 2 (:exit-code (ex-data error))))
      (is (= #{":context/:trace-depth must be a positive integer"
               ":context/:trace-limit must be a positive integer"}
             (set (:errors (ex-data error))))))))

(deftest storage-safety-settings-are-validated
  (let [context (temp-project)]
    (spit (str (:config-file context))
          (pr-str {:store {:minimum-free-space-bytes -1
                           :free-space-probe-path ""}}))
    (let [errors (:errors
                  (ex-data
                   (try (config/load-config context)
                        nil
                        (catch clojure.lang.ExceptionInfo error error))))]
      (is (= #{":store/:minimum-free-space-bytes must be a non-negative integer"
               ":store/:free-space-probe-path must be nil or a non-blank path"}
             (set errors))))))

(deftest user-configuration-deep-merges
  (let [context (temp-project)]
    (spit (str (:config-file context)) "{:analysis {:include [\"lib\"]}}")
    (let [loaded (config/load-config context)]
      (is (= ["lib"] (get-in loaded [:analysis :include])))
      (is (pos-int? (get-in loaded [:analysis :max-file-bytes]))))))

(deftest source-role-settings-are-validated-and-ordered
  (let [context (temp-project)]
    (spit (str (:config-file context))
          (pr-str {:context
                   {:intent-source-preference :production
                    :source-role-overrides
                    [{:role :test :pattern "quality/**"}
                     {:role :production :pattern "quality/runtime/**"}]}}))
    (let [loaded (config/load-config context)]
      (is (= :production
             (get-in loaded [:context :intent-source-preference])))
      (is (= [:test :production]
             (mapv :role
                   (get-in loaded [:context :source-role-overrides]))))))
  (let [context (temp-project)]
    (spit (str (:config-file context))
          (pr-str {:context
                   {:intent-source-preference :sometimes
                    :source-role-overrides
                    [{:role :mystery :pattern "mystery/**"}
                     {:role :test}]}}))
    (let [errors (:errors
                  (ex-data
                   (try (config/load-config context)
                        nil
                        (catch clojure.lang.ExceptionInfo error error))))]
      (is (some #(re-find #"intent-source-preference" %) errors))
      (is (some #(re-find #"source-role-overrides" %) errors)))))

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
          (pr-str {:analysis {:include :everything :max-file-bytes 0}
                   :service {:watch :sometimes
                             :watch-initial :sometimes
                             :watch-debounce-ms 0}}))
    (let [error (try (config/load-config context) nil
                     (catch clojure.lang.ExceptionInfo error error))]
      (is (= 2 (:exit-code (ex-data error))))
      (is (= 5 (count (:errors (ex-data error))))))))

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

(deftest accelerator-and-quantization-settings-are-validated
  (let [context (temp-project)]
    (spit (str (:config-file context))
          (pr-str {:semantic {:lateon-code {:accelerator :cuda
                                            :quantization :int8}}
                   :context {:query-router {:accelerator :tpu
                                            :quantization :binary}}}))
    (let [errors (:errors
                  (ex-data
                   (try (config/load-config context)
                        nil
                        (catch clojure.lang.ExceptionInfo error error))))]
      (is (some #(re-find #"lateon-code CUDA cannot use" %) errors))
      (is (some #(re-find #"query-router/:accelerator" %) errors))
      (is (some #(re-find #"query-router/:quantization" %) errors)))))
