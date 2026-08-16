(ns llm-context.semantic.worker-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.document :as document]
            [llm-context.semantic.fake-index :as fake]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.reconcile :as reconcile]
            [llm-context.semantic.state :as state]
            [llm-context.semantic.worker :as worker]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(def settings
  (assoc-in (config/defaults) [:semantic :providers] [:lateon-code]))

(defn fixture
  ([] (fixture "(ns sample.app)\n(defn useful [] :ok)"))
  ([source]
   (let [root (Files/createTempDirectory
               "llm-context-worker-"
               (make-array java.nio.file.attribute.FileAttribute 0))
         path (.resolve root "src/app.clj")
         project (project/context (str root))]
     (Files/createDirectories
      (.getParent path)
      (make-array java.nio.file.attribute.FileAttribute 0))
     (spit (str path) source)
     (full/analyze! project settings)
     {:project project :path path})))

(defn test-worker [graph project client]
  (worker/create graph project settings client
                 {:owner "test-worker"
                  :now-fn #(System/currentTimeMillis)
                  :sleep-fn (fn [_])}))

(defn semantic-documents [client]
  (->> (:documents (fake/snapshot client))
       vals
       (remove #(str/starts-with? (:symbol-id %)
                                  "semantic-generation:"))))

(deftest worker-upserts-and-commits-verified-indexed-state
  (let [{:keys [project]} (fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (let [worker (test-worker graph project client)]
        (worker/prepare! worker)
        (is (= {:leased 2 :completed 2 :retried 0
                :failed 0 :superseded 0 :accepted-documents 2}
               (select-keys
                (worker/process-once! worker)
                [:leased :completed :retried :failed :superseded
                 :accepted-documents])))
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
                       [:watermark :semantic.watermark/state])))
        (is (string?
             (get-in (state/semantic-summary
                      graph reconcile/provider (System/currentTimeMillis))
                     [:watermark
                      :semantic.watermark/graph-revision])))
        (is (string?
             (get-in (state/semantic-summary
                      graph reconcile/provider (System/currentTimeMillis))
                     [:watermark
                      :semantic.watermark/index-generation])))))))

