(ns llm-context.service.progress
  "Live and durable progress for long-running project analysis.

  Progress is intentionally separate from the Datalevin graph. The graph is
  unavailable while a replacement is being committed, but operators still
  need a safe status surface from another terminal or after a service restart."
  (:require [clojure.edn :as edn])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path
            StandardCopyOption StandardOpenOption]
           [java.time Instant]
           [java.util UUID]))

(defn path [project]
  (.resolve ^Path (:state-dir project) "analysis-progress.edn"))

(defn- now [] (str (Instant/now)))

(defn- initial-state []
  {:state :idle
   :operation nil
   :operation-id nil
   :started-at nil
   :updated-at (now)
   :finished-at nil
   :last-error nil})

(defn read-state [project]
  (let [progress-path (path project)]
    (if-not (Files/exists progress-path (make-array LinkOption 0))
      (initial-state)
      (try
        (merge (initial-state)
               (edn/read-string (Files/readString progress-path)))
        (catch Throwable error
          (assoc (initial-state)
                 :state :unreadable
                 :last-error (.getMessage error)))))))

(defn- write-atomically! [^Path target value]
  (let [directory (.getParent target)
        temporary (.resolve directory
                            (str "." (.getFileName target) "."
                                 (UUID/randomUUID) ".tmp"))
        bytes (.getBytes (pr-str value) StandardCharsets/UTF_8)]
    (try
      (Files/write temporary bytes
                   (into-array OpenOption
                               [StandardOpenOption/CREATE_NEW
                                StandardOpenOption/WRITE]))
      (try
        (Files/move temporary target
                    (into-array StandardCopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch java.nio.file.AtomicMoveNotSupportedException _
          (Files/move temporary target
                      (into-array StandardCopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (when (Files/exists temporary (make-array LinkOption 0))
          (Files/deleteIfExists temporary))))))

(defn create [project]
  (Files/createDirectories (:state-dir project)
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (let [state (atom (read-state project))]
    ;; A running operation from a previous service is no longer live.
    (when (= :running (:state @state))
      (swap! state assoc
             :state :interrupted
             :updated-at (now)
             :finished-at (now)
             :last-error "The previous analysis process stopped before completion")
      (write-atomically! (path project) @state))
    {:project project :state state}))

(defn snapshot [{:keys [state]}]
  @state)

(defn- update! [{:keys [project state]} values]
  (let [next-state (swap! state merge values {:updated-at (now)})]
    (write-atomically! (path project) next-state)
    next-state))

(defn begin! [progress operation]
  (update! progress
           {:state :running
            :operation operation
            :operation-id (str (UUID/randomUUID))
            :started-at (now)
            :finished-at nil
            :result nil
            :last-error nil}))

(defn record! [progress event]
  (update! progress
           (merge {:state :running} (dissoc event :timestamp))))

(defn complete! [progress result]
  (update! progress
           {:state :complete
            :finished-at (now)
            :result (select-keys result [:mode :files :changed :deleted
                                         :entities :diagnostics :semantic])
            :last-error nil}))

(defn fail! [progress error]
  (update! progress
           {:state :failed
            :finished-at (now)
            :last-error (.getMessage ^Throwable error)}))
