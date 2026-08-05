(ns llm-context.source
  "Deterministic source-byte decoding shared by authoritative analyzers."
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

(defn- utf8-width [code-point]
  (cond
    (<= code-point 0x7f) 1
    (<= code-point 0x7ff) 2
    (<= code-point 0xffff) 3
    :else 4))

(defn index
  "Build the reusable coordinate model for one normalized source string.
  Character offsets follow JVM UTF-16 indexing, matching clj-kondo columns;
  byte offsets address the normalized UTF-8 representation."
  [content]
  (let [content (or content "")
        length (count content)
        char->byte (int-array (inc length))]
    (loop [offset 0 byte-offset 0 line-starts [0]]
      (aset-int char->byte offset byte-offset)
      (if (= offset length)
        {:content content
         :bytes (.getBytes ^String content StandardCharsets/UTF_8)
         :char->byte char->byte
         :line-starts line-starts}
        (let [code-point (.codePointAt ^String content offset)
              width (Character/charCount code-point)
              next-offset (+ offset width)
              next-byte (+ byte-offset (utf8-width code-point))
              line-starts (if (= code-point 10)
                            (conj line-starts next-offset)
                            line-starts)]
          ;; A source coordinate should never split a surrogate pair. Mapping
          ;; the intermediate UTF-16 unit to the code point start keeps invalid
          ;; coordinates bounded and deterministic.
          (when (= width 2)
            (aset-int char->byte (inc offset) byte-offset))
          (recur next-offset next-byte line-starts))))))

(defn character-offset
  "Translate a one-based line and UTF-16 column into a String offset."
  [{:keys [^String content line-starts]} row column]
  (when-let [start (and (pos-int? row) (pos-int? column)
                        (nth line-starts (dec row) nil))]
    (let [next-start (nth line-starts row nil)
          line-end (if next-start (dec next-start) (count content))
          offset (+ start (dec column))]
      (when (<= start offset line-end)
        offset))))

(defn byte-offset
  "Translate a one-based line and UTF-16 column into a UTF-8 byte offset."
  [{:keys [^ints char->byte] :as source-index} row column]
  (when-let [offset (character-offset source-index row column)]
    (aget char->byte offset)))

(defn line-text
  "Return one source line without its newline terminator."
  [{:keys [^String content line-starts]} row]
  (when-let [start (and (pos-int? row) (nth line-starts (dec row) nil))]
    (let [next-start (nth line-starts row nil)
          raw-end (if next-start (dec next-start) (count content))
          end (if (and (> raw-end start)
                       (= \return (.charAt content (dec raw-end))))
                (dec raw-end)
                raw-end)]
      (subs content start end))))

(defn slice-bytes
  "Decode one validated global UTF-8 byte range from the indexed source."
  [{:keys [^bytes bytes]} start end]
  (when-not (and (nat-int? start) (nat-int? end)
                 (<= start end (alength bytes)))
    (throw (ex-info "Source byte range is outside the indexed content"
                    {:start-byte start :end-byte end
                     :file-bytes (alength bytes)})))
  (String. bytes start (- end start) StandardCharsets/UTF_8))
