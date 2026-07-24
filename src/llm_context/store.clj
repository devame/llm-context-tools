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
  (replace-file-and-mark! [store file entities dirty-markers]
    "Atomically replace one file and assert semantic dirty markers.")
  (delete-file! [store file-id]
    "Atomically retract a file and every graph fact connected to its symbols.")
  (delete-file-and-mark! [store file-id dirty-markers]
    "Atomically retract one file and assert semantic dirty markers.")
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
  (let [entities (->> entities
                      (map schema/with-symbol-search-text)
                      dependency-order
                      vec)
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

(defn- backfill-symbol-search-text!
  "Populate the derived full-text attribute once for databases created before
  it existed. Missing-attribute detection makes interrupted batches resumable;
  a version marker keeps normal database opens constant-time."
  [connection]
  (let [db (d/db connection)
        current-version
        (d/q '[:find ?version .
               :where [?meta :llm-context/meta-key "search-index"]
                      [?meta :llm-context/search-schema-version ?version]]
             db)]
    (when-not (= 1 current-version)
      (let [symbols (d/q '[:find ?symbol ?name ?qualified
                           :where [?symbol :symbol/name ?name]
                                  [?symbol :symbol/qualified-name ?qualified]]
                         db)
            indexed (set (d/q '[:find [?symbol ...]
                                :where [?symbol :symbol/search-text _]]
                              db))
            signatures (into {} (d/q '[:find ?symbol ?signature
                                        :where [?symbol :symbol/signature ?signature]]
                                      db))
            docs (into {} (d/q '[:find ?symbol ?doc
                                  :where [?symbol :symbol/doc ?doc]]
                                db))
            missing (keep (fn [[symbol name qualified]]
                            (when-not (contains? indexed symbol)
                              {:db/id symbol
                               :symbol/search-text
                               (schema/symbol-search-text
                                {:symbol/name name
                                 :symbol/qualified-name qualified
                                 :symbol/signature (get signatures symbol)
                                 :symbol/doc (get docs symbol)})}))
                          symbols)]
        (doseq [batch (partition-all 100 missing)]
          (d/transact! connection (vec batch)))
        (d/transact! connection
                     [{:llm-context/meta-key "search-index"
                       :llm-context/search-schema-version 1}])))))

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

(defn- dirty-marker-tx [db markers]
  (mapcat
   (fn [marker]
     (let [id (:semantic.dirty/id marker)
           existing (when id (existing-eid db [:semantic.dirty/id id]))
           old-hash (when existing
                      (d/q '[:find ?hash .
                             :in $ ?entity
                             :where [?entity :semantic.dirty/file-hash ?hash]]
                           db existing))]
       (cond-> []
         (and old-hash (nil? (:semantic.dirty/file-hash marker)))
         (conj [:db/retract existing :semantic.dirty/file-hash old-hash])

         true
         (conj marker))))
   markers))

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

  (replace-file-and-mark! [_ file entities dirty-markers]
    (schema/validate-entity! file)
    (doseq [entity entities]
      (schema/validate-entity! entity))
    (let [db (d/db connection)
          retractions (retract-owned-tx db (:file/id file))
          all-entities (vec (cons file entities))
          force-new (set (map entity-identity entities))
          assertions (entities->tx db all-entities force-new)
          markers (dirty-marker-tx db dirty-markers)]
      (d/transact! connection
                   (vec (concat retractions assertions markers)))))

  (delete-file! [_ file-id]
    (let [db (d/db connection)
          owned (retract-owned-tx db file-id)
          tx (cond-> owned
               (file-eid db file-id)
               (conj [:db/retractEntity (file-eid db file-id)]))]
      (when (seq tx)
        (d/transact! connection tx))))

  (delete-file-and-mark! [_ file-id dirty-markers]
    (let [db (d/db connection)
          owned (retract-owned-tx db file-id)
          graph-tx (cond-> owned
                     (file-eid db file-id)
                     (conj [:db/retractEntity (file-eid db file-id)]))
          marker-tx (dirty-marker-tx db dirty-markers)
          tx (vec (concat graph-tx marker-tx))]
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
    (let [connection (d/get-conn (str path) schema/datalevin-schema)]
      (try
        (backfill-symbol-search-text! connection)
        (->DatalevinStore connection path)
        (catch Throwable error
          (d/close connection)
          (throw error))))))

(defmacro with-store [[binding project config] & body]
  `(with-open [~binding (open ~project ~config)]
     ~@body))
