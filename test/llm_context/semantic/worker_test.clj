(ns llm-context.semantic.worker-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.fake-index :as fake]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.reconcile :as reconcile]
            [llm-context.semantic.state :as state]
            [llm-context.semantic.worker :as worker]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(def settings
  (assoc-in (config/defaults) [:semantic :providers] [:lateon-code]))

(defn fixture []
  (let [root (Files/createTempDirectory
              "llm-context-worker-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve root "src/app.clj")
        project (project/context (str root))]
    (Files/createDirectories (.getParent path)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) "(ns sample.app)\n(defn useful [] :ok)")
    (full/analyze! project settings)
    {:project project :path path}))

(defn test-worker [graph project client]
  (worker/create graph project settings client
                 {:owner "test-worker"
                  :now-fn #(System/currentTimeMillis)
                  :sleep-fn (fn [_])}))

(deftest worker-upserts-and-commits-verified-indexed-state
  (let [{:keys [project]} (fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (let [worker (test-worker graph project client)]
        (worker/prepare! worker)
        (is (= {:leased 1 :completed 1 :retried 0
                :failed 0 :superseded 0}
               (worker/process-once! worker)))
        (is (empty? (state/job-records graph reconcile/provider)))
        (let [indexed (first (state/indexed-records
                              graph reconcile/provider))]
          (is (= 1 (:semantic.indexed/chunk-count indexed)))
          (is (= 1 (index/indexed-chunk-count
                    client (:semantic.indexed/symbol-id indexed)
                    (:semantic.indexed/document-hash indexed)))))
        (is (= :ready
               (get-in (state/semantic-summary
                        graph reconcile/provider (System/currentTimeMillis))
                       [:watermark :semantic.watermark/state])))))))

(deftest worker-deletes-all-chunks-for-removed-symbol
  (let [{:keys [project path]} (fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (let [worker (test-worker graph project client)]
        (worker/prepare! worker)
        (worker/process-once! worker)
        (is (= 1 (count (:documents (fake/snapshot client)))))))
    (Files/delete path)
    (incremental/analyze! project settings)
    (store/with-store [graph project settings]
      (let [worker (test-worker graph project client)]
        (is (= :delete
               (:semantic.job/operation
                (first (state/job-records graph reconcile/provider)))))
        (is (= 1 (:completed (worker/process-once! worker))))
        (is (empty? (:documents (fake/snapshot client))))
        (is (empty? (state/indexed-records graph reconcile/provider)))))))

(deftest source-race-is-retried-without-committing-indexed-state
  (let [{:keys [project path]} (fixture)
        client (fake/create)]
    (spit (str path) "(ns sample.app)\n(defn useful [] :changed)")
    (store/with-store [graph project settings]
      (let [worker (test-worker graph project client)
            result (worker/process-once! worker)]
        (is (= 1 (:retried result)))
        (is (= :pending
               (:semantic.job/status
                (first (state/job-records graph reconcile/provider)))))
        (is (empty? (state/indexed-records graph reconcile/provider)))
        (is (empty? (:documents (fake/snapshot client))))))))

(deftest visibility-timeout-releases-the-job-for-retry
  (let [{:keys [project]} (fixture)
        clock (atom (System/currentTimeMillis))
        base (fake/create)
        invisible
        (reify index/SemanticIndex
          (index-health [_] (index/index-health base))
          (ensure-index! [_] (index/ensure-index! base))
          (add-documents! [_ documents]
            (index/add-documents! base documents))
          (delete-symbols! [_ symbols]
            (index/delete-symbols! base symbols))
          (indexed-chunk-count [_ _ _] 0)
          (search-text [_ query options]
            (index/search-text base query options))
          (close-index! [_] nil))
        short-settings
        (-> settings
            (assoc-in [:semantic :lateon-code :visibility-timeout-ms] 20)
            (assoc-in [:semantic :lateon-code :visibility-poll-ms] 1))]
    (store/with-store [graph project short-settings]
      (let [worker
            (worker/create
             graph project short-settings invisible
             {:owner "timeout-worker"
              :now-fn #(swap! clock + 10)
              :sleep-fn (fn [_])})
            result (worker/process-once! worker)]
        (is (= 1 (:retried result)))
        (is (= :pending
               (:semantic.job/status
                (first (state/job-records graph reconcile/provider)))))))))

(deftest non-retriable-failure-is-isolated-as-failed
  (let [{:keys [project]} (fixture)
        failing
        (reify index/SemanticIndex
          (index-health [_] {:ready? true})
          (ensure-index! [_] nil)
          (add-documents! [_ _]
            (throw (ex-info "invalid model input"
                            {:retriable? false})))
          (delete-symbols! [_ _] nil)
          (indexed-chunk-count [_ _ _] 0)
          (search-text [_ _ _] [])
          (close-index! [_] nil))]
    (store/with-store [graph project settings]
      (let [result (worker/process-once!
                    (test-worker graph project failing))]
        (is (= 1 (:failed result)))
        (is (= :failed
               (:semantic.job/status
                (first (state/job-records graph reconcile/provider)))))))))
