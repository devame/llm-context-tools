(ns llm-context.model.canonical-hash
  "Streaming, deterministic hashes for semantic graph values."
  (:import [java.io ByteArrayOutputStream DataOutputStream]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def contract-version 1)

(defn- write-bytes! [^DataOutputStream output ^bytes bytes]
  (.writeInt output (alength bytes))
  (.write output bytes))

(defn- write-text! [output value]
  (write-bytes! output (.getBytes (str value) StandardCharsets/UTF_8)))

(defn- compare-bytes [^bytes left ^bytes right]
  (let [limit (min (alength left) (alength right))]
    (loop [index 0]
      (if (= index limit)
        (compare (alength left) (alength right))
        (let [comparison
              (compare (bit-and 0xff (aget left index))
                       (bit-and 0xff (aget right index)))]
          (if (zero? comparison)
            (recur (inc index))
            comparison))))))

(declare write-value!)

(defn- encoded-value [value]
  (let [bytes (ByteArrayOutputStream.)
        output (DataOutputStream. bytes)]
    (write-value! output value)
    (.flush output)
    (.toByteArray bytes)))

(defn- ordered-encodings [values]
  (sort compare-bytes (mapv encoded-value values)))

(defn- write-map! [output value]
  (.writeInt output (count value))
  (doseq [[key-bytes key]
          (sort #(compare-bytes (first %1) (first %2))
                (mapv (fn [key] [(encoded-value key) key]) (keys value)))]
    (write-bytes! output key-bytes)
    (write-value! output (get value key))))

(defn- write-value! [^DataOutputStream output value]
  (cond
    (nil? value) (.writeByte output 0)
    (false? value) (.writeByte output 1)
    (true? value) (.writeByte output 2)
    (string? value) (do (.writeByte output 3) (write-text! output value))
    (keyword? value) (do (.writeByte output 4) (write-text! output value))
    (symbol? value) (do (.writeByte output 5) (write-text! output value))
    (integer? value) (do (.writeByte output 6) (write-text! output value))
    (ratio? value) (do (.writeByte output 7) (write-text! output value))
    (float? value) (do (.writeByte output 8)
                       (.writeLong output
                                   (Double/doubleToLongBits (double value))))
    (decimal? value) (do (.writeByte output 9) (write-text! output value))
    (char? value) (do (.writeByte output 10) (.writeInt output (int value)))
    (map? value) (do (.writeByte output 11) (write-map! output value))
    (set? value) (do (.writeByte output 12)
                     (.writeInt output (count value))
                     (doseq [bytes (ordered-encodings value)]
                       (write-bytes! output bytes)))
    (sequential? value) (do (.writeByte output 13)
                            (.writeInt output (count value))
                            (doseq [item value] (write-value! output item)))
    (uuid? value) (do (.writeByte output 14) (write-text! output value))
    (inst? value) (do (.writeByte output 15)
                      (.writeLong output (.getTime ^java.util.Date value)))
    :else
    (throw (ex-info "Unsupported canonical hash value"
                    {:type (some-> value class .getName)}))))

(defn order-by
  "Order values by one canonical key encoding without repeated rendering."
  [key-fn values]
  (mapv second
        (sort #(compare-bytes (first %1) (first %2))
              (mapv (fn [value] [(encoded-value (key-fn value)) value])
                    values))))

(defn hash-values
  "Hash an ordered collection directly into SHA-256 using the versioned
  type-tagged, length-delimited encoding."
  [values]
  (let [digest (MessageDigest/getInstance "SHA-256")
        output (DataOutputStream.
                (proxy [java.io.OutputStream] []
                  (write
                    ([value]
                     (.update digest (unchecked-byte value)))
                    ([bytes offset length]
                     (.update digest ^bytes bytes (int offset) (int length))))))
        values (if (counted? values) values (vec values))]
    (.writeInt output contract-version)
    (.writeInt output (count values))
    (doseq [value values]
      (write-value! output value))
    (.flush output)
    (str "sha256:"
         (apply str (map #(format "%02x" (bit-and % 0xff))
                         (.digest digest))))))
