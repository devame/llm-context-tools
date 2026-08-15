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
(def query-router-model-id "mixedbread-ai/mxbai-edge-colbert-v0-32m")
(def query-router-model-revision
  "963e23afa1478d8bcc12e5d7115adcfdbd22c3af")

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

(def query-router-model-files
  {"model_int8.onnx"
   "264ba680e960af9fffb4f78c3af1e4ff92520678b8e136c79434d88fb2549e1b"
   "tokenizer.json"
   "594291000b476c98ed600cbb1914ff128c79642a9433aac86213c7a5562d7c1a"
   "config_sentence_transformers.json"
   "0c4eb4090ff55ddee69380ad5ea88a3a89500651996a56953af72bafdb7965b6"
   "config.json"
   "a60a035a715a686dca530cf41da553a571e26ea45288d04d750b9da1a27c268d"
   "onnx_config.json"
   "e10f017e4a8355f6b15f5be5f67295c90d5b25e487568bf0b0d9ee3259dc0eb7"})

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
