(ns llm-context.analysis.manifest
  "Generated, revision-bound analyzer dependency manifests. Invalid cache
  state is never partially reused."
  (:require [clojure.edn :as edn]
            [llm-context.model.ids :as ids])
  (:import [java.nio.file AtomicMoveNotSupportedException Files LinkOption
            OpenOption Path StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(def version 1)
(def ^:private directory-name "analyzer-manifests")
(def ^:private index-name "index.edn")

(defn- cache-directory ^Path [project]
  (.resolve ^Path (:state-dir project) (str "cache/" directory-name)))

(defn- atomic-write! [^Path target value]
  (let [directory (.getParent target)
        _ (Files/createDirectories directory (make-array FileAttribute 0))
        temporary (Files/createTempFile directory ".manifest-" ".tmp"
                                        (make-array FileAttribute 0))]
    (try
      (Files/writeString temporary (str (pr-str value) "\n")
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

(defn- export-record [entity]
  (when (= :entity.type/symbol (:entity/type entity))
    {:platform (:symbol/platform entity)
     :kind (:symbol/kind entity)
     :qualified-name (:symbol/qualified-name entity)
     :file-id (:symbol/file entity)
     :symbol-id (:symbol/id entity)}))

(defn- dependency-target [entity]
  (when (contains? #{:entity.type/edge :entity.type/reference}
                   (:entity/type entity))
    (or (:edge/target-text entity) (:reference/qualified-target entity)
        (:reference/target-text entity))))

(defn- imported-namespace [entity]
  (when (and (= :entity.type/edge (:entity/type entity))
             (= :edge.kind/imports (:edge/kind entity)))
    (:edge/target-text entity)))

(defn file-manifest [revision output]
  (let [file (:file output)
        entities (:entities output)
        exports (->> entities (keep export-record)
                     (sort-by (juxt :platform :qualified-name :symbol-id)) vec)]
    {:version version
     :graph-revision revision
     :path (:file/path file)
     :file-id (:file/id file)
     :content-hash (:file/content-hash file)
     :semantic-hash (:file/semantic-hash file)
     :exports exports
     :exported-keys (set (map (juxt :platform :qualified-name) exports))
     :imported-namespaces (set (keep imported-namespace entities))
     :referenced-targets (set (keep dependency-target entities))}))

(defn write!
  "Write content-addressed shards first and activate their index last."
  [project candidate graph-revision]
  (let [directory (cache-directory project)
        manifests (mapv #(file-manifest graph-revision %) (:outputs candidate))
        entries
        (into (sorted-map)
              (map (fn [manifest]
                     (let [path (:path manifest)
                           shard (str (subs (ids/sha256
                                            (str path "\u001f"
                                                 (:content-hash manifest) "\u001f"
                                                 (:semantic-hash manifest) "\u001f"
                                                 graph-revision))
                                           0 32)
                                      ".edn")]
                       (atomic-write! (.resolve directory shard) manifest)
                       [path {:shard shard
                              :content-hash (:content-hash manifest)
                              :semantic-hash (:semantic-hash manifest)}])))
              manifests)
        index {:version version
               :graph-revision graph-revision
               :analyzers (:analyzers candidate)
               :files entries}
        active-shards (set (map (comp :shard val) entries))]
    (atomic-write! (.resolve directory index-name) index)
    (when (Files/isDirectory directory (make-array LinkOption 0))
      (with-open [stream (Files/list directory)]
        (doseq [^Path path (iterator-seq (.iterator stream))
                :let [name (str (.getFileName path))]
                :when (and (.endsWith name ".edn")
                           (not= index-name name)
                           (not (contains? active-shards name)))]
          (Files/deleteIfExists path))))
    index))

(defn load-active
  "Load one complete active manifest set or nil on any incompatibility."
  [project expected-revision expected-analyzers]
  (try
    (let [directory (cache-directory project)
          index-path (.resolve directory index-name)
          index (edn/read-string (Files/readString index-path))]
      (when (and (= version (:version index))
                 (= expected-revision (:graph-revision index))
                 (= expected-analyzers (:analyzers index)))
        (let [manifests
              (into (sorted-map)
                    (map (fn [[path {:keys [shard content-hash semantic-hash]}]]
                           (let [manifest
                                 (edn/read-string
                                  (Files/readString (.resolve directory shard)))]
                             (when-not (and (= version (:version manifest))
                                            (= expected-revision
                                               (:graph-revision manifest))
                                            (= path (:path manifest))
                                            (= content-hash
                                               (:content-hash manifest))
                                            (= semantic-hash
                                               (:semantic-hash manifest)))
                               (throw (ex-info "Stale analyzer manifest shard"
                                               {:path path})))
                             [path manifest])))
                    (:files index))]
          {:index index :files manifests})))
    (catch Throwable _ nil)))
