(ns llm-context.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-context.accelerator :as accelerator]
            [llm-context.intent :as intent]
            [llm-context.model-packages :as model-packages]
            [llm-context.source-role :as source-role])
  (:import [java.io PushbackReader]
           [java.nio.file FileAlreadyExistsException Files OpenOption Path StandardOpenOption]))

(def default-resource "llm_context/default-config.edn")

(def ^:private semantic-modes #{:background :disabled})

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- positive-number? [value]
  (and (number? value) (pos? value)))

(defn- valid-source-role-override? [value]
  (and (map? value)
       (= #{:role :pattern} (set (keys value)))
       (contains? source-role/roles (:role value))
       (non-blank-string? (:pattern value))))

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
  (let [lateon (get-in config [:semantic :lateon-code])
        router (get-in config [:context :query-router])
        reranker (get-in config [:context :candidate-reranker])]
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

    (not (boolean? (get-in config [:analysis :resumable-staging])))
    (conj ":analysis/:resumable-staging must be true or false")

    (not (non-blank-string? (get-in config [:analysis :staging-directory])))
    (conj ":analysis/:staging-directory must be a non-blank path")

    (not (pos-int? (get-in config
                           [:analysis :maximum-staging-generation-bytes])))
    (conj ":analysis/:maximum-staging-generation-bytes must be a positive integer")

    (not (string? (get-in config [:store :path])))
    (conj ":store/:path must be a path string")

    (not (pos-int? (get-in config [:store :max-transaction-weight])))
    (conj ":store/:max-transaction-weight must be a positive integer")

    (not (and (integer? (get-in config [:store :minimum-free-space-bytes]))
              (not (neg? (get-in config
                                 [:store :minimum-free-space-bytes])))))
    (conj ":store/:minimum-free-space-bytes must be a non-negative integer")

    (not (pos-int? (get-in config [:store :maximum-operation-growth-bytes])))
    (conj ":store/:maximum-operation-growth-bytes must be a positive integer")

    (not (pos-int? (get-in config [:store :storage-sample-interval-ms])))
    (conj ":store/:storage-sample-interval-ms must be a positive integer")

    (not (or (nil? (get-in config [:store :free-space-probe-path]))
             (non-blank-string?
              (get-in config [:store :free-space-probe-path]))))
    (conj ":store/:free-space-probe-path must be nil or a non-blank path")

    (not (boolean? (get-in config [:service :watch])))
    (conj ":service/:watch must be true or false")

    (not (boolean? (get-in config [:service :watch-initial])))
    (conj ":service/:watch-initial must be true or false")

    (not (pos-int? (get-in config [:service :watch-debounce-ms])))
    (conj ":service/:watch-debounce-ms must be a positive integer")

    (not (pos-int? (get-in config [:service :request-threads])))
    (conj ":service/:request-threads must be a positive integer")

    (not (pos-int? (get-in config [:service :request-queue-capacity])))
    (conj ":service/:request-queue-capacity must be a positive integer")

    (not (pos-int? (get-in config [:context :default-max-tokens])))
    (conj ":context/:default-max-tokens must be a positive integer")

    (not (pos-int? (get-in config [:context :trace-depth])))
    (conj ":context/:trace-depth must be a positive integer")

    (not (pos-int? (get-in config [:context :trace-limit])))
    (conj ":context/:trace-limit must be a positive integer")

    (not (contains? source-role/preferences
                    (get-in config [:context :intent-source-preference])))
    (conj ":context/:intent-source-preference must be :auto, :production, :test, or :none")

    (not (contains? intent/seed-modes
                    (get-in config [:context :intent-seed-mode])))
    (conj ":context/:intent-seed-mode must be :auto, :single, or :multi")

    (not (pos-int? (get-in config [:context :intent-max-seeds])))
    (conj ":context/:intent-max-seeds must be a positive integer")

    (not (boolean? (get-in config [:context :intent-rerank])))
    (conj ":context/:intent-rerank must be true or false")

    (not (pos-int? (get-in config [:context :intent-candidate-count])))
    (conj ":context/:intent-candidate-count must be a positive integer")

    (not (map? reranker))
    (conj ":context/:candidate-reranker must be a map")

    (not (boolean? (:enabled reranker)))
    (conj ":context/:candidate-reranker/:enabled must be true or false")

    (not (contains? #{:shadow :enforce} (:mode reranker)))
    (conj ":context/:candidate-reranker/:mode must be :shadow or :enforce")

    (not (pos-int? (:candidate-count reranker)))
    (conj ":context/:candidate-reranker/:candidate-count must be a positive integer")

    (not (pos-int? (:query-timeout-ms reranker)))
    (conj ":context/:candidate-reranker/:query-timeout-ms must be a positive integer")

    (not (pos-int? (:document-cache-size reranker)))
    (conj ":context/:candidate-reranker/:document-cache-size must be a positive integer")

    (not (map? router))
    (conj ":context/:query-router must be a map")

    (not (boolean? (:enabled router)))
    (conj ":context/:query-router/:enabled must be true or false")

    (not (and (vector? (:next-plaid-command router))
              (seq (:next-plaid-command router))
              (every? non-blank-string? (:next-plaid-command router))))
    (conj ":context/:query-router/:next-plaid-command must be a non-empty command vector")

    (not (non-blank-string? (:model router)))
    (conj ":context/:query-router/:model must be a non-blank string")

    (not (and (non-blank-string? (:model-revision router))
              (re-matches #"[0-9a-f]{40}" (:model-revision router))))
    (conj ":context/:query-router/:model-revision must be a 40-character commit hash")

    (not (or (nil? (:model-path router))
             (non-blank-string? (:model-path router))))
    (conj ":context/:query-router/:model-path must be nil or a non-blank path")

    (not (contains? accelerator/accelerators (:accelerator router)))
    (conj ":context/:query-router/:accelerator must be :auto, :cpu, or :cuda")

    (not (contains? accelerator/quantizations (:quantization router)))
    (conj ":context/:query-router/:quantization must be :auto, :int8, or :fp32")

    (not (and (vector? (:cuda-library-paths router))
              (every? non-blank-string? (:cuda-library-paths router))))
    (conj ":context/:query-router/:cuda-library-paths must be a vector of paths")

    (and (= :cuda (:accelerator router))
         (= :int8 (:quantization router)))
    (conj ":context/:query-router CUDA cannot use :int8 quantization")

    (not (and (non-blank-string? (:next-plaid-version router))
              (re-matches #"\d+\.\d+\.\d+" (:next-plaid-version router))))
    (conj ":context/:query-router/:next-plaid-version must be a semantic version")

    (not (non-blank-string? (:index-path router)))
    (conj ":context/:query-router/:index-path must be a non-blank path")

    (not (and (non-blank-string? (:index-name router))
              (re-matches #"[A-Za-z0-9_-]+" (:index-name router))))
    (conj ":context/:query-router/:index-name must contain only letters, digits, underscore, or hyphen")

    (not (pos-int? (:startup-timeout-ms router)))
    (conj ":context/:query-router/:startup-timeout-ms must be a positive integer")

    (not (pos-int? (:query-timeout-ms router)))
    (conj ":context/:query-router/:query-timeout-ms must be a positive integer")

    (not (pos-int? (:health-timeout-ms router)))
    (conj ":context/:query-router/:health-timeout-ms must be a positive integer")

    (not (pos-int? (:update-timeout-ms router)))
    (conj ":context/:query-router/:update-timeout-ms must be a positive integer")

    (not (pos-int? (:encoding-sessions router)))
    (conj ":context/:query-router/:encoding-sessions must be a positive integer")

    (not (pos-int? (:encoding-batch-size router)))
    (conj ":context/:query-router/:encoding-batch-size must be a positive integer")

    (not (pos-int? (:cuda-encoding-sessions router)))
    (conj ":context/:query-router/:cuda-encoding-sessions must be a positive integer")

    (not (pos-int? (:cuda-encoding-batch-size router)))
    (conj ":context/:query-router/:cuda-encoding-batch-size must be a positive integer")

    (not (and (number? (:minimum-margin router))
              (not (neg? (:minimum-margin router)))))
    (conj ":context/:query-router/:minimum-margin must be a non-negative number")

    (not (and (vector? (get-in config [:context :source-role-overrides]))
              (every? valid-source-role-override?
                      (get-in config [:context :source-role-overrides]))))
    (conj ":context/:source-role-overrides must contain ordered {:role keyword :pattern glob} maps")

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

    (not (contains? accelerator/accelerators (:accelerator lateon)))
    (conj ":semantic/:lateon-code/:accelerator must be :auto, :cpu, or :cuda")

    (not (contains? accelerator/quantizations (:quantization lateon)))
    (conj ":semantic/:lateon-code/:quantization must be :auto, :int8, or :fp32")

    (not (and (vector? (:cuda-library-paths lateon))
              (every? non-blank-string? (:cuda-library-paths lateon))))
    (conj ":semantic/:lateon-code/:cuda-library-paths must be a vector of paths")

    (and (= :cuda (:accelerator lateon))
         (= :int8 (:quantization lateon)))
    (conj ":semantic/:lateon-code CUDA cannot use :int8 quantization")

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

    (not (pos-int? (:cuda-encoding-sessions lateon)))
    (conj ":semantic/:lateon-code/:cuda-encoding-sessions must be a positive integer")

    (not (pos-int? (:cuda-encoding-batch-size lateon)))
    (conj ":semantic/:lateon-code/:cuda-encoding-batch-size must be a positive integer")

    (not (pos-int? (:model-document-length lateon)))
    (conj ":semantic/:lateon-code/:model-document-length must be a positive integer")

    (not (pos-int? (:update-batch-size lateon)))
    (conj ":semantic/:lateon-code/:update-batch-size must be a positive integer")

    (not (pos-int? (:update-concurrency lateon)))
    (conj ":semantic/:lateon-code/:update-concurrency must be a positive integer")

    (not (pos-int? (:cuda-update-concurrency lateon)))
    (conj ":semantic/:lateon-code/:cuda-update-concurrency must be a positive integer")

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
  "Load defaults, an optional verified installation model registry, and the
  optional project-local llm-context.edn file. Project configuration remains
  authoritative so a repository can deliberately select another verified
  model installation."
  [{:keys [^Path config-file]}]
  (let [user-config (when (Files/exists config-file (make-array java.nio.file.LinkOption 0))
                      (read-edn (.toFile config-file)))
        registry (model-packages/read-registry
                  (System/getenv "LLM_CONTEXT_MODEL_REGISTRY"))]
    (validate! (deep-merge (defaults)
                           (model-packages/config-overlay registry)
                           (or user-config {})))))

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
