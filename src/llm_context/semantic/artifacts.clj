(ns llm-context.semantic.artifacts
  "Immutable third-party runtime and model artifact contract."
  (:import [java.io BufferedInputStream]
           [java.nio.file Files LinkOption Path]
           [java.security DigestInputStream MessageDigest]
           [java.util HexFormat]))

(def next-plaid-version "1.6.4")
(def onnx-runtime-version "1.23.0")
(def model-id "lightonai/LateOn-Code")
(def model-revision "734b659a57935ef50562d79581c3ff1f8d825c93")

(def model-files
  {"model_int8.onnx"
   "a62a88b4e3ebb76e8bc5f0263d17b773c667d27bc73c5120e3131048dd1554ef"
   "tokenizer.json"
   "a388b94942e98e5c661c6c23f919842285738bfd123a0d148dea0c56287505d0"
   "config_sentence_transformers.json"
   "34942289dec20e285b07132aa1d09980ed776a0bc34e531dd7b49c4701876871"
   "config.json"
   "424fa6fedd42b6a78257145a6068c17cc7e67ac5d7cc3c011ed9d8141c9159d4"
   "onnx_config.json"
   "eedf90bb3b71b7500a973e140b72a736c4c5ca4b6746c1f69fcc64b29924a8d5"})

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

(defn verify-model
  "Return missing and mismatched files for an immutable model directory."
  [^Path directory]
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
   model-files))
