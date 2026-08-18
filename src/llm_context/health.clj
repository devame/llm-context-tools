(ns llm-context.health
  "Project-health reduction and durable command-boundary alert snapshot."
  (:require [clojure.edn :as edn])
  (:import [java.nio.file Files LinkOption OpenOption Path StandardCopyOption
            StandardOpenOption]
           [java.util UUID]))

(defn snapshot-path [project]
  (.resolve ^Path (:state-dir project) "health.edn"))

(declare read-snapshot)

(defn persist! [project snapshot]
  (Files/createDirectories
   (:state-dir project)
   (make-array java.nio.file.attribute.FileAttribute 0))
  (let [previous (read-snapshot project)
        previous-alerts (into {} (map (juxt :id identity)) (:alerts previous))
        snapshot
        (update snapshot :alerts
                (fn [alerts]
                  (mapv (fn [alert]
                          (let [prior (get previous-alerts (:id alert))]
                            (assoc alert :since
                                   (if (= (:severity prior) (:severity alert))
                                     (or (:since prior) (:observed-at snapshot))
                                     (:observed-at snapshot)))))
                        alerts)))
        target (snapshot-path project)
        temporary (.resolve ^Path (:state-dir project)
                            (str ".health." (UUID/randomUUID) ".tmp"))]
    (try
      (Files/writeString temporary (pr-str snapshot)
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
      snapshot
      (finally
        (Files/deleteIfExists temporary)))))

(defn read-snapshot [project]
  (let [path (snapshot-path project)]
    (when (Files/exists path (make-array LinkOption 0))
      (try
        (edn/read-string (Files/readString path))
        (catch Throwable error
          {:state :unknown
           :summary "durable health snapshot is unreadable"
           :alerts [{:id "service/health-snapshot-unreadable"
                     :severity :error
                     :component :service
                     :kind :health-snapshot-unreadable
                     :detail (.getMessage error)
                     :action "run llm-context doctor"
                     :self-healing? false}]})))))

(def ^:private state-priority
  {:unknown 0
   :disabled 0
   :healthy 1
   :starting 2
   :indexing 2
   :degraded 3
   :recovering 4
   :stalled 5
   :failed 6})

(defn- alert
  [severity component kind detail action self-healing?]
  {:id (str (name component) "/" (name kind))
   :severity severity
   :component component
   :kind kind
   :detail detail
   :action action
   :self-healing? self-healing?})

(defn- worst-state [states]
  (or (last (sort-by #(get state-priority % 0) states)) :unknown))

(defn- component [state detail & {:as more}]
  (merge {:state state :detail detail} more))

(defn semantic-health
  "Derive one bounded operational health snapshot from semantic status.

  `stall-window-ms` is deliberately supplied by the caller so configuration can
  scale it with provider update and visibility deadlines."
  ([status now]
   (semantic-health status now 300000))
  ([status now stall-window-ms]
   (let [runtime (:runtime status)
         runtime-status (:status runtime)
         worker-status (:worker-status runtime)
         watcher-status (:watcher-status runtime)
         router-status (:query-router-status runtime)
         reranker-status (:candidate-reranker-status runtime)
         progress (:worker-progress runtime)
         watermark-state (get-in status [:watermark
                                         :semantic.watermark/state])
         last-progress-at (:last-progress-at progress)
         outstanding (+ (long (or (:pending status) 0))
                        (long (or (:leased status) 0)))
         stalled? (and (pos? outstanding)
                       (number? last-progress-at)
                       (> (- now last-progress-at) stall-window-ms))
         runtime-component
         (cond
           (= :disabled runtime-status)
           (component :disabled "semantic runtime disabled")

           (contains? #{:starting} runtime-status)
           (component :starting "semantic runtime is starting")

           (= :recovering runtime-status)
           (component :recovering
                      (or (:detail runtime) "semantic runtime is recovering")
                      :attempt (:recovery-attempt runtime))

           (= :ready runtime-status)
           (if (:runtime-diagnostic runtime)
             (component :degraded
                        (get-in runtime [:runtime-diagnostic :detail]))
             (component :healthy "semantic runtime ready"))

           (contains? #{:failed :unavailable :not-running} runtime-status)
           (component :failed
                      (or (:detail runtime)
                          (some-> runtime-status name)
                          "semantic runtime unavailable"))

           :else (component :unknown "semantic runtime state unknown"))
         worker-component
         (cond
           (= :disabled worker-status) (component :disabled "worker disabled")
           (= :starting worker-status) (component :starting "worker starting")
           (= :recovering worker-status)
           (component :recovering
                      (or (:worker-detail runtime) "worker recovering"))
           (= :failed worker-status)
           (component :failed (or (:worker-detail runtime) "worker failed"))
           stalled?
           (component :stalled
                      (str "no semantic progress for "
                           (long (/ (- now last-progress-at) 1000)) " seconds")
                      :last-progress-at last-progress-at)
           (= :running worker-status)
           (component (if (pos? outstanding) :indexing :healthy)
                      (if (pos? outstanding) "worker indexing" "worker idle")
                      :last-progress-at last-progress-at)
           (nil? worker-status) (component :unknown "worker state unavailable")
           :else (component :degraded (str "worker " (name worker-status))))
         queue-component
         (cond
           (= :disabled runtime-status)
           (component :disabled "semantic queue disabled")

           (pos? (long (or (:failed status) 0)))
           (component :failed
                      (str (:failed status) " terminal semantic jobs")
                      :pending (:pending status) :leased (:leased status)
                      :failed (:failed status) :dirty (:dirty status))

           (= :failed watermark-state)
           (component :failed "semantic watermark records a provider failure"
                      :watermark watermark-state)

           (= :degraded watermark-state)
           (component :degraded "semantic watermark is degraded"
                      :watermark watermark-state)

           (pos? (long (or (:dirty status) 0)))
           (component :degraded
                      (str (:dirty status) " files awaiting semantic reconciliation")
                      :pending (:pending status) :leased (:leased status)
                      :failed (:failed status) :dirty (:dirty status))

           (pos? outstanding)
           (component :indexing
                      (str outstanding " semantic jobs outstanding")
                      :pending (:pending status) :leased (:leased status)
                      :failed (:failed status) :dirty (:dirty status))

           (= :complete (:completeness status))
           (component :healthy "semantic coverage complete"
                      :pending 0 :leased 0 :failed 0 :dirty 0)

           :else
           (component :degraded "semantic coverage is partial"
                      :pending (:pending status) :leased (:leased status)
                      :failed (:failed status) :dirty (:dirty status)))
         analysis-state (get-in status [:analysis-progress :state])
         analysis-component
         (case analysis-state
           :failed (component :failed
                              (or (get-in status [:analysis-progress :last-error])
                                  "analysis failed"))
           :interrupted (component :degraded
                                   "previous analysis was interrupted")
           :unreadable (component :failed "analysis progress is unreadable")
           :running (component :indexing "analysis is running")
           (component :healthy "analysis is not failing"))
         watcher-component
         (cond
           (= :disabled watcher-status) (component :disabled "watcher disabled")
           (contains? #{:failed :not-running} watcher-status)
           (component :failed (or (:watcher-detail runtime) "watcher failed"))
           (= :recovering watcher-status)
           (component :recovering
                      (or (:watcher-detail runtime) "watcher recovering"))
           (= :running watcher-status) (component :healthy "watcher running")
           (nil? watcher-status) (component :unknown "watcher state unavailable")
           :else (component :degraded (str "watcher " (name watcher-status))))
         advisory-component
         (fn [label status detail]
           (cond
             (= :disabled status) (component :disabled (str label " disabled"))
             (= :ready status) (component :healthy (str label " ready"))
             (= :recovering status)
             (component :recovering (or detail (str label " recovering")))
             (= :failed status)
             (component :degraded (or detail (str label " failed")))
             (= :unavailable status)
             (component :degraded (or detail (str label " unavailable")))
             (nil? status) (component :unknown (str label " state unavailable"))
             :else (component :degraded (str label " " (name status)))))
         graph-component
         (case (:graph-state status)
           :ready (component :healthy "canonical graph ready")
           :updating (component :indexing "canonical graph updating")
           :incompatible (component :failed "canonical graph format incompatible")
           :unavailable (component :failed "canonical graph unavailable")
           (component :unknown "canonical graph state unavailable"))
         accelerator-component
         (cond
           (= :disabled runtime-status) (component :disabled "accelerator disabled")
           (or (:runtime-diagnostic runtime) (:recovery runtime)
               (seq (get-in runtime [:inference :fallback-reasons])))
           (component :degraded
                      (str "effective accelerator "
                           (name (or (get-in runtime [:inference :accelerator])
                                     :unknown))))
           (get-in runtime [:inference :accelerator])
           (component :healthy
                      (str "effective accelerator "
                           (name (get-in runtime [:inference :accelerator]))))
           :else (component :unknown "accelerator profile unavailable"))
         storage-snapshot (:storage progress)
         storage-component
         (cond
           (false? (:safe? storage-snapshot))
           (component :failed "configured storage reserve is exhausted")
           (true? (:safe? storage-snapshot))
           (component :healthy "storage reserve is available")
           :else (component :unknown "storage was not sampled in this status"))
         service-component
         (case (:service-state status)
           :running (component :healthy "resident service RPC responsive")
           :not-running (component :failed "resident project service is not running")
           (component :unknown "resident service state unavailable"))
         components
         {:service service-component
          :graph graph-component
          :storage storage-component
          :accelerator accelerator-component
          :analysis analysis-component
          :watcher watcher-component
          :semantic-runtime runtime-component
          :semantic-worker worker-component
          :semantic-queue queue-component
          :query-router (advisory-component
                         "query router" router-status
                         (:query-router-detail runtime))
          :candidate-reranker (advisory-component
                               "candidate reranker" reranker-status
                               (:candidate-reranker-detail runtime))}
         alerts
         (cond-> []
           (:recovery runtime)
           (conj (alert :warning :semantic-runtime
                        (or (get-in runtime [:recovery :kind])
                            :automatic-recovery)
                        (str "semantic runtime recovered from "
                             (name (or (get-in runtime [:recovery :kind])
                                       :automatic-recovery)))
                        (or (get-in runtime [:recovery :action])
                            "run llm-context doctor to inspect the degraded provider")
                        true))

           (= :failed (:state service-component))
           (conj (alert :error :service :service-not-running
                        (:detail service-component)
                        "run llm-context analyze or llm-context service start"
                        true))

           (:runtime-diagnostic runtime)
           (conj (alert :error :semantic-runtime
                        (or (get-in runtime [:runtime-diagnostic :kind])
                            :provider-degraded)
                        (get-in runtime [:runtime-diagnostic :detail])
                        (get-in runtime [:runtime-diagnostic :action])
                        (= :auto (get-in runtime [:inference
                                                  :requested-accelerator]))))

           (and (= :failed (:state runtime-component))
                (nil? (:runtime-diagnostic runtime)))
           (conj (alert :error :semantic-runtime :runtime-unavailable
                        (:detail runtime-component)
                        "run llm-context doctor and llm-context analyze"
                        true))

           (= :failed (:state worker-component))
           (conj (alert :error :semantic-worker :worker-failed
                        (:detail worker-component)
                        "inspect semantic status and service.log"
                        true))

           (= :stalled (:state worker-component))
           (conj (alert :error :semantic-worker :worker-stalled
                        (:detail worker-component)
                        "inspect provider health and restart the project service"
                        true))

           (pos? (long (or (:failed status) 0)))
           (conj (alert :error :semantic-queue :terminal-jobs
                        (str (:failed status) " semantic jobs exhausted retries")
                        "run semantic failures; repair the cause before retrying"
                        false))

           (contains? #{:degraded :failed} watermark-state)
           (conj (alert (if (= :failed watermark-state) :error :warning)
                        :semantic-queue :watermark-unhealthy
                        (str "semantic watermark is " (name watermark-state))
                        "inspect semantic failures and the provider runtime"
                        false))

           (pos? (long (or (:dirty status) 0)))
           (conj (alert :warning :semantic-queue :dirty-reconciliation
                        (str (:dirty status) " semantic files remain deferred")
                        "run semantic dirty and repair source/reconciliation errors"
                        false))

           (contains? #{:failed :interrupted :unreadable} analysis-state)
           (conj (alert :error :analysis :analysis-unhealthy
                        (:detail analysis-component)
                        "run llm-context analyze and inspect service.log"
                        (= :interrupted analysis-state)))

           (contains? #{:failed :not-running} watcher-status)
           (conj (alert :error :watcher :watcher-failed
                        (:detail watcher-component)
                        "run analyze manually and restart the project service"
                        true))

           (contains? #{:failed :unavailable :not-running} router-status)
           (conj (alert :warning :query-router :router-degraded
                        (:detail (:query-router components))
                        "inspect query-router.log and restart the project service"
                        true))

           (:query-router-recovery runtime)
           (conj (alert :warning :query-router :router-recovered
                        (str "query router recovered from "
                             (name (get-in runtime
                                           [:query-router-recovery :kind])))
                        "run llm-context doctor to inspect accelerator readiness"
                        true))

           (contains? #{:failed :unavailable :not-running} reranker-status)
           (conj (alert :warning :candidate-reranker :reranker-degraded
                        (:detail (:candidate-reranker components))
                        "inspect query-router.log and project service health"
                        true))

           (= :failed (:state storage-component))
           (conj (alert :error :storage :storage-unsafe
                        (:detail storage-component)
                        "free storage or adjust the configured reserve"
                        true)))
         state (worst-state (map :state (vals components)))]
     {:state state
      :observed-at now
      :summary (case state
                 :healthy "project runtime is healthy"
                 :starting "project runtime is starting"
                 :indexing "project indexing is active"
                 :degraded "project is usable with reduced capability"
                 :recovering "project runtime is recovering"
                 :stalled "project indexing is stalled"
                 :failed "project has unresolved failures"
                 :unknown "project health is unknown"
                 "project health is unavailable")
      :components components
      :alerts alerts})))

(defn unhealthy? [health]
  (contains? #{:starting :degraded :recovering :stalled :failed :unknown}
             (:state health)))
