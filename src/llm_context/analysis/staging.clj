(ns llm-context.analysis.staging
  "Immutable, content-addressed preparation snapshots. A generation becomes
  reusable only when its index is atomically published after every shard."
  (:require [clojure.edn :as edn]
            [llm-context.model.ids :as ids])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException Files LinkOption
            OpenOption Path Paths StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.zip GZIPInputStream GZIPOutputStream]))

(def format-version 1)
(def ^:private index-name "index.edn")

(defn root-path ^Path [project config]
  (let [value (get-in config [:analysis :staging-directory])
        candidate (Paths/get value (make-array String 0))
        path (.normalize
              (if (.isAbsolute candidate)
                candidate
                (.resolve ^Path (:root project) candidate)))
        state-dir (.normalize (.toAbsolutePath ^Path (:state-dir project)))]
    (when-not (and (.startsWith path state-dir) (not= path state-dir))
      (throw
       (ex-info "Analyzer staging directory must be inside the project state directory"
                {:exit-code 2 :type :analysis/unsafe-staging-directory
                 :path (str path) :state-directory (str state-dir)})))
    path))

(defn generation-id [contract inventory]
  (subs (ids/sha256 (pr-str [format-version contract inventory])) 7 39))

(defn generation-path ^Path [project config contract inventory]
  (.resolve (root-path project config)
            (generation-id contract inventory)))

(defn- compressed-bytes [value]
  (let [buffer (ByteArrayOutputStream.)]
    (with-open [stream (GZIPOutputStream. buffer)]
      (.write stream (.getBytes (str (pr-str value) "\n")
                                StandardCharsets/UTF_8)))
    (.toByteArray buffer)))

(defn- read-compressed [^Path path]
  (with-open [stream (GZIPInputStream.
                      (ByteArrayInputStream. (Files/readAllBytes path)))]
    (edn/read-string (String. (.readAllBytes stream)
                              StandardCharsets/UTF_8))))

(defn- atomic-write-bytes! [^Path target bytes]
  (let [directory (.getParent target)
        _ (Files/createDirectories directory (make-array FileAttribute 0))
        temporary (Files/createTempFile directory ".staging-" ".tmp"
                                        (make-array FileAttribute 0))]
    (try
      (Files/write temporary bytes
                   (into-array OpenOption
                               [StandardOpenOption/TRUNCATE_EXISTING
                                StandardOpenOption/WRITE]))
      (try
        (Files/move temporary target
                    (into-array StandardCopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move temporary target
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (Files/deleteIfExists temporary)))))

(defn- atomic-write-edn! [^Path target value]
  (atomic-write-bytes! target (.getBytes (str (pr-str value) "\n")
                                         StandardCharsets/UTF_8)))

(defn- shard-name [{:keys [file]}]
  (str (subs (ids/sha256
              (str (:file/path file) "\u001f" (:file/content-hash file)))
             7 39)
       ".edn.gz"))

(defn write-generation!
  "Write or resume one exact generation and publish its index last. Existing
  shards are reused only when their decoded value equals the proposed output."
  [project config contract inventory snapshot before-write]
  (when (get-in config [:analysis :resumable-staging])
    (let [directory (generation-path project config contract inventory)
          maximum (get-in config [:analysis :maximum-staging-generation-bytes])
          _ (Files/createDirectories directory (make-array FileAttribute 0))
          entries
          (loop [outputs (seq (:outputs snapshot))
                 entries (sorted-map)
                 generation-bytes 0]
            (if-let [output (first outputs)]
              (let [path (get-in output [:file :file/path])
                    shard (shard-name output)
                    target (.resolve directory shard)
                    existing? (and (Files/isRegularFile
                                    target (make-array LinkOption 0))
                                   (try (= output (read-compressed target))
                                        (catch Throwable _ false)))
                    bytes (when-not existing? (compressed-bytes output))
                    shard-bytes (if bytes (alength bytes) (Files/size target))
                    next-bytes (+ generation-bytes shard-bytes)]
                (when (> next-bytes maximum)
                  (throw
                   (ex-info "Analyzer staging generation exceeded its configured size limit"
                            {:exit-code 1
                             :type :analysis/staging-size-limit
                             :generation (str directory)
                             :maximum-bytes maximum
                             :attempted-bytes next-bytes})))
                (when bytes
                  (when before-write (before-write {:path path :bytes (alength bytes)}))
                  (atomic-write-bytes! target bytes))
                (recur (next outputs)
                       (assoc entries path
                              {:shard shard
                               :content-hash (get-in output
                                                     [:file :file/content-hash])})
                       next-bytes))
              entries))
          index {:format format-version
                 :generation (generation-id contract inventory)
                 :contract contract
                 :source-inventory inventory
                 :files entries
                 :analyzers (:analyzers snapshot)
                 :analysis-metrics (:analysis-metrics snapshot)
                 :diagnostics (:diagnostics snapshot)
                 :completed-at (System/currentTimeMillis)}]
      (atomic-write-edn! (.resolve directory index-name) index)
      {:generation (:generation index)
       :path (str directory)
       :files (count entries)})))

(defn load-generation
  "Load a complete exact generation, or nil for missing, partial, corrupt, or
  incompatible state. No subset is exposed to graph persistence."
  [project config contract inventory]
  (when (get-in config [:analysis :resumable-staging])
    (try
      (let [directory (generation-path project config contract inventory)
            index (edn/read-string (Files/readString (.resolve directory index-name)))
            inventory-by-path (into {} inventory)]
        (when (and (= format-version (:format index))
                   (= (generation-id contract inventory) (:generation index))
                   (= contract (:contract index))
                   (= inventory (:source-inventory index))
                   (= (set (map first inventory)) (set (keys (:files index))))
                   (every? (fn [[path {:keys [shard content-hash]}]]
                             (and (boolean
                                   (re-matches #"[0-9a-f]{32}\.edn\.gz" shard))
                                  (= content-hash (get inventory-by-path path))))
                           (:files index)))
          (let [outputs
                (mapv (fn [[path {:keys [shard content-hash]}]]
                        (let [output (read-compressed (.resolve directory shard))]
                          (when-not (and (= path (get-in output [:file :file/path]))
                                         (= content-hash
                                            (get-in output
                                                    [:file :file/content-hash])))
                            (throw (ex-info "Invalid analyzer staging shard"
                                            {:path path :shard shard})))
                          output))
                      (:files index))]
            {:outputs outputs
             :analyzers (:analyzers index)
             :analysis-metrics (:analysis-metrics index)
             :diagnostics (:diagnostics index)
             :staging {:generation (:generation index)
                       :path (str directory)
                       :resumed? true}})))
      (catch Throwable _ nil))))
