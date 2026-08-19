(ns llm-context.semantic.artifacts-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.semantic.artifacts :as artifacts])
  (:import [java.nio.file Files OpenOption StandardOpenOption]))

(deftest model-verification-reports-missing-and-mismatched-files
  (let [directory
        (Files/createTempDirectory
         "llm-context-model-verification-"
         (make-array java.nio.file.attribute.FileAttribute 0))
        first-file (first (keys artifacts/model-files))]
    (Files/writeString
     (.resolve directory first-file) "not the model"
     (into-array OpenOption [StandardOpenOption/CREATE
                             StandardOpenOption/WRITE]))
    (let [result (artifacts/verify-model directory)]
      (is (= [first-file] (:mismatched result)))
      (is (= (dec (count artifacts/model-files))
             (count (:missing result)))))))

(deftest pinned-versions-match-the-runtime-contract
  (let [defaults (config/defaults)
        settings (get-in defaults [:semantic :lateon-code])
        router (get-in defaults [:context :query-router])]
    (is (= "1.7.0" artifacts/next-plaid-version))
    (is (= "mixedbread-ai/mxbai-edge-colbert-v0-32m"
           artifacts/query-router-model-id))
    (is (= 40 (count artifacts/query-router-model-revision)))
    (is (= "1.29.0" artifacts/onnx-runtime-version))
    (is (= artifacts/next-plaid-version
           (:next-plaid-version settings)))
    (is (= artifacts/model-id (:model settings)))
    (is (= artifacts/model-revision (:model-revision settings)))
    (is (= artifacts/query-router-model-id (:model router)))
    (is (= artifacts/query-router-model-revision
           (:model-revision router)))
    (is (= artifacts/next-plaid-version (:next-plaid-version router)))
    (is (= 40 (count artifacts/model-revision)))
    (is (= 6 (count artifacts/model-files)))
    (is (= 6 (count artifacts/query-router-model-files)))))
