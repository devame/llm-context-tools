(ns llm-context.provider-qualification
  "Isolated, executable qualification checks for optional provider features.
  These probes never open a project database and do not enable a production
  feature by themselves."
  (:require [clojure.pprint :as pprint]
            [datalevin.core :as d]
            [llm-context.semantic.artifacts :as artifacts])
  (:import [java.nio.file FileVisitOption Files LinkOption]
           [java.util Properties]))

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

(defn qualification-report []
  {:qualification/version 1
   :datalevin (qualify-datalevin)
   :next-plaid
   {:provider :next-plaid
    :version artifacts/next-plaid-version
    :status :not-run
    :reason (str "NextPlaid crash qualification requires the packaged binary, "
                 "verified model artifacts, and a child-process kill test; "
                 "it is deliberately not inferred from API availability.")}})

(defn -main [& _]
  (let [report (qualification-report)]
    (pprint/pprint report)
    (when-not (= :supported (get-in report [:datalevin :status]))
      (System/exit 1))))
