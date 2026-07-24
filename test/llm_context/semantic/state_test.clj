(ns llm-context.semantic.state-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.semantic.state :as state]
            [llm-context.store :as store]
            [llm-context.store-test :as fixture]))

(def provider :lateon-code)

(defn job
  ([symbol-id hash now]
   (job symbol-id hash now :upsert))
  ([symbol-id hash now operation]
   (cond-> {:provider provider
            :symbol-id symbol-id
            :file-id "file:src/a.clj"
            :operation operation
            :available-at now
            :updated-at now}
     hash (assoc :document-hash hash))))

(defn indexed [symbol-id hash now]
  {:provider provider
   :symbol-id symbol-id
   :file-id "file:src/a.clj"
   :document-hash hash
   :model-revision "734b659a57935ef50562d79581c3ff1f8d825c93"
   :document-version 1
   :chunk-count 2
   :updated-at now})

(deftest dirty-markers-coalesce-and-clear
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (state/mark-dirty! graph {:provider provider
                                :file-id "file:src/a.clj"
                                :file-hash "sha256:old"
                                :operation :upsert
                                :created-at 10})
      (state/mark-dirty! graph {:provider provider
                                :file-id "file:src/a.clj"
                                :file-hash "sha256:new"
                                :operation :upsert
                                :created-at 20})
      (let [records (state/dirty-records graph provider)]
        (is (= 1 (count records)))
        (is (= "sha256:new" (:semantic.dirty/file-hash (first records))))
        (is (= 20 (:semantic.dirty/created-at (first records)))))
      (is (true? (state/clear-dirty! graph provider "file:src/a.clj")))
      (is (empty? (state/dirty-records graph provider))))))

(deftest jobs-coalesce-to-newest-document
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (state/enqueue-job! graph (job "symbol:a" "sha256:old" 10))
      (let [leased (first (state/lease-jobs! graph provider "worker-a"
                                              10 100 1))]
        (is (= :leased (:semantic.job/status leased))))
      (state/enqueue-job! graph (job "symbol:a" "sha256:new" 20))
      (let [record (first (state/job-records graph provider))]
        (is (= 1 (count (state/job-records graph provider))))
        (is (= :pending (:semantic.job/status record)))
        (is (= "sha256:new" (:semantic.job/document-hash record)))
        (is (= 0 (:semantic.job/attempts record)))
        (is (nil? (:semantic.job/lease-owner record)))))))

(deftest leases-are-exclusive-and-expire
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (state/enqueue-job! graph (job "symbol:a" "sha256:a" 10))
      (is (= 1 (count (state/lease-jobs! graph provider "worker-a"
                                         10 100 5))))
      (is (empty? (state/lease-jobs! graph provider "worker-b"
                                      10 100 5)))
      (is (= 0 (state/recover-expired-leases! graph provider 109)))
      (is (= 1 (state/recover-expired-leases! graph provider 110)))
      (let [leased (state/lease-jobs! graph provider "worker-b" 110 100 5)]
        (is (= "worker-b" (:semantic.job/lease-owner (first leased))))))))

(deftest worker-can-renew-only-its-current-lease
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (state/enqueue-job! graph (job "symbol:a" "sha256:a" 10))
      (state/lease-jobs! graph provider "worker-a" 10 100 1)
      (is (nil? (state/renew-job-lease!
                 graph (state/job-id provider "symbol:a")
                 "worker-b" 50 100)))
      (is (true? (state/renew-job-lease!
                  graph (state/job-id provider "symbol:a")
                  "worker-a" 50 100)))
      (is (= 0 (state/recover-expired-leases! graph provider 149)))
      (is (= 1 (state/recover-expired-leases! graph provider 150)))
      (is (nil? (state/renew-job-lease!
                 graph (state/job-id provider "symbol:a")
                 "worker-a" 160 100))))))

(deftest stale-worker-cannot-complete-superseded-job
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (state/enqueue-job! graph (job "symbol:a" "sha256:old" 10))
      (state/lease-jobs! graph provider "worker-a" 10 100 1)
      (state/enqueue-job! graph (job "symbol:a" "sha256:new" 20))
      (is (nil? (state/complete-job!
                 graph {:job-id (state/job-id provider "symbol:a")
                        :lease-owner "worker-a"
                        :indexed (indexed "symbol:a" "sha256:old" 30)
                        :completed-at 30})))
      (is (= "sha256:new"
             (:semantic.job/document-hash
              (first (state/job-records graph provider)))))
      (is (empty? (state/indexed-records graph provider))))))

