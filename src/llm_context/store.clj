(ns llm-context.store
  (:require [clojure.string :as str]
            [datalevin.core :as d]
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
  (prune-orphan-topics! [store]
    "Retract project-global topics that no exact edge references.")
  (query [store query-form inputs] "Run a Datalog query against the store."))

(defn- entity-identity [entity]
  (cond
    (:file/id entity) [:file/id (:file/id entity)]
    (:symbol/id entity) [:symbol/id (:symbol/id entity)]
    (:topic/id entity) [:topic/id (:topic/id entity)]
    (:edge/id entity) [:edge/id (:edge/id entity)]
    (:reference/id entity) [:reference/id (:reference/id entity)]
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
               :entity.type/topic 2
               :entity.type/edge 3
               :entity.type/reference 3
               :entity.type/effect 4
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

(defn- edge-target-identities [entities]
  (set (keep (fn [entity]
               (when (= :entity.type/edge (:entity/type entity))
                 (:edge/to entity)))
             entities)))

(defn- asserted-target-identities [entities]
  (set (concat (keep :symbol/id entities)
               (keep :topic/id entities))))

(defn- existing-target-identities [db targets]
  (if-not (seq targets)
    #{}
    (set
     (concat
      (d/q '[:find [?id ...]
             :in $ ?targets
             :where
             [?entity :symbol/id ?id]
             [(contains? ?targets ?id)]]
           db targets)
      (d/q '[:find [?id ...]
             :in $ ?targets
             :where
             [?entity :topic/id ?id]
             [(contains? ?targets ?id)]]
           db targets)))))

(defn- validate-edge-targets! [db entities replace-all?]
  (let [targets (edge-target-identities entities)
        asserted (asserted-target-identities entities)
        available (if replace-all?
                    asserted
                    (into asserted (existing-target-identities db targets)))
        missing (vec (sort (remove available targets)))]
    (when (seq missing)
      (throw (ex-info "Exact graph edge targets do not exist"
                      {:missing-targets missing})))))

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
                      (map schema/with-derived-attributes)
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
              (:edge/to entity)
              (update :edge/to
                      #(ref (if (str/starts-with? % "topic:")
                              :topic/id :symbol/id) %))
              (:reference/symbol entity)
              (update :reference/symbol #(ref :symbol/id %))
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

(def graph-metadata-key "analysis-format")

(defn graph-metadata
  "Return the persisted analyzer/graph compatibility contract, or nil before
  the first format-aware full analysis."
  [store]
  (d/q '[:find (pull ?meta [*]) .
         :in $ ?key
         :where [?meta :llm-context/meta-key ?key]]
       (database store) graph-metadata-key))

(defn graph-state
  "Classify generated graph state without preventing a full rebuild from
  opening an older database."
  [store]
  (let [db (database store)
        files? (boolean
                (d/q '[:find ?file .
                       :where [?file :file/id _]]
                     db))
        metadata (graph-metadata store)]
    (cond
      (not files?) :empty
      (= schema/graph-format-version
         (:llm-context/graph-format metadata)) :ready
      :else :incompatible)))

(defn assert-query-compatible!
  "Refuse graph reads when derived state predates the current evidence
  contract. Full analysis intentionally does not call this function."
  [store]
  (when (= :incompatible (graph-state store))
    (throw
     (ex-info
      (str "This project graph uses an incompatible format. "
           "Run `llm-context analyze --full` from the project root to rebuild it.")
      {:exit-code 2
       :type :graph/rebuild-required
       :required-format schema/graph-format-version
       :metadata (graph-metadata store)})))
  store)

(defn write-graph-metadata!
  [store {:keys [analyzer-name analyzer-version janet-catalog-version
                 semantic-document-version semantic-index-name]}]
  (d/transact!
   (:connection store)
   [{:llm-context/meta-key graph-metadata-key
     :llm-context/graph-format schema/graph-format-version
     :llm-context/analyzer-name analyzer-name
     :llm-context/analyzer-version analyzer-version
     :llm-context/janet-catalog-version janet-catalog-version
     :llm-context/semantic-document-version semantic-document-version
     :llm-context/semantic-index-name semantic-index-name}]))

