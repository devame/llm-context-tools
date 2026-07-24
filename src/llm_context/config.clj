(ns llm-context.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackReader]
           [java.nio.file FileAlreadyExistsException Files OpenOption Path StandardOpenOption]))

(def default-resource "llm_context/default-config.edn")

(def ^:private semantic-modes #{:background :disabled})
(def ^:private semantic-quantizations #{:int8})

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- positive-number? [value]
  (and (number? value) (pos? value)))

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
  (let [lateon (get-in config [:semantic :lateon-code])]
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
    (conj ":context/:default-max-tokens must be a positive integer")

    (not (and (vector? (get-in config [:semantic :providers]))
              (every? keyword? (get-in config [:semantic :providers]))))
    (conj ":semantic/:providers must be a vector of keywords")

    (not (map? lateon))
    (conj ":semantic/:lateon-code must be a map")

    (not (boolean? (:enabled lateon)))
    (conj ":semantic/:lateon-code/:enabled must be true or false")

    (not (contains? semantic-modes (:mode lateon)))
    (conj ":semantic/:lateon-code/:mode must be :background or :disabled")

    (not (and (non-blank-string? (:next-plaid-version lateon))
              (re-matches #"\d+\.\d+\.\d+" (:next-plaid-version lateon))))
    (conj ":semantic/:lateon-code/:next-plaid-version must be a semantic version")

    (not (and (vector? (:next-plaid-command lateon))
              (seq (:next-plaid-command lateon))
              (every? non-blank-string? (:next-plaid-command lateon))))
    (conj ":semantic/:lateon-code/:next-plaid-command must be a non-empty command vector")

    (not (non-blank-string? (:model lateon)))
    (conj ":semantic/:lateon-code/:model must be a non-blank string")

    (not (and (non-blank-string? (:model-revision lateon))
              (re-matches #"[0-9a-f]{40}" (:model-revision lateon))))
    (conj ":semantic/:lateon-code/:model-revision must be a 40-character commit hash")

    (not (contains? semantic-quantizations (:quantization lateon)))
    (conj ":semantic/:lateon-code/:quantization must be :int8")

    (not (or (nil? (:model-path lateon))
             (non-blank-string? (:model-path lateon))))
    (conj ":semantic/:lateon-code/:model-path must be nil or a non-blank path")

    (not (non-blank-string? (:index-path lateon)))
    (conj ":semantic/:lateon-code/:index-path must be a non-blank path")

    (not (and (non-blank-string? (:index-name lateon))
              (re-matches #"[A-Za-z0-9_-]+" (:index-name lateon))))
    (conj ":semantic/:lateon-code/:index-name must contain letters, digits, _ or -")

    (not (contains? #{2 4} (:nbits lateon)))
    (conj ":semantic/:lateon-code/:nbits must be 2 or 4")

    (not (nat-int? (:start-from-scratch lateon)))
    (conj ":semantic/:lateon-code/:start-from-scratch must be non-negative")

    (not (pos-int? (:document-version lateon)))
    (conj ":semantic/:lateon-code/:document-version must be a positive integer")

    (not (pos-int? (:max-document-bytes lateon)))
    (conj ":semantic/:lateon-code/:max-document-bytes must be a positive integer")

    (not (nat-int? (:chunk-overlap-lines lateon)))
    (conj ":semantic/:lateon-code/:chunk-overlap-lines must be a non-negative integer")

    (not (pos-int? (:pool-factor lateon)))
    (conj ":semantic/:lateon-code/:pool-factor must be a positive integer")

    (not (pos-int? (:encoding-sessions lateon)))
    (conj ":semantic/:lateon-code/:encoding-sessions must be a positive integer")

    (not (pos-int? (:encoding-batch-size lateon)))
    (conj ":semantic/:lateon-code/:encoding-batch-size must be a positive integer")

    (not (pos-int? (:model-document-length lateon)))
    (conj ":semantic/:lateon-code/:model-document-length must be a positive integer")

    (not (pos-int? (:update-batch-size lateon)))
    (conj ":semantic/:lateon-code/:update-batch-size must be a positive integer")

    (not (pos-int? (:health-timeout-ms lateon)))
    (conj ":semantic/:lateon-code/:health-timeout-ms must be a positive integer")

    (not (pos-int? (:startup-timeout-ms lateon)))
    (conj ":semantic/:lateon-code/:startup-timeout-ms must be a positive integer")

    (not (pos-int? (:update-timeout-ms lateon)))
    (conj ":semantic/:lateon-code/:update-timeout-ms must be a positive integer")

    (not (pos-int? (:query-timeout-ms lateon)))
    (conj ":semantic/:lateon-code/:query-timeout-ms must be a positive integer")

    (not (pos-int? (:candidate-count lateon)))
    (conj ":semantic/:lateon-code/:candidate-count must be a positive integer")

    (not (pos-int? (:n-ivf-probe lateon)))
    (conj ":semantic/:lateon-code/:n-ivf-probe must be a positive integer")

    (not (or (nil? (:centroid-score-threshold lateon))
             (positive-number? (:centroid-score-threshold lateon))))
    (conj ":semantic/:lateon-code/:centroid-score-threshold must be nil or positive")

    (not (pos-int? (:n-full-scores lateon)))
    (conj ":semantic/:lateon-code/:n-full-scores must be a positive integer")

    (not (pos-int? (:lease-ms lateon)))
    (conj ":semantic/:lateon-code/:lease-ms must be a positive integer")

    (not (pos-int? (:visibility-timeout-ms lateon)))
    (conj ":semantic/:lateon-code/:visibility-timeout-ms must be a positive integer")

    (not (pos-int? (:visibility-poll-ms lateon)))
    (conj ":semantic/:lateon-code/:visibility-poll-ms must be a positive integer")

    (not (pos-int? (:idle-poll-ms lateon)))
    (conj ":semantic/:lateon-code/:idle-poll-ms must be a positive integer")

    (not (pos-int? (:retry-base-ms lateon)))
    (conj ":semantic/:lateon-code/:retry-base-ms must be a positive integer")

    (not (pos-int? (:retry-max-ms lateon)))
    (conj ":semantic/:lateon-code/:retry-max-ms must be a positive integer")

    (and (pos-int? (:retry-base-ms lateon))
         (pos-int? (:retry-max-ms lateon))
         (> (:retry-base-ms lateon) (:retry-max-ms lateon)))
    (conj ":semantic/:lateon-code/:retry-base-ms must not exceed :retry-max-ms")

    (not (pos-int? (:max-attempts lateon)))
    (conj ":semantic/:lateon-code/:max-attempts must be a positive integer"))))

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
