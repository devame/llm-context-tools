(ns llm-context.store
  (:require [datalevin.core :as d]
            [llm-context.model.schema :as schema])
  (:import [java.io Closeable]
           [java.nio.file Files LinkOption Path]))

(defprotocol GraphStore
  (database [store] "Return an immutable database value for querying.")
  (transact! [store entities] "Validate and transact canonical graph entities.")
  (replace-all! [store entities] [store entities options]
    "Replace the complete graph in bounded, dependency-ordered transactions.")
  (replace-file! [store file entities]
    "Atomically replace one file and every graph fact owned by it.")
  (delete-file! [store file-id]
    "Atomically retract a file and every graph fact connected to its symbols.")
  (reconcile-edges! [store decisions]
    "Update edge targets and resolution states after the symbol set changes.")
  (query [store query-form inputs] "Run a Datalog query against the store."))

(defn- entity-identity [entity]
  (cond
    (:file/id entity) [:file/id (:file/id entity)]
    (:symbol/id entity) [:symbol/id (:symbol/id entity)]
    (:edge/id entity) [:edge/id (:edge/id entity)]
    (:effect/id entity) [:effect/id (:effect/id entity)]))

(defn- existing-eid [db [attribute value]]
  (d/q '[:find ?entity .
         :in $ ?attribute ?value
         :where [?entity ?attribute ?value]]
       db attribute value))

(defn- dependency-order
  "Put every referenced entity before entities that point at it. Datalevin can
  resolve forward temp IDs, but doing that repeatedly in a large transaction
  is pathologically expensive. Stable sorting preserves source order within a
  dependency layer."
  [entities]
  (sort-by (fn [entity]
             (case (:entity/type entity)
               :entity.type/file 0
               :entity.type/symbol 1
               :entity.type/edge 2
               :entity.type/effect 3
               4))
           entities))

(defn- validate-identities! [entities]
  (let [identities (map entity-identity entities)
        duplicates (->> identities frequencies
                        (keep (fn [[identity count]]
                                (when (> count 1) identity)))
                        vec)]
    (when (seq duplicates)
      (throw (ex-info "Duplicate canonical entity identities in transaction"
                      {:duplicates duplicates})))))

(defn- transact-batches!
  [connection items batch-size tx-fn phase on-progress]
  (let [batches (vec (partition-all batch-size items))
        total (count items)]
    (loop [remaining batches
           completed 0]
      (when-let [batch (first remaining)]
        (d/transact! connection (vec (tx-fn batch)))
        (let [next-completed (+ completed (count batch))]
          (when on-progress
            (on-progress {:phase phase :completed next-completed :total total}))
          (recur (next remaining) next-completed))))))

