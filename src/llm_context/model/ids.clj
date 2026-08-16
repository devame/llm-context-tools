(ns llm-context.model.ids
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private ^"[C" hex-digits
  (.toCharArray "0123456789abcdef"))

(defn- hex-string [^bytes bytes]
  (let [result (char-array (* 2 (alength bytes)))]
    (dotimes [index (alength bytes)]
      (let [value (bit-and 0xff (aget bytes index))
            offset (* 2 index)]
        (aset-char result offset (aget hex-digits (unsigned-bit-shift-right value 4)))
        (aset-char result (inc offset) (aget hex-digits (bit-and value 0x0f)))))
    (String. result)))

(defn sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) StandardCharsets/UTF_8))]
    (hex-string digest)))

(defn content-hash [content]
  (str "sha256:" (sha256 content)))

(defn file-id [relative-path]
  (str "file:" relative-path))

(defn symbol-id
  [{:keys [platform file-id kind qualified-name discriminator]}]
  (str "symbol:" (subs (sha256 (str/join "\u001f"
                                         [(name (or platform :unknown))
                                          file-id (name kind) qualified-name
                                          (or discriminator "")]))
                       0 32)))

(defn edge-id
  [{:keys [kind from-id to-id target-text start-line start-column]}]
  (str "edge:" (subs (sha256 (str/join "\u001f"
                                       [(name kind) from-id (or to-id target-text)
                                        start-line start-column]))
                     0 32)))

(defn reference-id
  [{:keys [platform symbol-id kind target-text classification
           start-line start-column]}]
  (str "reference:"
       (subs (sha256 (str/join "\u001f"
                               [(name platform) symbol-id (name kind)
                                target-text (name classification)
                                start-line start-column]))
             0 32)))

(defn topic-id [{:keys [platform kind key]}]
  (str "topic:"
       (subs (sha256 (str/join "\u001f"
                               [(name platform) (name kind) (pr-str key)]))
             0 32)))

(defn effect-id
  [{:keys [kind symbol-id detail start-line start-column]}]
  (str "effect:" (subs (sha256 (str/join "\u001f"
                                         [(name kind) symbol-id detail
                                          start-line start-column]))
                       0 32)))

(defn aggregate-id
  [{:keys [owner-id kind]}]
  (str "aggregate:"
       (subs (sha256 (str/join "\u001f" [owner-id (name kind)])) 0 32)))

(defn membership-id
  [{:keys [aggregate-id ordinal key value]}]
  (str "membership:"
       (subs (sha256 (str/join "\u001f"
                               [aggregate-id ordinal (or key "") value]))
             0 32)))
