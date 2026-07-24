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
  (let [settings (get-in (config/defaults) [:semantic :lateon-code])]
    (is (= "1.6.4" artifacts/next-plaid-version))
    (is (= "1.23.0" artifacts/onnx-runtime-version))
    (is (= artifacts/next-plaid-version
           (:next-plaid-version settings)))
    (is (= artifacts/model-id (:model settings)))
    (is (= artifacts/model-revision (:model-revision settings)))
    (is (= 40 (count artifacts/model-revision)))
    (is (= 5 (count artifacts/model-files)))))
