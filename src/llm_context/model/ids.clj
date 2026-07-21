(ns llm-context.model.ids
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn content-hash [content]
  (str "sha256:" (sha256 content)))

(defn file-id [relative-path]
  (str "file:" relative-path))

(defn symbol-id
  [{:keys [file-id kind qualified-name signature start-line start-column]}]
  (str "symbol:" (subs (sha256 (str/join "\u001f"
                                         [file-id (name kind) qualified-name
                                          (or signature "") start-line start-column]))
                       0 32)))

(defn edge-id
  [{:keys [kind from-id target-text start-line start-column]}]
  (str "edge:" (subs (sha256 (str/join "\u001f"
                                       [(name kind) from-id target-text
                                        start-line start-column]))
                     0 32)))

(defn effect-id
  [{:keys [kind symbol-id detail start-line start-column]}]
  (str "effect:" (subs (sha256 (str/join "\u001f"
                                         [(name kind) symbol-id detail
                                          start-line start-column]))
                       0 32)))
