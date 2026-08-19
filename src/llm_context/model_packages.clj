(ns llm-context.model-packages
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-context.dependencies :as dependencies])
  (:import [java.io BufferedInputStream File PushbackReader]
           [java.lang ProcessHandle]
           [java.net URI]
           [java.nio.file Files StandardCopyOption]
           [java.security DigestInputStream MessageDigest]))

(def default-resource dependencies/default-resource)
(def roles #{:semantic-retriever :query-router-reranker :answer-reader})
(def formats #{:next-plaid-onnx :gguf})
(def ^:private sha-pattern #"[0-9a-f]{64}")
(def ^:private revision-pattern #"[0-9a-f]{40}")
(def ^:private model-pattern
  #"[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")

(defn- read-edn [source]
  (with-open [reader (PushbackReader. (io/reader source))]
    (edn/read {:eof nil} reader)))

(defn defaults [] (dependencies/all))

(defn sha256 [source]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (DigestInputStream.
                       (BufferedInputStream. (io/input-stream source)) digest)]
      (let [buffer (byte-array 65536)]
        (loop []
          (when-not (= -1 (.read input buffer)) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))

(defn- safe-relative-file? [value]
  (and (string? value)
       (not (str/blank? value))
       (let [path (.normalize (.toPath (File. value)))]
         (and (not (.isAbsolute path))
              (not (str/starts-with? (str path) ".."))))))

(defn validate! [manifest]
  (let [errors
        (cond-> []
          (not= 1 (:contract-version manifest))
          (conj ":contract-version must be 1")
          (not (map? (:roles manifest)))
          (conj ":roles must be a map")
          (not (every? roles (keys (:roles manifest))))
          (conj "manifest contains an unknown model role")
          (empty? (:roles manifest))
          (conj "manifest must define at least one model role"))
        errors
        (reduce-kv
         (fn [result role package]
           (cond-> result
             (not (and (string? (:model package))
                       (re-matches model-pattern (:model package))))
             (conj (str role " :model must be a safe owner/name identifier"))
             (not (and (string? (:revision package))
                       (re-matches revision-pattern (:revision package))))
             (conj (str role " :revision must be a pinned 40-character commit"))
             (not (formats (:format package)))
             (conj (str role " :format must be a supported runtime format"))
             (not (safe-relative-file? (:entrypoint package)))
             (conj (str role " :entrypoint must be a safe relative file"))
             (not (and (string? (:base-url package))
                       (not (str/blank? (:base-url package)))))
             (conj (str role " :base-url must be non-blank"))
             (not (and (map? (:files package)) (seq (:files package))))
             (conj (str role " :files must contain SHA-256 entries"))
             (not (every? (fn [[file hash]]
                            (and (safe-relative-file? file)
                                 (string? hash)
                                 (re-matches sha-pattern hash)))
                          (:files package)))
             (conj (str role " files must use safe paths and lowercase SHA-256 hashes"))
             (not (contains? (:files package) (:entrypoint package)))
             (conj (str role " :entrypoint must be present in :files"))))
         errors (or (:roles manifest) {}))]
    (when (seq errors)
      (throw (ex-info (str "Invalid verified model manifest: " (str/join "; " errors))
                      {:exit-code 2 :errors errors})))
    manifest))

(defn load-manifest
  [{:keys [manifest manifest-sha256]}]
  (if manifest
    (do
      (when-not (and manifest-sha256 (re-matches sha-pattern manifest-sha256))
        (throw (ex-info "A custom model manifest requires --manifest-sha256; unverified manifests are rejected"
                        {:exit-code 2})))
      (when-not (= manifest-sha256 (sha256 manifest))
        (throw (ex-info "Custom model manifest checksum verification failed"
                        {:exit-code 2})))
      (validate! (read-edn manifest)))
    (validate! (defaults))))

(defn model-directory [cache-root {:keys [model revision]}]
  (io/file cache-root (str/replace model "/" "--") revision))

(defn- verified-file? [file expected]
  (and (.isFile ^File file) (= expected (sha256 file))))

(defn verify-package! [directory package]
  (doseq [[relative expected] (:files package)]
    (let [file (io/file directory relative)]
      (when-not (verified-file? file expected)
        (throw (ex-info (str "Model verification failed for " (.getPath file))
                        {:exit-code 2 :file (.getPath file)})))))
  true)

(defn- source-url [base relative]
  (str (str/replace base #"/$" "") "/" relative
       (when (str/starts-with? base "http") "?download=true")))

(defn- copy-source! [source destination]
  (io/make-parents destination)
  (with-open [input (io/input-stream source)]
    (Files/copy input (.toPath ^File destination)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))

(defn install-package!
  [cache-root role package source-root]
  (let [destination (model-directory cache-root package)]
    (try
      (verify-package! destination package)
      (catch Exception _
        (let [staging (io/file (str (.getPath destination) ".new." (.pid (ProcessHandle/current))))]
          (doseq [[relative expected] (:files package)]
            (let [target (io/file staging relative)
                  source (if source-root
                           (io/file source-root relative)
                           (URI/create (source-url (:base-url package) relative)))]
              (copy-source! source target)
              (when-not (verified-file? target expected)
                (throw (ex-info (str "Downloaded model checksum failed for " role "/" relative)
                                {:exit-code 2 :role role :file relative})))))
          (io/make-parents (io/file destination ".keep"))
          (when (.exists destination)
            (doseq [file (reverse (file-seq destination))] (.delete ^File file)))
          (Files/move (.toPath staging) (.toPath destination)
                      (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))))
    (verify-package! destination package)
    {:model (:model package)
     :revision (:revision package)
     :format (:format package)
     :alias (:alias package)
     :entrypoint (:entrypoint package)
     :path (.getCanonicalPath destination)
     :files (:files package)}))

(defn install!
  [{:keys [cache registry selected-roles source-roots] :as options}]
  (let [manifest (load-manifest options)
        selected (or selected-roles [:semantic-retriever :query-router-reranker])
        unknown (remove roles selected)]
    (when (seq unknown)
      (throw (ex-info (str "Unknown model role(s): " (str/join ", " (map name unknown)))
                      {:exit-code 2})))
    (let [missing (remove #(contains? (:roles manifest) %) selected)]
      (when (seq missing)
        (throw (ex-info (str "Verified manifest does not define selected role(s): "
                             (str/join ", " (map name missing)))
                        {:exit-code 2}))))
    (let [installed (into {}
                          (for [role selected]
                            [role (install-package! cache role
                                                    (get-in manifest [:roles role])
                                                    (get source-roots role))]))
          result {:contract-version 1 :roles installed}]
      (when registry
        (io/make-parents registry)
        (spit registry (str (pr-str result) "\n")))
      result)))

(defn read-registry [path]
  (when (and path (.isFile (io/file path)))
    (let [registry (read-edn path)]
      (when-not (= 1 (:contract-version registry))
        (throw (ex-info "Unsupported installed model registry contract"
                        {:exit-code 2})))
      registry)))

(defn config-overlay [registry]
  (let [semantic (get-in registry [:roles :semantic-retriever])
        router (get-in registry [:roles :query-router-reranker])
        answer (get-in registry [:roles :answer-reader])]
    (cond-> {}
      semantic (assoc-in [:semantic :lateon-code]
                         {:model (:model semantic)
                          :model-revision (:revision semantic)
                          :model-path (:path semantic)})
      router (assoc-in [:context :query-router]
                       {:model (:model router)
                        :model-revision (:revision router)
                        :model-path (:path router)})
      answer (assoc :answer-reader
                    {:model (:model answer)
                     :model-revision (:revision answer)
                     :model-path (:path answer)
                     :model-file (:entrypoint answer)
                     :model-alias (:alias answer)
                     :format (:format answer)}))))