(defn reset-semantic-state!
  "Remove queue, indexed-record, dirty-marker, and watermark entities before
  rebuilding a versioned semantic index. Canonical graph facts are untouched."
  [store]
  (let [semantic-eids
        (->> (d/q '[:find ?entity ?attribute
                    :where [?entity ?attribute _]]
                  (database store))
             (keep (fn [[entity attribute]]
                     (when (str/starts-with? (or (namespace attribute) "")
                                             "semantic")
                       entity)))
             distinct
             vec)]
    (doseq [batch (partition-all 100 semantic-eids)]
      (d/transact! (:connection store)
                   (mapv (fn [eid] [:db/retractEntity eid]) batch)))
    (count semantic-eids)))

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
        references (if (seq symbols)
                     (d/q '[:find [?reference ...]
                            :in $ [?symbol ...]
                            :where
                            [?reference :reference/symbol ?symbol]]
                          db symbols)
                     [])
        owned (set (concat from-edges references effects symbols))]
    {:owned owned
     :inbound (remove #(contains? owned (first %)) inbound)}))

(defn- file-eid [db file-id]
  (d/q '[:find ?file .
         :in $ ?file-id
         :where [?file :file/id ?file-id]]
       db file-id))

(defn- retract-owned-tx
  ([db file-id] (retract-owned-tx db file-id #{}))
  ([db file-id preserve-symbol-ids]
   (let [{:keys [owned inbound]} (file-retraction-plan db file-id)
         preserved-eids
         (if (seq preserve-symbol-ids)
           (set (d/q '[:find [?symbol ...]
                       :in $ [?id ...]
                       :where [?symbol :symbol/id ?id]]
                     db (vec preserve-symbol-ids)))
           #{})
         owned (remove preserved-eids owned)
         inbound (remove (comp preserved-eids second) inbound)]
     (into (mapv (fn [eid] [:db/retractEntity eid]) owned)
           (map (fn [[edge _target]] [:db/retractEntity edge]) inbound)))))

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
    (validate-edge-targets! (d/db connection) entities false)
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
    (validate-edge-targets! (d/db connection) entities true)
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
    (validate-edge-targets! (d/db connection) entities false)
    (let [db (d/db connection)
          preserved (set (keep :symbol/id entities))
          retractions (retract-owned-tx db (:file/id file) preserved)
          all-entities (vec (cons file entities))
          force-new (set (remove #(and (= :symbol/id (first %))
                                       (contains? preserved (second %)))
                                 (map entity-identity entities)))
          assertions (entities->tx db all-entities force-new)]
      (d/transact! connection (into retractions assertions))))

  (replace-file-and-mark! [_ file entities dirty-markers]
    (schema/validate-entity! file)
    (doseq [entity entities]
      (schema/validate-entity! entity))
    (validate-edge-targets! (d/db connection) entities false)
    (let [db (d/db connection)
          preserved (set (keep :symbol/id entities))
          retractions (retract-owned-tx db (:file/id file) preserved)
          all-entities (vec (cons file entities))
          force-new (set (remove #(and (= :symbol/id (first %))
                                       (contains? preserved (second %)))
                                 (map entity-identity entities)))
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

  (prune-orphan-topics! [_]
    (let [db (d/db connection)
          topics (set (d/q '[:find [?topic ...]
                             :where [?topic :topic/id _]]
                           db))
          referenced
          (set (d/q '[:find [?topic ...]
                      :where
                      [?topic :topic/id _]
                      [?edge :edge/to ?topic]]
                    db))
          topics (remove referenced topics)]
      (when (seq topics)
        (d/transact! connection
                     (mapv (fn [topic] [:db/retractEntity topic]) topics)))
      (count topics)))

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
