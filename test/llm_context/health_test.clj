(ns llm-context.health-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.health :as health]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(def healthy-runtime
  {:status :ready
   :worker-status :running
   :watcher-status :running
   :query-router-status :disabled
   :candidate-reranker-status :disabled
   :worker-progress {:last-progress-at 1000}})

(deftest exact-queue-failures-are-never-hidden-by-indexed-counts
  (let [snapshot (health/semantic-health
                  {:desired 10 :indexed 10 :pending 0 :leased 0
                   :failed 2 :dirty 0 :completeness :partial
                   :analysis-progress {:state :complete}
                   :runtime healthy-runtime}
                  2000 30000)]
    (is (= :failed (:state snapshot)))
    (is (= :failed (get-in snapshot [:components :semantic-queue :state])))
    (is (= :terminal-jobs (-> snapshot :alerts first :kind)))))

(deftest outstanding-work-without-progress-is-reported-as-stalled
  (let [snapshot (health/semantic-health
                  {:desired 10 :indexed 0 :pending 10 :leased 0
                   :failed 0 :dirty 0 :completeness :partial
                   :analysis-progress {:state :complete}
                   :runtime healthy-runtime}
                  50000 30000)]
    (is (= :stalled (:state snapshot)))
    (is (= :stalled
           (get-in snapshot [:components :semantic-worker :state])))
    (is (some #(= :worker-stalled (:kind %)) (:alerts snapshot)))))

(deftest automatic-provider-recovery-remains-visible
  (let [snapshot (health/semantic-health
                  {:desired 0 :indexed 0 :pending 0 :leased 0
                   :failed 0 :dirty 0 :completeness :complete
                   :analysis-progress {:state :complete}
                   :runtime (assoc healthy-runtime :recovery
                                   {:kind :cuda-runtime-initialization-failed
                                    :from :cuda :to :cpu})}
                  2000 30000)]
    (is (= :warning (-> snapshot :alerts first :severity)))
    (is (true? (-> snapshot :alerts first :self-healing?)))))

(deftest batch-local-cuda-oom-warns-without-marking-cuda-unavailable
  (let [snapshot
        (health/semantic-health
         {:desired 0 :indexed 0 :pending 0 :leased 0
          :failed 0 :dirty 0 :completeness :complete
          :service-state :running :graph-state :ready
          :analysis-progress {:state :complete}
          :runtime
          (assoc healthy-runtime
                 :inference {:accelerator :cuda}
                 :runtime-diagnostic
                 {:kind :cuda-compression-oom
                  :severity :warning
                  :degrades-runtime? false
                  :degrades-accelerator? false
                  :self-healing? true
                  :detail "one CUDA compression batch fell back to CPU"
                  :action "reduce the update batch"})}
         2000 30000)
        alert (first (:alerts snapshot))]
    (is (= :healthy
           (get-in snapshot [:components :semantic-runtime :state])))
    (is (= :healthy
           (get-in snapshot [:components :accelerator :state])))
    (is (= :cuda-compression-oom (:kind alert)))
    (is (= :warning (:severity alert)))
    (is (true? (:self-healing? alert)))))

(deftest durable-alerts-preserve-the-original-transition-time
  (let [root (Files/createTempDirectory
              "llm-context-health-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        alert {:id "worker/stalled" :severity :error
               :component :semantic-worker :kind :worker-stalled
               :detail "stalled" :action "inspect" :self-healing? true}]
    (health/persist! project {:state :stalled :observed-at 100
                              :alerts [alert]})
    (let [updated (health/persist!
                   project {:state :stalled :observed-at 200 :alerts [alert]})]
      (is (= 100 (get-in updated [:alerts 0 :since])))
      (is (= updated (health/read-snapshot project))))))
