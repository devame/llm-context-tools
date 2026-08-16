(ns llm-context.storage
  "Host-aware free-space protection for generated indexes."
  (:require [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path Paths]))

(def ^:private gib (* 1024 1024 1024))

(defn- wsl? []
  (or (not (str/blank? (System/getenv "WSL_DISTRO_NAME")))
      (try
        (str/includes?
         (str/lower-case (slurp "/proc/sys/kernel/osrelease")) "microsoft")
        (catch Throwable _ false))))

(defn- existing-ancestor ^Path [^Path path]
  (loop [candidate (.normalize (.toAbsolutePath path))]
    (cond
      (nil? candidate) nil
      (Files/exists candidate (make-array LinkOption 0)) candidate
      :else (recur (.getParent candidate)))))

(defn probe-path
  "Resolve the filesystem whose usable capacity protects generated writes.
  An explicit path wins. On WSL, /mnt/c is the conservative default because
  the ext4 filesystem reports its thin-provisioned VHDX ceiling rather than
  host capacity. Users whose distro VHDX lives elsewhere must override it."
  [{:keys [^Path root]} config]
  (let [configured (get-in config [:store :free-space-probe-path])
        configured-path
        (when configured
          (let [candidate (Paths/get configured (make-array String 0))]
            (if (.isAbsolute candidate)
              candidate
              (.resolve root candidate))))
        db-path (.resolve root (get-in config [:store :path]))
        automatic (if (and (wsl?)
                           (Files/exists (Paths/get "/mnt/c"
                                                    (make-array String 0))
                                         (make-array LinkOption 0)))
                    (Paths/get "/mnt/c" (make-array String 0))
                    db-path)]
    (or (existing-ancestor (or configured-path automatic))
        (throw (ex-info "No existing filesystem path is available for the storage safety check"
                        {:exit-code 2
                         :type :store/space-probe-missing
                         :configured-path configured})))))

(defn status
  "Return usable bytes and configured reserve for generated writes."
  [project config]
  (let [path (probe-path project config)
        store (Files/getFileStore path)
        usable (.getUsableSpace store)
        minimum (get-in config [:store :minimum-free-space-bytes])]
    {:probe-path (str path)
     :filesystem (str (.name store))
     :usable-bytes usable
     :minimum-free-space-bytes minimum
     :safe? (>= usable minimum)}))

(defn gibibytes [bytes]
  (/ (double bytes) gib))

(defn assert-headroom!
  "Fail before the next generated write when the configured reserve would be
  crossed. The caller should leave graph/index state retryable."
  ([project config] (assert-headroom! project config nil))
  ([project config operation]
   (let [snapshot (status project config)]
     (when-not (:safe? snapshot)
       (throw
        (ex-info
         (format (str "Storage safety reserve reached before %s: %.1f GiB "
                      "usable at %s, %.1f GiB required")
                 (name (or operation :write))
                 (gibibytes (:usable-bytes snapshot))
                 (:probe-path snapshot)
                 (gibibytes (:minimum-free-space-bytes snapshot)))
         (assoc snapshot
                :exit-code 1
                :type :store/insufficient-space
                :operation operation))))
     snapshot)))
