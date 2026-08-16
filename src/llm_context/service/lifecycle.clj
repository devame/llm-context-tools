(ns llm-context.service.lifecycle
  "Ownership and durable state for the project-local resident service.

  The OS-backed file lock is the source of truth. Descriptor and socket files
  are advertisements which may survive an abrupt process death."
  (:require [clojure.edn :as edn]
            [llm-context.service.transport :as transport])
  (:import [java.io Closeable]
           [java.nio.channels FileChannel FileLock
            OverlappingFileLockException]
           [java.nio.file AtomicMoveNotSupportedException Files LinkOption Path
            StandardCopyOption StandardOpenOption]))

(defn descriptor-path ^Path [project]
  (.resolve ^Path (:state-dir project) "service.edn"))

(defn lock-path ^Path [project]
  (.resolve ^Path (:state-dir project) "service.lock"))

(defrecord ServiceLease [^FileChannel channel ^FileLock lock]
  Closeable
  (close [_]
    (try
      (when (and lock (.isValid lock))
        (.release lock))
      (finally
        (when (and channel (.isOpen channel))
          (.close channel))))))

(defn try-acquire!
  "Try to acquire exclusive service ownership. Return a closeable lease or
  nil when a live process in this JVM or another process owns the project."
  [project]
  (Files/createDirectories
   (:state-dir project)
   (make-array java.nio.file.attribute.FileAttribute 0))
  (let [channel
        (FileChannel/open
         (lock-path project)
         (into-array java.nio.file.OpenOption
                     [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
        lock (try
               (.tryLock channel)
               (catch OverlappingFileLockException _ nil)
               (catch Throwable error
                 (.close channel)
                 (throw error)))]
    (if lock
      (->ServiceLease channel lock)
      (do (.close channel) nil))))

(defn acquire!
  "Acquire exclusive service ownership or fail without waiting."
  [project]
  (or (try-acquire! project)
      (throw
       (ex-info "A service already owns this project"
                {:exit-code 2 :type :service/already-owned}))))

(defn descriptor-snapshot
  "Read the descriptor once, preserving its exact content for compare-and-
  delete recovery. Invalid EDN is represented explicitly rather than hidden."
  [project]
  (let [path (descriptor-path project)]
    (if-not (Files/exists path (make-array LinkOption 0))
      {:path path :exists? false}
      (try
        (let [content (Files/readString path)]
          (try
            (let [value (edn/read-string content)]
              {:path path :exists? true :content content :value value
               :valid? (map? value)})
            (catch Throwable error
              {:path path :exists? true :content content :valid? false
               :error error})))
        (catch java.nio.file.NoSuchFileException _
          {:path path :exists? false})
        (catch Throwable error
          {:path path :exists? true :valid? false :error error})))))

(defn- move-replacing! [^Path source ^Path target]
  (try
    (Files/move source target
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (catch AtomicMoveNotSupportedException _
      (Files/move source target
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/REPLACE_EXISTING])))))

(defn write-descriptor!
  "Atomically publish a complete, owner-only service descriptor."
  [project descriptor]
  (let [state-dir ^Path (:state-dir project)
        target (descriptor-path project)
        temporary (Files/createTempFile
                   state-dir ".service-" ".edn.tmp"
                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/writeString
       temporary (pr-str descriptor)
       (into-array java.nio.file.OpenOption
                   [StandardOpenOption/TRUNCATE_EXISTING
                    StandardOpenOption/WRITE]))
      (transport/secure-owner-only! temporary)
      (move-replacing! temporary target)
      (transport/secure-owner-only! target)
      target
      (finally
        (Files/deleteIfExists temporary)))))

(defn- delete-canonical-socket! [project descriptor]
  (when (and (= :unix (:transport descriptor))
             (= (str (transport/socket-path project))
                (:socket-path descriptor)))
    (Files/deleteIfExists (transport/socket-path project))))

(defn reclaim-stale!
  "Reclaim one unreachable descriptor snapshot when no process owns the
  project service lock. The descriptor is re-read under the lock so a newer
  service advertisement can never be deleted by an older client."
  [project expected-content]
  (if-let [lease (try-acquire! project)]
    (with-open [_ lease]
      (let [{:keys [exists? content value]} (descriptor-snapshot project)]
        (cond
          (not exists?) {:status :absent}
          (not= expected-content content) {:status :changed}
          :else
          (do
            (Files/deleteIfExists (descriptor-path project))
            (delete-canonical-socket! project value)
            {:status :reclaimed}))))
    {:status :owned}))

(defn delete-owned!
  "Delete service advertisements only when they still belong to instance-id.
  Callers must already own the service lock."
  [project instance-id]
  (let [{:keys [exists? value]} (descriptor-snapshot project)]
    (when (and exists? (= instance-id (:instance-id value)))
      (Files/deleteIfExists (descriptor-path project))
      (delete-canonical-socket! project value)
      true)))
