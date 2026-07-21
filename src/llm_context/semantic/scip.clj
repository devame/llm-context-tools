(ns llm-context.semantic.scip
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path]
           [java.util.concurrent TimeUnit]))

(defn- unsigned-byte [bytes index]
  (bit-and 0xff (aget ^bytes bytes index)))

(defn- read-varint [bytes start]
  (loop [index start shift 0 value 0]
    (let [byte (unsigned-byte bytes index)
          value (bit-or value (bit-shift-left (bit-and byte 0x7f) shift))]
      (if (zero? (bit-and byte 0x80))
        [value (inc index)]
        (recur (inc index) (+ shift 7) value)))))

(defn- byte-slice [bytes start end]
  (java.util.Arrays/copyOfRange ^bytes bytes start end))

(defn decode-message
  "Decode protobuf wire fields without generated classes. Values are retained
  as varints or byte arrays and interpreted only by the small SCIP projection
  below. Unknown fields and future schema additions remain safely skippable."
  [bytes]
  (loop [index 0 fields {}]
    (if (>= index (alength ^bytes bytes))
      fields
      (let [[tag after-tag] (read-varint bytes index)
            field (unsigned-bit-shift-right tag 3)
            wire (bit-and tag 7)]
        (case wire
          0 (let [[value next-index] (read-varint bytes after-tag)]
              (recur next-index (update fields field (fnil conj []) value)))
          1 (recur (+ after-tag 8)
                   (update fields field (fnil conj [])
                           (byte-slice bytes after-tag (+ after-tag 8))))
          2 (let [[length content-start] (read-varint bytes after-tag)
                  content-end (+ content-start length)]
              (recur content-end
                     (update fields field (fnil conj [])
                             (byte-slice bytes content-start content-end))))
          5 (recur (+ after-tag 4)
                   (update fields field (fnil conj [])
                           (byte-slice bytes after-tag (+ after-tag 4))))
          (throw (ex-info (str "Unsupported protobuf wire type " wire)
                          {:wire wire :field field :offset index})))))))

(defn- values [message field]
  (get message field []))

(defn- first-value [message field]
  (first (values message field)))

(defn- utf8 [bytes]
  (when bytes (String. ^bytes bytes StandardCharsets/UTF_8)))

(defn- nested [message field]
  (mapv decode-message (values message field)))

(defn- packed-varints [bytes]
  (loop [index 0 result []]
    (if (>= index (alength ^bytes bytes))
      result
      (let [[value next-index] (read-varint bytes index)]
        (recur next-index (conj result value))))))

(defn- decode-range [occurrence field]
  (some-> (first-value occurrence field) packed-varints vec))

(defn- decode-relationship [message]
  {:symbol (utf8 (first-value message 1))
   :reference? (pos? (or (first-value message 2) 0))
   :implementation? (pos? (or (first-value message 3) 0))
   :type-definition? (pos? (or (first-value message 4) 0))
   :definition? (pos? (or (first-value message 5) 0))})

(defn- decode-symbol [message]
  {:symbol (utf8 (first-value message 1))
   :documentation (mapv utf8 (values message 3))
   :relationships (mapv decode-relationship (nested message 4))
   :kind (or (first-value message 5) 0)
   :display-name (utf8 (first-value message 6))
   :signature (some-> (first-value message 7) decode-message
                      (first-value 5) utf8)
   :enclosing-symbol (utf8 (first-value message 8))})

(defn- decode-occurrence [message]
  {:range (decode-range message 1)
   :symbol (utf8 (first-value message 2))
   :roles (or (first-value message 3) 0)
   :enclosing-range (decode-range message 7)})

(defn- decode-document [message]
  {:relative-path (utf8 (first-value message 1))
   :occurrences (mapv decode-occurrence (nested message 2))
   :symbols (mapv decode-symbol (nested message 3))
   :language (utf8 (first-value message 4))
   :text (utf8 (first-value message 5))})

(defn parse-index [bytes]
  (let [message (decode-message bytes)]
    {:documents (mapv decode-document (nested message 2))
     :external-symbols (mapv decode-symbol (nested message 3))}))

(defn read-index [path]
  (parse-index (Files/readAllBytes ^Path path)))

(defn symbol-display-name
  "Prefer SCIP's display name and fall back to the final descriptor for
  indexers that only populate the canonical symbol string."
  [{:keys [display-name symbol]}]
  (or (not-empty display-name)
      (some-> symbol
              (str/split #"/")
              last
              (str/replace #"[`().#]" "")
              not-empty)))

(defn- stream-text [^InputStream stream]
  (with-open [input stream
              output (ByteArrayOutputStream.)]
    (.transferTo input output)
    (.toString output StandardCharsets/UTF_8)))

(defn run!
  "Run scip-typescript as an argv vector and decode its protobuf output. The
  external provider is isolated behind this function; it never owns graph
  persistence or project path resolution."
  [project config]
  (let [^Path state-dir (:state-dir project)
        output (.resolve state-dir "index.scip")
        configured (get-in config [:semantic :scip-typescript-command])
        command (vec (concat configured
                             ["--cwd" (:root-str project)
                              "--output" (str output)
                              "--no-progress-bar"]))]
    (when-not (seq configured)
      (throw (ex-info "SCIP TypeScript provider is disabled"
                      {:provider :scip-typescript})))
    (Files/createDirectories state-dir
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (let [builder (doto (ProcessBuilder. ^java.util.List command)
                    (.directory (.toFile ^Path (:root project)))
                    (.redirectErrorStream true))
          process (.start builder)
          output-text (future (stream-text (.getInputStream process)))
          finished? (.waitFor process 5 TimeUnit/MINUTES)]
      (when-not finished?
        (.destroyForcibly process)
        (throw (ex-info "SCIP TypeScript timed out after five minutes"
                        {:provider :scip-typescript :command command})))
      (let [exit (.exitValue process)
            log @output-text]
        (when-not (zero? exit)
          (throw (ex-info (str "SCIP TypeScript failed with exit code " exit
                               (when-not (str/blank? log) (str ": " (str/trim log))))
                          {:provider :scip-typescript :exit-code exit
                           :command command :output log})))
        (when-not (Files/exists output (make-array LinkOption 0))
          (throw (ex-info "SCIP TypeScript did not produce an index"
                          {:provider :scip-typescript :output (str output)})))
        (assoc (read-index output) :log log :path output)))))