(deftest completion-is-atomic-and-idempotent
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (state/enqueue-job! graph (job "symbol:a" "sha256:a" 10))
      (state/lease-jobs! graph provider "worker-a" 10 100 1)
      (is (true?
           (state/complete-job!
            graph {:job-id (state/job-id provider "symbol:a")
                   :lease-owner "worker-a"
                   :indexed (indexed "symbol:a" "sha256:a" 20)
                   :completed-at 20})))
      (is (empty? (state/job-records graph provider)))
      (is (= "sha256:a"
             (:semantic.indexed/document-hash
              (first (state/indexed-records graph provider)))))
      (is (nil?
           (state/complete-job!
            graph {:job-id (state/job-id provider "symbol:a")
                   :lease-owner "worker-a"
                   :indexed (indexed "symbol:a" "sha256:a" 30)
                   :completed-at 30}))))))

(deftest completion-replaces-existing-indexed-state
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (state/put-indexed! graph (indexed "symbol:a" "sha256:old" 10))
      (state/enqueue-job! graph (job "symbol:a" "sha256:new" 20))
      (state/lease-jobs! graph provider "worker-a" 20 100 1)
      (is (true?
           (state/complete-job!
            graph {:job-id (state/job-id provider "symbol:a")
                   :lease-owner "worker-a"
                   :indexed (indexed "symbol:a" "sha256:new" 30)
                   :completed-at 30})))
      (let [records (state/indexed-records graph provider)]
        (is (= 1 (count records)))
        (is (= "sha256:new"
               (:semantic.indexed/document-hash (first records))))))))

(deftest delete-completion-removes-indexed-state
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (state/put-indexed! graph (indexed "symbol:a" "sha256:a" 10))
      (state/enqueue-job! graph (job "symbol:a" nil 20 :delete))
      (state/lease-jobs! graph provider "worker-a" 20 100 1)
      (is (true?
           (state/complete-job!
            graph {:job-id (state/job-id provider "symbol:a")
                   :lease-owner "worker-a"
                   :indexed nil
                   :completed-at 30})))
      (is (empty? (state/indexed-records graph provider))))))

(deftest retry-is-bounded-and-does-not-leak-large-errors
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (state/enqueue-job! graph (job "symbol:a" "sha256:a" 10))
      (state/lease-jobs! graph provider "worker-a" 10 100 1)
      (is (= {:status :pending :attempts 1}
             (state/retry-job!
              graph {:job-id (state/job-id provider "symbol:a")
                     :lease-owner "worker-a"
                     :failed-at 20
                     :available-at 50
                     :error (apply str (repeat 3000 "x"))
                     :max-attempts 2})))
      (let [record (first (state/job-records graph provider))]
        (is (= 2000 (count (:semantic.job/last-error record))))
        (is (empty? (state/lease-jobs! graph provider "worker-b"
                                       49 100 1))))
      (state/lease-jobs! graph provider "worker-b" 50 100 1)
      (is (= {:status :failed :attempts 2}
             (state/retry-job!
              graph {:job-id (state/job-id provider "symbol:a")
                     :lease-owner "worker-b"
                     :failed-at 60
                     :available-at 100
                     :error "permanent"
                     :max-attempts 2})))
      (is (= 1 (:failed (state/semantic-summary graph provider 100)))))))

(deftest graph-replacement-preserves-operational-state
  (let [project (fixture/temp-project)
        file (fixture/file-entity "src/a.clj" "source")
        symbol (fixture/symbol-entity file "sample/a" 1)]
    (store/with-store [graph project (config/defaults)]
      (state/mark-dirty! graph {:provider provider
                                :file-id (:file/id file)
                                :file-hash (:file/content-hash file)
                                :operation :upsert
                                :created-at 10})
      (state/enqueue-job! graph (job (:symbol/id symbol) "sha256:a" 10))
      (state/put-indexed! graph (indexed "symbol:old" "sha256:old" 10))
      (store/replace-all! graph [file symbol])
      (store/replace-all! graph [])
      (is (= 1 (count (state/dirty-records graph provider))))
      (is (= 1 (count (state/job-records graph provider))))
      (is (= 1 (count (state/indexed-records graph provider)))))))

(deftest watermark-and-summary-report-provider-state
  (let [project (fixture/temp-project)]
    (store/with-store [graph project (config/defaults)]
      (state/record-watermark!
       graph {:provider provider :state :ready :last-success-at 100
              :graph-revision "sha256:graph"})
      (state/enqueue-job! graph (job "symbol:a" "sha256:a" 50))
      (let [summary (state/semantic-summary graph provider 125)]
        (is (= 1 (:pending summary)))
        (is (= 75 (:oldest-pending-ms summary)))
        (is (= :ready
               (get-in summary [:watermark :semantic.watermark/state])))))))
