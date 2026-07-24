(ns llm-context.semantic.reconcile-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.document :as document]
            [llm-context.semantic.reconcile :as reconcile]
            [llm-context.semantic.state :as state]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(def settings
  (assoc-in (config/defaults) [:semantic :providers] [:lateon-code]))

(defn project-with-source [source]
  (let [root (Files/createTempDirectory
              "llm-context-reconcile-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve root "src/app.clj")]
    (Files/createDirectories (.getParent path)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) source)
    {:project (project/context (str root))
     :path path}))

(defn job [graph]
  (first (state/job-records graph reconcile/provider)))

(defn record-indexed! [graph job now]
  (state/put-indexed!
   graph
   {:provider reconcile/provider
    :symbol-id (:semantic.job/symbol-id job)
    :file-id (:semantic.job/file-id job)
    :document-hash (:semantic.job/document-hash job)
    :model-revision
    (get-in settings [:semantic :lateon-code :model-revision])
    :document-version
    (get-in settings [:semantic :lateon-code :document-version])
    :chunk-count 1
    :updated-at now})
  (state/cancel-job! graph reconcile/provider
                     (:semantic.job/symbol-id job)))

(deftest full-analysis-plans-symbol-upserts-without-running-a-model
  (let [{:keys [project]} (project-with-source
                           "(ns sample.app)\n(defn useful [] :ok)")
        result (full/analyze! project settings)]
    (is (= 1 (get-in result [:semantic :queued-upserts])))
    (is (zero? (get-in result [:semantic :queued-deletes])))
    (store/with-store [graph project settings]
      (let [record (job graph)]
        (is (= :upsert (:semantic.job/operation record)))
        (is (= :pending (:semantic.job/status record)))
        (is (str/starts-with? (:semantic.job/document-hash record)
                              "sha256:")))
      (is (empty? (state/dirty-records graph reconcile/provider))))))

(deftest returning-to-indexed-content-cancels-obsolete-work
  (let [original "(ns sample.app)\n(defn useful [] :old)"
        changed "(ns sample.app)\n(defn useful [] :new)"
        {:keys [project path]} (project-with-source original)]
    (full/analyze! project settings)
    (store/with-store [graph project settings]
      (record-indexed! graph (job graph) 10))
    (spit (str path) changed)
    (let [result (incremental/analyze! project settings)]
      (is (= 1 (get-in result [:semantic :queued-upserts]))))
    (spit (str path) original)
    (let [result (incremental/analyze! project settings)]
      ;; One-line Clojure definitions include their body in the current graph
      ;; identity signature. Returning to the original source cancels both the
      ;; old-symbol deletion and the changed-symbol insertion.
      (is (= 2 (get-in result [:semantic :cancelled])))
      (is (= 1 (get-in result [:semantic :unchanged]))))
    (store/with-store [graph project settings]
      (is (empty? (state/job-records graph reconcile/provider)))
      (is (= 1 (count (state/indexed-records
                       graph reconcile/provider)))))))

(deftest deleting-an-indexed-symbol-plans-a-delete
  (let [{:keys [project path]}
        (project-with-source "(ns sample.app)\n(defn useful [] :ok)")]
    (full/analyze! project settings)
    (store/with-store [graph project settings]
      (record-indexed! graph (job graph) 10))
    (Files/delete path)
    (let [result (incremental/analyze! project settings)]
      (is (= 1 (:deleted result)))
      (is (= 1 (get-in result [:semantic :queued-deletes]))))
    (store/with-store [graph project settings]
      (is (= :delete (:semantic.job/operation (job graph)))))))

(deftest full-recovery-deletes-indexed-symbols-missing-from-the-graph
  (let [{:keys [project]} (project-with-source "")]
    (store/with-store [graph project settings]
      (state/put-indexed!
       graph {:provider reconcile/provider
              :symbol-id "symbol:ghost"
              :file-id "file:src/removed.clj"
              :document-hash "sha256:ghost"
              :model-revision
              (get-in settings [:semantic :lateon-code :model-revision])
              :document-version 1 :chunk-count 1 :updated-at 10})
      (reconcile/mark-full! graph)
      (let [result (reconcile/reconcile! graph project settings 20)]
        (is (= 1 (:queued-deletes result)))
        (is (= :delete (:semantic.job/operation (job graph))))
        (is (empty? (state/dirty-records graph reconcile/provider)))))))

(deftest source-edited-after-analysis-defers-and-retains-dirty-state
  (let [{:keys [project path]}
        (project-with-source "(ns sample.app)\n(defn useful [] :old)")]
    (full/analyze! project settings)
    (store/with-store [graph project settings]
      (state/cancel-job! graph reconcile/provider
                         (:semantic.job/symbol-id (job graph)))
      (let [[file-id file-hash]
            (first
             (store/query
              graph
              '[:find ?id ?hash
                :where [?file :file/id ?id]
                       [?file :file/content-hash ?hash]]
              []))]
        (state/mark-dirty! graph
                           (reconcile/dirty-marker
                            file-id file-hash :upsert 20))
        (spit (str path) "(ns sample.app)\n(defn useful [] :new)")
        (let [result (reconcile/reconcile! graph project settings 30)]
          (is (= 1 (:deferred result)))
          (is (empty? (state/job-records graph reconcile/provider)))
          (is (= 1 (count (state/dirty-records
                           graph reconcile/provider)))))))))

(deftest one-file-failure-does-not-block-unrelated-semantic-work
  (let [{:keys [project]} (project-with-source
                           "(ns sample.app)\n(defn useful [] :ok)")
        second-path (.resolve (:root project) "src/other.clj")]
    (spit (str second-path) "(ns sample.other)\n(defn healthy [] :ok)")
    (full/analyze! project settings)
    (store/with-store [graph project settings]
      (doseq [record (state/job-records graph reconcile/provider)]
        (state/cancel-job! graph reconcile/provider
                           (:semantic.job/symbol-id record)))
      (let [files (store/query
                   graph
                   '[:find ?id ?path ?hash
                     :where [?file :file/id ?id]
                            [?file :file/path ?path]
                            [?file :file/content-hash ?hash]]
                   [])
            failed-id (ffirst (filter #(= "src/app.clj" (second %)) files))
            original document/build-file]
        (doseq [[file-id _ file-hash] files]
          (state/mark-dirty!
           graph (reconcile/dirty-marker file-id file-hash :upsert 20)))
        (with-redefs [document/build-file
                      (fn [graph project lateon file-id]
                        (if (= failed-id file-id)
                          (throw (java.nio.charset.MalformedInputException. 1))
                          (original graph project lateon file-id)))]
          (let [result (reconcile/reconcile! graph project settings 30)
                dirty (state/dirty-records graph reconcile/provider)]
            (is (= 1 (:deferred result)))
            (is (= 1 (:queued-upserts result)))
            (is (= :semantic-file-failed
                   (get-in result [:diagnostics 0 :kind])))
            (is (= "src/app.clj"
                   (get-in result [:diagnostics 0 :file])))
            (is (= [failed-id]
                   (mapv :semantic.dirty/file-id dirty)))))))))