(deftest missing-index-generation-fails-closed-and-requeues-all-symbols
  (let [{:keys [project]} (fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (let [first-worker (test-worker graph project client)]
        (worker/prepare! first-worker)
        (worker/process-once! first-worker)
        (let [generation
              (:semantic.watermark/index-generation
               (state/watermark graph reconcile/provider))]
          (index/delete-symbols! client [(str "semantic-generation:"
                                               generation)])
          (let [prepared (worker/prepare! (test-worker graph project client))]
            (is (true? (get-in prepared [:generation :invalidated?])))
            (is (empty? (state/indexed-records graph reconcile/provider)))
            (is (= 2 (count (state/job-records graph reconcile/provider))))))))))

(deftest worker-builds-each-file-once-and-submits-fresh-documents-together
  (let [{:keys [project]}
        (fixture "(ns sample.app)\n(defn useful [] :ok)\n(defn other [] :ok)")
        client (fake/create)
        builds (atom 0)
        renewals (atom 0)
        completion-batches (atom [])
        original-build document/build-symbols
        original-renew state/renew-job-leases!
        original-complete state/complete-jobs!]
    (store/with-store [graph project settings]
      (let [worker (test-worker graph project client)
            result
            (with-redefs [document/build-symbols
                          (fn [& args]
                            (swap! builds inc)
                            (apply original-build args))
                          state/renew-job-leases!
                          (fn [graph job-ids & args]
                            (swap! renewals + (count job-ids))
                            (apply original-renew graph job-ids args))
                          state/complete-jobs!
                          (fn [graph completions]
                            (swap! completion-batches conj
                                   (count completions))
                            (original-complete graph completions))]
              (worker/prepare! worker)
              (worker/process-once! worker))
            additions
            (->> (:operations (fake/snapshot client))
                 (filter #(= :add (:operation %)))
                 (keep (fn [operation]
                         (let [ids (remove #(str/starts-with?
                                            % "semantic-generation:")
                                           (:document-ids operation))]
                           (when (seq ids)
                             (assoc operation :document-ids (vec ids)))))))]
        (is (= 3 (:completed result)))
        (is (= 1 @builds))
        (is (= 1 (count additions)))
        (is (= 3 (count (:document-ids (first additions)))))
        (is (<= 6 @renewals 9))
        (is (= [3] @completion-batches))))))

(deftest worker-keeps-multiple-update-requests-in-flight
  (let [definitions (apply str (for [index (range 40)]
                                 (format "(defn f%d [] %d)\n" index index)))
        {:keys [project]} (fixture (str "(ns sample.app)\n" definitions))
        base (fake/create)
        active (atom 0)
        maximum (atom 0)
        concurrent
        (reify index/SemanticIndex
          (index-health [_] (index/index-health base))
          (ensure-index! [_] (index/ensure-index! base))
          (add-documents! [_ documents]
            (let [current (swap! active inc)]
              (swap! maximum max current)
              (try
                (Thread/sleep 25)
                (index/add-documents! base documents)
                (finally
                  (swap! active dec)))))
          (delete-symbols! [_ symbols]
            (index/delete-symbols! base symbols))
          (indexed-documents [_ symbols]
            (index/indexed-documents base symbols))
          (indexed-chunk-count [_ symbol hash]
            (index/indexed-chunk-count base symbol hash))
          (search-text [_ query options]
            (index/search-text base query options))
          (close-index! [_] nil))
        concurrent-settings
        (-> settings
            (assoc-in [:semantic :lateon-code :update-batch-size] 10)
            (assoc-in [:semantic :lateon-code :update-concurrency] 4))]
    (store/with-store [graph project concurrent-settings]
      (let [semantic-worker
            (worker/create graph project concurrent-settings concurrent
                           {:owner "concurrency-worker"})]
        (worker/prepare! semantic-worker)
        (let [result (worker/process-once! semantic-worker)]
          (is (= 40 (:completed result)))
          (is (= 4 (:upload-batches result)))
          (is (<= 2 @maximum 4)))))))

(deftest worker-recovers-leases-that-expire-after-startup
  (let [{:keys [project]} (fixture)
        client (fake/create)
        clock (atom (System/currentTimeMillis))]
    (store/with-store [graph project settings]
      (is (= 1 (count (state/lease-jobs!
                       graph reconcile/provider "stopped-worker"
                       @clock 10 1))))
      (swap! clock + 11)
      (let [worker (worker/create
                    graph project settings client
                    {:owner "replacement-worker"
                     :now-fn #(swap! clock inc)
                     :sleep-fn (fn [_])})]
        (is (= 2 (:completed (worker/process-once! worker))))
        (is (empty? (state/job-records graph reconcile/provider)))))))

(deftest worker-reconciles-dirty-markers-created-after-startup
  (let [{:keys [project]} (fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (let [worker (test-worker graph project client)]
        (worker/prepare! worker)
        (is (= 2 (:completed (worker/process-once! worker))))
        (reconcile/mark-full! graph)
        (is (= 1 (count (state/dirty-records graph reconcile/provider))))
        (is (zero? (:leased (worker/process-once! worker))))
        (is (empty? (state/dirty-records graph reconcile/provider)))))))

(deftest worker-deletes-all-chunks-for-removed-symbol
  (let [{:keys [project path]} (fixture)
        client (fake/create)]
    (store/with-store [graph project settings]
      (let [worker (test-worker graph project client)]
        (worker/prepare! worker)
        (worker/process-once! worker)
        (is (= 2 (count (semantic-documents client))))))
    (Files/delete path)
    (incremental/analyze! project settings)
    (store/with-store [graph project settings]
      (let [worker (test-worker graph project client)]
        (is (= :delete
               (:semantic.job/operation
                (first (state/job-records graph reconcile/provider)))))
        (is (= 2 (:completed (worker/process-once! worker))))
        (is (empty? (semantic-documents client)))
        (is (empty? (state/indexed-records graph reconcile/provider)))))))

(deftest source-race-is-retried-without-committing-indexed-state
  (let [{:keys [project path]} (fixture)
        client (fake/create)]
    (spit (str path) "(ns sample.app)\n(defn useful [] :changed)")
    (store/with-store [graph project settings]
      (let [worker (test-worker graph project client)
            result (worker/process-once! worker)]
        (is (= 2 (:retried result)))
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
          (indexed-documents [_ _] [])
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
        (is (= 2 (:retried result)))
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
          (indexed-documents [_ _] [])
          (indexed-chunk-count [_ _ _] 0)
          (search-text [_ _ _] [])
          (close-index! [_] nil))]
    (store/with-store [graph project settings]
      (let [result (worker/process-once!
                    (test-worker graph project failing))]
        (is (= 2 (:failed result)))
        (is (= :failed
               (:semantic.job/status
                (first (state/job-records graph reconcile/provider)))))))))
