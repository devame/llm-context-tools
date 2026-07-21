(ns llm-context.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader]
           [java.nio.file FileAlreadyExistsException Files OpenOption Path StandardOpenOption]))

(def default-resource "llm_context/default-config.edn")

(defn deep-merge
  "Recursively merge configuration maps; user scalars and collections replace
  defaults rather than being concatenated implicitly."
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left) (map? right))
             (deep-merge left right)
             right))
         maps))

(defn read-edn [source]
  (with-open [reader (PushbackReader. (io/reader source))]
    (edn/read {:eof nil} reader)))

(defn defaults []
  (read-edn (io/resource default-resource)))

(defn- validation-errors [config]
  (cond-> []
    (not (map? config))
    (conj "configuration must be an EDN map")

    (not (and (vector? (get-in config [:analysis :include]))
              (every? string? (get-in config [:analysis :include]))))
    (conj ":analysis/:include must be a vector of paths")

    (not (and (vector? (get-in config [:analysis :exclude]))
              (every? string? (get-in config [:analysis :exclude]))))
    (conj ":analysis/:exclude must be a vector of paths")

    (not (pos-int? (get-in config [:analysis :max-file-bytes])))
    (conj ":analysis/:max-file-bytes must be a positive integer")

    (not (string? (get-in config [:store :path])))
    (conj ":store/:path must be a path string")

    (not (pos-int? (get-in config [:context :default-max-tokens])))
    (conj ":context/:default-max-tokens must be a positive integer")))

(defn validate! [config]
  (when-let [errors (seq (validation-errors config))]
    (throw (ex-info (str "Invalid llm-context.edn: " (str/join "; " errors))
                    {:exit-code 2 :errors errors})))
  config)

(defn load-config
  "Load defaults plus the optional project-local llm-context.edn file."
  [{:keys [^Path config-file]}]
  (let [user-config (when (Files/exists config-file (make-array java.nio.file.LinkOption 0))
                      (read-edn (.toFile config-file)))]
    (validate! (deep-merge (defaults) (or user-config {})))))

(defn init!
  "Create the canonical project configuration without overwriting user data."
  [{:keys [^Path config-file]}]
  (try
    (Files/writeString config-file
                       (str (slurp (io/resource default-resource)) "\n")
                       (into-array OpenOption [StandardOpenOption/CREATE_NEW
                                               StandardOpenOption/WRITE]))
    config-file
    (catch FileAlreadyExistsException _
      (throw (ex-info (str "Configuration already exists: " config-file)
                      {:exit-code 2 :path (str config-file)})))))
