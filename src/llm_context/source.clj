(ns llm-context.source
  "Deterministic source-byte decoding shared by structural and semantic paths."
  (:import [java.nio ByteBuffer]
           [java.nio.charset CharacterCodingException CodingErrorAction
            StandardCharsets]
           [java.nio.file Files Path]))

(defn decode-utf8
  "Decode source bytes as UTF-8. Malformed input is replaced with U+FFFD so
  every analysis stage sees identical text; the result records the first bad
  byte offset for an actionable diagnostic."
  [^bytes bytes]
  (let [input (ByteBuffer/wrap bytes)
        decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (try
      {:content (str (.decode decoder input))
       :malformed? false}
      (catch CharacterCodingException _
        {:content (String. bytes StandardCharsets/UTF_8)
         :malformed? true
         :malformed-offset (.position input)}))))

(defn read-utf8 [^Path path]
  (decode-utf8 (Files/readAllBytes path)))
