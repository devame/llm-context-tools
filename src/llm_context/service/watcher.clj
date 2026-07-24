(ns llm-context.service.watcher
  "Recursive, debounced source-change trigger for the project coordinator."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [llm-context.project :as project])
  (:import [java.nio.file ClosedWatchServiceException FileVisitResult Files
            LinkOption Path SimpleFileVisitor StandardWatchEventKinds
            WatchEvent$Kind WatchService]
           [java.nio.file.attribute BasicFileAttributes]
           [java.util.concurrent TimeUnit]))

(def ^:private event-kinds
  (into-array WatchEvent$Kind
              [StandardWatchEventKinds/ENTRY_CREATE
               StandardWatchEventKinds/ENTRY_MODIFY
               StandardWatchEventKinds/ENTRY_DELETE]))

(defn- excluded? [relative-path excluded]
  (some (fn [prefix]
          (let [prefix (str/replace prefix #"[/\\]+$" "")]
            (or (= relative-path prefix)
                (str/starts-with? relative-path (str prefix "/"))
                (and (not (str/includes? prefix "/"))
                     (contains? (set (str/split relative-path #"/"))
                                prefix)))))
        excluded))

(defn- relevant? [project excluded ^Path path]
  (try
    (not (excluded? (project/relative-path project path) excluded))
    (catch IllegalArgumentException _
      false)))

(defn- register-directory! [^WatchService service keys ^Path directory]
  (let [key (.register directory service event-kinds)]
    (swap! keys assoc key directory)))

(defn- register-tree! [project service keys excluded ^Path root]
  (when (and (Files/isDirectory root (make-array LinkOption 0))
             (relevant? project excluded root))
    (Files/walkFileTree
     root
     (proxy [SimpleFileVisitor] []
       (preVisitDirectory [^Path directory ^BasicFileAttributes _]
         (if (relevant? project excluded directory)
           (do
             (register-directory! service keys directory)
             FileVisitResult/CONTINUE)
           FileVisitResult/SKIP_SUBTREE)))))
  nil)

(defn create
  "Create and register a project watcher without starting its event loop."
  [project config on-change]
  (let [service (.newWatchService (.getFileSystem ^Path (:root project)))
        keys (atom {})
        excluded (set (get-in config [:analysis :exclude]))
        roots (map #(.normalize (.resolve ^Path (:root project) ^String %))
                   (get-in config [:analysis :include]))]
    (doseq [root roots]
      (register-tree! project service keys excluded root))
    {:project project
     :config config
     :service service
     :keys keys
     :excluded excluded
     :on-change on-change
     :stop? (atom false)
     :last-change (atom (when (get-in config [:service :watch-initial])
                          (System/currentTimeMillis)))}))

(defn- consume-key! [watcher key]
  (when-let [^Path directory (get @(:keys watcher) key)]
    (doseq [event (.pollEvents key)]
      (let [kind (.kind event)]
        (if (= kind StandardWatchEventKinds/OVERFLOW)
          (reset! (:last-change watcher) (System/currentTimeMillis))
          (let [path (.normalize
                      (.resolve directory ^Path (.context event)))]
            (when (and (= kind StandardWatchEventKinds/ENTRY_CREATE)
                       (Files/isDirectory path (make-array LinkOption 0)))
              (register-tree! (:project watcher) (:service watcher)
                              (:keys watcher) (:excluded watcher) path))
            (when (relevant? (:project watcher) (:excluded watcher) path)
              (reset! (:last-change watcher)
                      (System/currentTimeMillis))))))))
  (when-not (.reset key)
    (swap! (:keys watcher) dissoc key)))

(defn- maybe-run! [watcher]
  (when-let [changed-at @(:last-change watcher)]
    (let [debounce-ms (get-in (:config watcher)
                              [:service :watch-debounce-ms])]
      (when (>= (- (System/currentTimeMillis) changed-at) debounce-ms)
        (reset! (:last-change watcher) nil)
        ((:on-change watcher))))))

(defn run!
  "Consume watcher events until stop! closes the underlying WatchService."
  [watcher]
  (try
    (while (not @(:stop? watcher))
      (when-let [key (.poll ^WatchService (:service watcher)
                            250 TimeUnit/MILLISECONDS)]
        (consume-key! watcher key))
      (maybe-run! watcher))
    :stopped
    (catch ClosedWatchServiceException _
      :stopped)))

(defn start! [watcher]
  (assoc watcher :future (future (run! watcher))))

(defn stop! [watcher]
  (reset! (:stop? watcher) true)
  (.close ^WatchService (:service watcher))
  (when-let [running (:future watcher)]
    (deref running 5000 :timeout))
  nil)
