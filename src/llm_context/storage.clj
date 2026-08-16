(ns llm-context.storage
  "Host-aware free-space protection for generated indexes."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [llm-context.analysis.staging :as staging])
  (:import [java.nio.file Files LinkOption Path Paths]
           [java.util.stream Stream]))

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

(def ^:private no-follow-links
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- configured-path ^Path [project value]
  (let [candidate (Paths/get value (make-array String 0))]
    (.normalize
     (if (.isAbsolute candidate)
       candidate
       (.resolve ^Path (:root project) candidate)))))

(defn- path-stat
  "Measure one allowlisted artifact without following symbolic links. Files
  that disappear during the scan are ignored so inventory remains read-only
  and useful while a provider is updating its index."
  [^Path path]
  (let [path (.normalize (.toAbsolutePath path))]
    (if-not (Files/exists path no-follow-links)
      {:path (str path) :exists? false :files 0 :bytes 0 :modified-at nil}
      (with-open [^Stream entries (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
        (let [summary
              (reduce
               (fn [{:keys [files bytes modified-at] :as result} ^Path entry]
                 (try
                   (let [modified (.toMillis
                                   (Files/getLastModifiedTime entry no-follow-links))]
                     (cond-> (assoc result :modified-at (max (or modified-at 0)
                                                             modified))
                       (Files/isRegularFile entry no-follow-links)
                       (assoc :files (inc files)
                              :bytes (+ bytes (Files/size entry)))))
                   (catch java.nio.file.NoSuchFileException _ result)))
               {:files 0 :bytes 0 :modified-at nil}
               (iterator-seq (.iterator entries)))]
          (assoc summary :path (str path) :exists? true))))))

(defn inventory
  "Return a read-only inventory of project-owned generated artifacts. The
  component list is explicit: arbitrary project paths are never traversed."
  [project config]
  (let [database (configured-path project (get-in config [:store :path]))
        state-dir ^Path (:state-dir project)
        components
        [[:graph database]
         [:recovery (.resolve (.getParent database) "recovery")]
         [:semantic-index
          (configured-path project
                           (get-in config [:semantic :lateon-code :index-path]))]
         [:query-router-index
          (configured-path project
                           (get-in config [:context :query-router :index-path]))]
         [:analysis-staging
          (configured-path project (get-in config
                                           [:analysis :staging-directory]))]
         [:maintenance (.resolve state-dir "maintenance")]
         [:logs (.resolve state-dir "logs")]]]
    {:storage (status project config)
     :components
     (mapv (fn [[component path]]
             (assoc (path-stat path) :component component))
           components)}))

(declare assert-headroom! gibibytes)

(defn- selected-sizes [project config components]
  (let [selected (set components)]
    (into (sorted-map)
          (comp (filter #(contains? selected (:component %)))
                (map (juxt :component :bytes)))
          (:components (inventory project config)))))

(defn operation-guard
  "Capture a bounded operation's initial generated-artifact size. Component
  measurement is filesystem-only and never retains a database value."
  [project config operation components]
  (let [sizes (selected-sizes project config components)]
    {:project project
     :config config
     :operation operation
     :components (set components)
     :baseline-component-bytes sizes
     :baseline-bytes (reduce + 0 (vals sizes))
     :sample (atom {:sampled-at 0 :bytes nil})}))

(defn assert-operation-safe!
  "Check free-space reserve before every write and rate-limit recursive growth
  measurement. Throws before the next write unit when the operation cap is
  exceeded."
  [{:keys [project config operation components baseline-component-bytes
           baseline-bytes sample]}]
  (let [space (assert-headroom! project config operation)
        now (System/currentTimeMillis)
        interval (get-in config [:store :storage-sample-interval-ms])
        previous @sample]
    (if (< (- now (:sampled-at previous)) interval)
      (assoc space :sampled? false
             :operation operation
             :operation-growth-bytes
             (some-> (:bytes previous) (- baseline-bytes)))
      (let [component-bytes (selected-sizes project config components)
            bytes (reduce + 0 (vals component-bytes))
            growth (max 0 (- bytes baseline-bytes))
            maximum (get-in config [:store :maximum-operation-growth-bytes])
            snapshot (assoc space :sampled? true
                            :operation operation
                            :components components
                            :component-bytes component-bytes
                            :component-growth-bytes
                            (into (sorted-map)
                                  (map (fn [[component size]]
                                         [component
                                          (max 0 (- size
                                                    (get baseline-component-bytes
                                                         component 0)))]))
                                  component-bytes)
                            :operation-bytes bytes
                            :operation-growth-bytes growth
                            :maximum-operation-growth-bytes maximum)]
        (reset! sample {:sampled-at now :bytes bytes})
        (when (> growth maximum)
          (throw
           (ex-info
            (format "Storage growth limit reached before %s: %.1f GiB grown, %.1f GiB allowed"
                    (name operation) (gibibytes growth) (gibibytes maximum))
            (assoc snapshot :exit-code 1
                   :type :store/operation-growth-limit))))
        snapshot))))

(defn- direct-children [^Path directory]
  (if-not (Files/isDirectory directory no-follow-links)
    []
    (with-open [^Stream entries (Files/list directory)]
      (vec (iterator-seq (.iterator entries))))))

(defn- marker-data [^Path marker]
  (try
    (edn/read-string (Files/readString marker))
    (catch Throwable _ nil)))

(defn- marked-artifacts [^Path directory suffix expected-type]
  (->> (direct-children directory)
       (keep
        (fn [^Path artifact]
          (when (Files/isDirectory artifact no-follow-links)
            (let [artifact (.normalize (.toAbsolutePath artifact))
                  marker (.resolveSibling
                          artifact (str (.getFileName artifact) suffix))
                  data (when (Files/isRegularFile marker no-follow-links)
                         (marker-data marker))]
              (when (and (= expected-type (:artifact/type data))
                         (= 1 (:artifact/format data))
                         (= (str artifact) (:artifact/path data))
                         (integer? (:artifact/created-at data)))
                (assoc (path-stat artifact)
                       :marker-path (str marker)
                       :created-at (:artifact/created-at data)))))))
       (sort-by :created-at >)
       vec))

(defn- staging-artifacts [project config]
  (->> (direct-children (staging/root-path project config))
       (keep
        (fn [^Path artifact]
          (when (and (Files/isDirectory artifact no-follow-links)
                     (re-matches #"[0-9a-f]{32}" (str (.getFileName artifact))))
            (let [artifact (.normalize (.toAbsolutePath artifact))
                  index (marker-data (.resolve artifact "index.edn"))
                  complete? (and (= staging/format-version (:format index))
                                 (= (str (.getFileName artifact))
                                    (:generation index))
                                 (integer? (:completed-at index)))
                  measured (path-stat artifact)]
              (assoc measured
                     :created-at (if complete?
                                   (:completed-at index)
                                   (:modified-at measured))
                     :complete? complete?)))))
       (filter :created-at)
       (sort-by :created-at >)
       vec))

(defn cleanup-plan
  "Plan retention cleanup without mutating the filesystem. Only artifacts
  carrying llm-context's exact marker contract are eligible. The newest
  recovery archive and newest verified compact copy are always retained."
  [project config older-than-days]
  (when-not (and (integer? older-than-days) (pos? older-than-days))
    (throw (ex-info "Retention age must be a positive number of days"
                    {:exit-code 2 :type :maintenance/invalid-retention-age})))
  (let [database (configured-path project (get-in config [:store :path]))
        cutoff (- (System/currentTimeMillis)
                  (* older-than-days 24 60 60 1000))
        groups
        [[:recovery
          (marked-artifacts (.resolve (.getParent database) "recovery")
                            ".recovery.edn"
                            :interrupted-graph-recovery)]
         [:maintenance
          (marked-artifacts (.resolve ^Path (:state-dir project) "maintenance")
                            ".verified.edn"
                            :verified-compact-copy)]
         [:analysis-staging (staging-artifacts project config)]]
        artifacts
        (mapcat
         (fn [[component entries]]
           (map-indexed
            (fn [index entry]
              (let [protected? (zero? index)
                    eligible? (and (not protected?)
                                   (< (:created-at entry) cutoff))]
                (assoc entry :component component
                       :protected? protected?
                       :eligible? eligible?)))
            entries))
         groups)]
    {:older-than-days older-than-days
     :cutoff cutoff
     :candidates (vec artifacts)
     :eligible-count (count (filter :eligible? artifacts))
     :eligible-bytes (reduce + 0 (map :bytes (filter :eligible? artifacts)))}))

(defn- delete-tree! [^Path path]
  (with-open [^Stream entries (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
    (doseq [^Path entry (sort-by #(.getNameCount ^Path %) >
                                 (iterator-seq (.iterator entries)))]
      (Files/deleteIfExists entry))))

(defn apply-cleanup!
  "Delete only candidates produced by cleanup-plan. Each target was selected
  from a direct-child allowlist and validated with an exact marker."
  [project config older-than-days]
  (let [plan (cleanup-plan project config older-than-days)
        deleted
        (mapv
         (fn [{:keys [path marker-path bytes component]}]
           (delete-tree! (Paths/get path (make-array String 0)))
           (when marker-path
             (Files/deleteIfExists
              (Paths/get marker-path (make-array String 0))))
           {:path path :component component :bytes bytes})
         (filter :eligible? (:candidates plan)))]
    (assoc plan :applied? true :deleted deleted)))

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
