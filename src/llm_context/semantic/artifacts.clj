(ns llm-context.semantic.artifacts
  "Immutable third-party runtime and model artifact contract."
  (:require [llm-context.dependencies :as dependencies])
  (:import [java.io BufferedInputStream]
           [java.nio.file Files LinkOption Path]
           [java.security DigestInputStream MessageDigest]
           [java.util HexFormat]))

(def next-plaid-version
  (dependencies/value [:semantic :next-plaid :version]))
(def onnx-runtime-version
  (dependencies/value [:semantic :onnx-runtime :version]))
(def model-id
  (dependencies/value [:roles :semantic-retriever :model]))
(def model-revision
  (dependencies/value [:roles :semantic-retriever :revision]))
(def query-router-model-id
  (dependencies/value [:roles :query-router-reranker :model]))
(def query-router-model-revision
  (dependencies/value [:roles :query-router-reranker :revision]))
(def model-files
  (dependencies/value [:roles :semantic-retriever :files]))
(def query-router-model-files
  (dependencies/value [:roles :query-router-reranker :files]))

(defn sha256 [^Path path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input
                (DigestInputStream.
                 (BufferedInputStream. (Files/newInputStream path
                                                             (make-array
                                                              java.nio.file.OpenOption
                                                              0)))
                 digest)]
      (.transferTo input (java.io.OutputStream/nullOutputStream)))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn- verify-files [^Path directory files]
  (reduce-kv
   (fn [result filename expected]
     (let [path (.resolve directory filename)]
       (cond
         (not (Files/isRegularFile path (make-array LinkOption 0)))
         (update result :missing conj filename)

         (not= expected (sha256 path))
         (update result :mismatched conj filename)

         :else result)))
   {:missing [] :mismatched []}
   files))

(defn verify-model
  "Return missing and mismatched files for the immutable LateOn directory."
  [^Path directory]
  (verify-files directory model-files))

(defn verify-query-router-model
  "Return missing and mismatched files for the immutable router directory."
  [^Path directory]
  (verify-files directory query-router-model-files))
