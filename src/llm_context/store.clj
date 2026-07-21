(ns llm-context.store
  (:require [datalevin.core :as d]
            [llm-context.model.schema :as schema])
  (:import [java.io Closeable]
           [java.nio.file Files LinkOption Path]))

(defprotocol GraphStore
  (database [store] "Return an immutable database value for querying.")
  (transact! [store entities] "Validate and transact canonical graph entities.")
  (replace-file! [store file entities]
    "Atomically replace one file and every graph fact owned by it.")
  (delete-file! [store file-id]
    "Atomically retract a file and every graph fact connected to its symbols.")
  (query [store query-form inputs] "Run a Datalog query against the store."))

(defn- lookup-ref [attribute value]
  [attribute value])

(defn- entity->tx [entity]
  (cond-> entity
    (:symbol/file entity)
    (update :symbol/file #(lookup-ref :file/id %))

    (:edge/from entity)
    (update :edge/from #(lookup-ref :symbol/id %))

    (:edge/to entity)
    (update :edge/to #(lookup-ref :symbol/id %))

    (:effect/symbol entity)
    (update :effect/symbol #(lookup-ref :symbol/id %))))

(defn- file-owned-entity-ids [db file-id]
  (let [symbols (d/q '[:find [?symbol ...]
                       :in $ ?file-id
                       :where
                       [?file :file/id ?file-id]
                       [?symbol :symbol/file ?file]]
                     db file-id)
        from-edges (if (seq symbols)
                     (d/q '[:find [?edge ...]
                            :in $ [?symbol ...]
                            :where [?edge :edge/from ?symbol]]
                          db symbols)
                     [])
        to-edges (if (seq symbols)
                   (d/q '[:find [?edge ...]
                          :in $ [?symbol ...]
                          :where [?edge :edge/to ?symbol]]
                        db symbols)
                   [])
        effects (if (seq symbols)
                  (d/q '[:find [?effect ...]
                         :in $ [?symbol ...]
                         :where [?effect :effect/symbol ?symbol]]
                       db symbols)
                  [])]
    (distinct (concat from-edges to-edges effects symbols))))

(defn- file-eid [db file-id]
  (d/q '[:find ?file .
         :in $ ?file-id
         :where [?file :file/id ?file-id]]
       db file-id))

(defn- retract-owned-tx [db file-id]
  (mapv (fn [eid] [:db/retractEntity eid])
        (file-owned-entity-ids db file-id)))

(defrecord DatalevinStore [connection path]
  GraphStore
  (database [_] (d/db connection))

  (transact! [_ entities]
    (doseq [entity entities]
      (schema/validate-entity! entity))
    (when (seq entities)
      (d/transact! connection (mapv entity->tx entities))))

  (replace-file! [_ file entities]
    (schema/validate-entity! file)
    (doseq [entity entities]
      (schema/validate-entity! entity))
    (let [retractions (retract-owned-tx (d/db connection) (:file/id file))
          assertions (mapv entity->tx (cons file entities))]
      (d/transact! connection (into retractions assertions))))

  (delete-file! [_ file-id]
    (let [db (d/db connection)
          owned (retract-owned-tx db file-id)
          tx (cond-> owned
               (file-eid db file-id)
               (conj [:db/retractEntity (file-eid db file-id)]))]
      (when (seq tx)
        (d/transact! connection tx))))

  (query [_ query-form inputs]
    (apply d/q query-form (d/db connection) inputs))

  Closeable
  (close [_]
    (d/close connection)))

(defn open
  "Open the embedded Datalevin database configured for a project."
  [{:keys [^Path root]} config]
  (let [configured (get-in config [:store :path])
        path (.normalize (.resolve root configured))]
    (Files/createDirectories path (make-array java.nio.file.attribute.FileAttribute 0))
    (->DatalevinStore (d/get-conn (str path) schema/datalevin-schema) path)))

(defmacro with-store [[binding project config] & body]
  `(with-open [~binding (open ~project ~config)]
     ~@body))
