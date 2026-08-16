(ns llm-context.provider-qualification
  "Isolated, executable qualification checks for optional provider features.
  These probes never open a project database and do not enable a production
  feature by themselves."
  (:require [clojure.pprint :as pprint]
            [datalevin.core :as d]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.artifacts :as artifacts]
            [llm-context.semantic.index :as index]
            [llm-context.semantic.runtime :as runtime])
  (:import [java.nio.file FileVisitOption Files LinkOption]
           [java.util Properties]
           [java.util.concurrent TimeUnit]))

(def ^:private qualification-schema
  {:qualification/id {:db/valueType :db.type/string
                      :db/unique :db.unique/identity}
   :qualification/value {:db/valueType :db.type/long}})

(def ^:private transaction-report-keys
  #{:db-before :db-after :tx-data :tempids :tx-meta})

(defn- library-version [resource]
  (when-let [stream (.getResourceAsStream
                     (.getContextClassLoader (Thread/currentThread)) resource)]
    (with-open [stream stream]
      (let [properties (Properties.)]
        (.load properties stream)
        (.getProperty properties "version")))))

(defn- delete-tree! [path]
  (when (Files/exists path (make-array LinkOption 0))
    (with-open [paths (Files/walk path (make-array FileVisitOption 0))]
      (doseq [entry (reverse (vec (iterator-seq (.iterator paths))))]
        (Files/deleteIfExists entry)))))

(defn- report-valid? [report expected-meta]
  (and (= transaction-report-keys
          (set (filter transaction-report-keys (keys report))))
       (= expected-meta (:tx-meta report))
       (seq (:tx-data report))
       (:db-before report)
       (:db-after report)))

(defn qualify-datalevin
  "Prove the pinned Datalevin transaction-report, async-commit, and compact-copy
  contracts in an isolated temporary database. Returns data; never exits."
  []
  (let [root (Files/createTempDirectory
              "llm-context-datalevin-qualification-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source (.resolve root "source")
        copied (.resolve root "compact-copy")
        version (library-version
                 "META-INF/maven/org.datalevin/datalevin-embedded/pom.properties")]
    (try
      (let [reports
            (let [connection (d/get-conn (str source) qualification-schema)]
              (try
                (let [sync-meta {:qualification/probe :sync}
                      async-meta {:qualification/probe :async}
                      sync-report
                      (d/transact! connection
                                   [{:qualification/id "sync"
                                     :qualification/value 1}]
                                   sync-meta)
                      async-report
                      @(d/transact-async connection
                                         [{:qualification/id "async"
                                           :qualification/value 2}]
                                         async-meta)]
                  (d/copy (d/db connection) (str copied) true)
                  {:sync [sync-report sync-meta]
                   :async [async-report async-meta]})
                (finally
                  (d/close connection))))
            copied-ids
            (let [connection (d/get-conn (str copied) qualification-schema)]
              (try
                (set (d/q '[:find [?id ...]
                            :where [_ :qualification/id ?id]]
                          (d/db connection)))
                (finally
                  (d/close connection))))
            capabilities
            {:transaction-report
             (every? (fn [[report metadata]]
                       (boolean (report-valid? report metadata)))
                     (vals reports))
             :transact-async
             (boolean (report-valid? (first (:async reports))
                                     (second (:async reports))))
             :compact-copy (= #{"sync" "async"} copied-ids)}]
        {:provider :datalevin
         :artifact "org.datalevin/datalevin-embedded"
         :version version
         :status (if (every? true? (vals capabilities))
                   :supported :failed)
         :capabilities capabilities})
      (catch Throwable error
        {:provider :datalevin
         :artifact "org.datalevin/datalevin-embedded"
         :version version
         :status :failed
         :error-class (.getName (class error))
         :error (.getMessage error)})
      (finally
        (delete-tree! root)))))

(defn- await-value
  [timeout-ms f predicate]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [last-value nil]
      (let [value (f)]
        (cond
          (predicate value) value
          (>= (System/currentTimeMillis) deadline) last-value
          :else (do (Thread/sleep 250) (recur value)))))))

(defn- qualification-document [suffix]
  {:id (str "qualification:chunk:" suffix)
   :symbol-id (str "qualification:symbol:" suffix)
   :file-id "qualification:file"
   :document-hash (str "qualification:hash:" suffix)
   :model-revision artifacts/model-revision
   :document-version 4
   :chunk-index 0
   :chunk-count 1
   :text (str "Provider qualification document " suffix
              " for restart consistency and semantic visibility.")})

(defn- visible-for [client documents]
  (index/indexed-documents client (mapv :symbol-id documents)))

(defn- exact-visible? [visible documents]
  (let [expected (set (map (juxt :id :symbol-id :document-hash) documents))
        actual (set (map (juxt :id :symbol-id :document-hash) visible))]
    (and (= (count documents) (count visible)) (= expected actual))))

(defn- start-next-plaid! [project settings]
  (let [result (runtime/start! project {:semantic {:lateon-code settings}})]
    (when-not (= :ready (:status result))
      (throw (ex-info "Pinned NextPlaid runtime is unavailable" result)))
    (index/ensure-index! (:client result))
    result))

(defn qualify-next-plaid
  "Exercise the real pinned binary and verified model in an isolated project.
  The probe kills the provider after an accepted asynchronous update, restarts
  it, and proves that application reconciliation restores one exact copy. It
  also measures whether direct provider re-submission is itself idempotent."
  []
  (let [root (Files/createTempDirectory
              "llm-context-next-plaid-qualification-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        base-settings (get-in (config/defaults) [:semantic :lateon-code])
        model-path (runtime/model-path project base-settings)
        executable (runtime/find-executable
                    (first (:next-plaid-command base-settings)))
        settings (assoc base-settings
                        :accelerator :cpu
                        :quantization :int8
                        :encoding-sessions 1
                        :encoding-batch-size 1
                        :model-document-length 256
                        :index-name "llm-context-provider-qualification-v1")
        current-runtime (atom nil)
        sentinel [(qualification-document "sentinel")]
        interrupted (mapv #(qualification-document (str "crash-" %))
                          (range 32))]
    (try
      (when-not executable
        (throw (ex-info "Pinned NextPlaid executable was not found" {})))
      (let [verification (artifacts/verify-model model-path)]
        (when (or (seq (:missing verification))
                  (seq (:mismatched verification)))
          (throw (ex-info "Pinned LateOn model failed verification"
                          verification))))
      (let [first-runtime (start-next-plaid! project settings)
            _ (reset! current-runtime first-runtime)
            first-client (:client first-runtime)]
        (index/add-documents! first-client sentinel)
        (when-not (exact-visible?
                   (await-value 30000 #(visible-for first-client sentinel)
                                #(exact-visible? % sentinel))
                   sentinel)
          (throw (ex-info "NextPlaid sentinel did not become visible" {})))
        ;; Mutation responses are asynchronous. Kill immediately after the
        ;; provider accepts this update to exercise its restart boundary.
        (index/add-documents! first-client interrupted)
        (.destroyForcibly ^Process (:process first-runtime))
        (.waitFor ^Process (:process first-runtime) 10 TimeUnit/SECONDS)
        (reset! current-runtime nil))
      (let [second-runtime (start-next-plaid! project settings)
            _ (reset! current-runtime second-runtime)
            client (:client second-runtime)
            visible-after-crash (visible-for client interrupted)
            sentinel-survived?
            (exact-visible? (visible-for client sentinel) sentinel)]
        ;; Reconciliation owns logical idempotency: remove every possibly
        ;; partial symbol, await absence, then submit the desired generation.
        (index/delete-symbols! client (mapv :symbol-id interrupted))
        (when-not (empty?
                   (await-value 30000 #(visible-for client interrupted) empty?))
          (throw (ex-info "NextPlaid reconciliation deletion did not settle" {})))
        (index/add-documents! client interrupted)
        (let [reconciled
              (await-value 120000 #(visible-for client interrupted)
                           #(exact-visible? % interrupted))]
          (when-not (exact-visible? reconciled interrupted)
            (throw (ex-info "NextPlaid reconciliation did not restore coverage"
                            {:visible (count reconciled)}))))
        ;; Measure the provider contract rather than assuming metadata IDs are
        ;; physical upsert keys.
        (index/add-documents! client interrupted)
        (let [after-resubmit
              (await-value 15000 #(visible-for client interrupted)
                           #(> (count %) (count interrupted)))
              direct-idempotent? (= (count interrupted)
                                    (count after-resubmit))]
          ;; Leave the isolated index in a known converged state and prove that
          ;; a second restart can open it with complete metadata.
          (index/delete-symbols! client (mapv :symbol-id interrupted))
          (await-value 30000 #(visible-for client interrupted) empty?)
          (index/add-documents! client interrupted)
          (await-value 120000 #(visible-for client interrupted)
                       #(exact-visible? % interrupted))
          (runtime/stop! second-runtime)
          (reset! current-runtime nil)
          (let [third-runtime (start-next-plaid! project settings)
                _ (reset! current-runtime third-runtime)
                final-visible (visible-for (:client third-runtime) interrupted)
                final-sentinel (visible-for (:client third-runtime) sentinel)]
            {:provider :next-plaid
             :version artifacts/next-plaid-version
             :binary (str executable)
             :binary-sha256 (artifacts/sha256 executable)
             :model (str model-path)
             :status
             (if (and sentinel-survived?
                      (exact-visible? final-visible interrupted)
                      (exact-visible? final-sentinel sentinel))
               :supported :failed)
             :capabilities
             {:accepted-update-survives-or-reconciles true
              :restart-opens-index true
              :sentinel-survives-restart sentinel-survived?
              :visible-immediately-after-crash (count visible-after-crash)
              :direct-resubmit-idempotent direct-idempotent?
              :delete-then-submit-converges
              (exact-visible? final-visible interrupted)}})))
      (catch Throwable error
        {:provider :next-plaid
         :version artifacts/next-plaid-version
         :binary (some-> executable str)
         :model (str model-path)
         :status :failed
         :error-class (.getName (class error))
         :error (.getMessage error)
         :data (ex-data error)
         :stack
         (mapv str (take 12 (.getStackTrace error)))})
      (finally
        (when-let [active @current-runtime]
          (runtime/stop! active))
        (delete-tree! root)))))

(defn qualification-report
  ([] (qualification-report {}))
  ([{:keys [next-plaid?]}]
   {:qualification/version 1
    :datalevin (qualify-datalevin)
    :next-plaid
    (if next-plaid?
      (qualify-next-plaid)
      {:provider :next-plaid
       :version artifacts/next-plaid-version
       :status :not-run
       :reason (str "Pass --next-plaid to exercise the packaged binary, "
                    "verified model, and child-process crash test.")})}))

(defn -main [& arguments]
  (let [unknown (remove #{"--next-plaid"} arguments)
        _ (when (seq unknown)
            (throw (ex-info "Unknown provider qualification option"
                            {:arguments (vec unknown)})))
        report (qualification-report
                {:next-plaid? (boolean (some #{"--next-plaid"} arguments))})]
    (pprint/pprint report)
    (when-not (and (= :supported (get-in report [:datalevin :status]))
                   (or (not (some #{"--next-plaid"} arguments))
                       (= :supported (get-in report [:next-plaid :status]))))
      (System/exit 1))))