(defn- entities->tx
  "Assign explicit entity/temp IDs so references within one transaction never
  create partial lookup-ref placeholders. Identities in force-new are recreated
  after retractEntity rather than reused in the same transaction."
  [db entities force-new]
  (let [entities (vec (dependency-order entities))
        _ (validate-identities! entities)
        identities (mapv entity-identity entities)
        db-ids (into {}
                     (map-indexed
                      (fn [index ident]
                        [ident (or (when-not (contains? force-new ident)
                                     (existing-eid db ident))
                                   (- (inc index)))])
                      identities))
        ref (fn [attribute value]
              (or (get db-ids [attribute value]) [attribute value]))]
    (mapv (fn [entity]
            (cond-> (assoc entity :db/id (get db-ids (entity-identity entity)))
              (:symbol/file entity) (update :symbol/file #(ref :file/id %))
              (:edge/from entity) (update :edge/from #(ref :symbol/id %))
              (:edge/to entity) (update :edge/to #(ref :symbol/id %))
              (:effect/symbol entity) (update :effect/symbol #(ref :symbol/id %))))
          entities)))

(defn- file-retraction-plan [db file-id]
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
        inbound (if (seq symbols)
                  (d/q '[:find ?edge ?symbol
                         :in $ [?symbol ...]
                         :where [?edge :edge/to ?symbol]]
                       db symbols)
                  #{})
        effects (if (seq symbols)
                  (d/q '[:find [?effect ...]
                         :in $ [?symbol ...]
                         :where [?effect :effect/symbol ?symbol]]
                       db symbols)
                  [])
        owned (set (concat from-edges effects symbols))]
    {:owned owned
     :inbound (remove #(contains? owned (first %)) inbound)}))

(defn- file-eid [db file-id]
  (d/q '[:find ?file .
         :in $ ?file-id
         :where [?file :file/id ?file-id]]
       db file-id))

(defn- retract-owned-tx [db file-id]
  (let [{:keys [owned inbound]} (file-retraction-plan db file-id)]
    (into (mapv (fn [eid] [:db/retractEntity eid]) owned)
          (mapcat (fn [[edge target]]
                    [[:db/retract edge :edge/to target]
                     {:db/id edge
                      :edge/resolution :resolution/unresolved
                      :edge/confidence 0.0}])
                  inbound))))

(defrecord DatalevinStore [connection path]
  GraphStore
  (database [_] (d/db connection))

  (transact! [_ entities]
    (doseq [entity entities]
      (schema/validate-entity! entity))
    (when (seq entities)
      (d/transact! connection (entities->tx (d/db connection) entities #{}))))

  (replace-all! [this entities]
    (replace-all! this entities {}))

  (replace-all! [_ entities {:keys [batch-size on-progress]
                             :or {batch-size 100}}]
    (when-not (pos-int? batch-size)
      (throw (ex-info "Full replacement batch size must be positive"
                      {:batch-size batch-size})))
    (doseq [entity entities]
      (schema/validate-entity! entity))
    (let [ordered (vec (dependency-order entities))
          _ (validate-identities! ordered)
          existing (vec (d/q '[:find [?entity ...]
                               :where [?entity :entity/type _]]
                             (d/db connection)))]
      (transact-batches! connection existing batch-size
                         #(map (fn [eid] [:db/retractEntity eid]) %)
                         :retract on-progress)
      (transact-batches! connection ordered batch-size
                         (fn [batch]
                           (entities->tx (d/db connection) batch
                                         (set (map entity-identity batch))))
                         :assert on-progress)))

  (replace-file! [_ file entities]
    (schema/validate-entity! file)
    (doseq [entity entities]
      (schema/validate-entity! entity))
    (let [db (d/db connection)
          retractions (retract-owned-tx db (:file/id file))
          all-entities (vec (cons file entities))
          force-new (set (map entity-identity entities))
          assertions (entities->tx db all-entities force-new)]
      (d/transact! connection (into retractions assertions))))

  (delete-file! [_ file-id]
    (let [db (d/db connection)
          owned (retract-owned-tx db file-id)
          tx (cond-> owned
               (file-eid db file-id)
               (conj [:db/retractEntity (file-eid db file-id)]))]
      (when (seq tx)
        (d/transact! connection tx))))

  (reconcile-edges! [_ decisions]
    (let [db (d/db connection)
          tx (mapcat
              (fn [{:keys [edge-id target-id resolution confidence]}]
                (when-let [edge-eid (d/q '[:find ?edge .
                                           :in $ ?id
                                           :where [?edge :edge/id ?id]]
                                         db edge-id)]
                  (let [current-target (d/q '[:find ?target .
                                              :in $ ?edge
                                              :where [?edge :edge/to ?target]]
                                            db edge-eid)
                        target-eid (when target-id
                                     (d/q '[:find ?target .
                                            :in $ ?id
                                            :where [?target :symbol/id ?id]]
                                          db target-id))]
                    (cond-> []
                      (and current-target (not= current-target target-eid))
                      (conj [:db/retract edge-eid :edge/to current-target])

                      true
                      (conj (cond-> {:db/id edge-eid
                                     :edge/resolution resolution
                                     :edge/confidence (double confidence)}
                              target-eid (assoc :edge/to target-eid)))))))
              decisions)]
      (when (seq tx)
        (d/transact! connection (vec tx)))))

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
