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

(def ^:private canonical-identity-attributes
  [:file/id :symbol/id :topic/id :edge/id :reference/id :effect/id])

(defn- existing-identity-eids
  "Resolve a set of canonical identities with at most one indexed query per
  identity attribute. The returned map is suitable for transaction planning."
  [db identities]
  (reduce
   (fn [result [attribute identities]]
     (let [values (mapv second identities)]
       (if-not (seq values)
         result
         (into result
               (map (fn [[value eid]] [[attribute value] eid]))
               (d/q '[:find ?value ?entity
                      :in $ ?attribute [?value ...]
                      :where [?entity ?attribute ?value]]
                    db attribute values)))))
   {}
   (group-by first identities)))

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

(defn- existing-identities [db attribute ids]
  (if-not (seq ids)
    #{}
    (set
     (d/q '[:find [?id ...]
            :in $ ?attribute ?ids
            :where
            [?entity ?attribute ?id]
            [(contains? ?ids ?id)]]
          db attribute ids))))

(defn- relationship-requirements [entities]
  {:files (set (keep (fn [entity]
                       (when (= :entity.type/symbol (:entity/type entity))
                         (:symbol/file entity)))
                     entities))
   :symbols
   (set
    (keep
     identity
     (mapcat
      (fn [entity]
        (case (:entity/type entity)
          :entity.type/edge [(:edge/from entity)
                             (when (str/starts-with? (:edge/to entity) "symbol:")
                               (:edge/to entity))]
          :entity.type/reference [(:reference/symbol entity)]
          :entity.type/effect [(:effect/symbol entity)]
          []))
      entities)))
   :topics
   (set (keep (fn [entity]
                (when (and (= :entity.type/edge (:entity/type entity))
                           (str/starts-with? (:edge/to entity) "topic:"))
                  (:edge/to entity)))
              entities))})

(defn- asserted-identities [entities attribute]
  (set (keep attribute entities)))

(defn- validate-relationships!
  ([db entities replace-all?]
   (validate-relationships! db entities replace-all? #{}))
  ([db entities replace-all? unavailable-symbols]
   (let [{:keys [files symbols topics]} (relationship-requirements entities)
         asserted-files (asserted-identities entities :file/id)
         asserted-symbols (asserted-identities entities :symbol/id)
         asserted-topics (asserted-identities entities :topic/id)
         available-files
         (if replace-all?
           asserted-files
           (into asserted-files (existing-identities db :file/id files)))
         available-symbols
         (if replace-all?
           asserted-symbols
           (into asserted-symbols
                 (remove unavailable-symbols
                         (existing-identities db :symbol/id symbols))))
         available-topics
         (if replace-all?
           asserted-topics
           (into asserted-topics (existing-identities db :topic/id topics)))
         missing {:symbol-files (vec (sort (remove available-files files)))
                  :edge-or-fact-symbols
                  (vec (sort (remove available-symbols symbols)))
                  :edge-topics (vec (sort (remove available-topics topics)))}
         missing (into {} (filter (comp seq val)) missing)]
     (when (seq missing)
       (throw
        (ex-info "Canonical graph relationships refer to missing owners or targets"
                 {:missing-relationships missing}))))))

(defn- validate-file-ownership!
  "A file-scoped replacement may point to project-global targets, but every
  asserted symbol and every source-owned fact must belong to the file being
  replaced. Otherwise later file deletion could not retract facts reliably."
  [file entities]
  (let [file-id (:file/id file)
        symbol-ids (set (keep :symbol/id entities))
        foreign-symbols
        (->> entities
             (keep (fn [entity]
                     (when (and (= :entity.type/symbol (:entity/type entity))
                                (not= file-id (:symbol/file entity)))
                       (:symbol/id entity))))
             sort vec)
        foreign-facts
        (->> entities
             (keep
              (fn [entity]
                (let [owner
                      (case (:entity/type entity)
                        :entity.type/edge (:edge/from entity)
                        :entity.type/reference (:reference/symbol entity)
                        :entity.type/effect (:effect/symbol entity)
                        nil)]
                  (when (and owner (not (contains? symbol-ids owner)))
                    (entity-identity entity)))))
             (sort-by pr-str)
             vec)]
    (when (or (seq foreign-symbols) (seq foreign-facts))
      (throw
       (ex-info "File replacement contains facts owned by another file"
                {:file-id file-id
                 :foreign-symbols foreign-symbols
                 :foreign-facts foreign-facts})))))

(defn validate-replacement!
  "Preflight a complete canonical snapshot without changing Datalevin. Full
  analysis calls this before clearing versioned semantic operational state."
  [store entities]
  (doseq [entity entities]
    (schema/validate-entity! entity))
  (validate-identities! entities)
  (validate-relationships! (database store) entities true)
  entities)

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
  ([db entities force-new]
   (let [identities (remove force-new (map entity-identity entities))]
     (entities->tx db entities force-new
                   (existing-identity-eids db identities))))
  ([db entities force-new existing-eids]
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
                                      (get existing-eids ident))
                                    (- (inc index)))])
                       identities))
         ref (fn [attribute value]
               (or (get db-ids [attribute value]) [attribute value]))]
     (mapv
      (fn [entity]
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
      entities))))

(defn- backfill-symbol-search-index!
  "Populate derived full-text and character-gram attributes for older
  databases. Missing-attribute detection makes interrupted batches resumable;
  a version marker keeps normal database opens constant-time."
  [connection]
  (let [db (d/db connection)
        current-version
        (d/q '[:find ?version .
               :where [?meta :llm-context/meta-key "search-index"]
                      [?meta :llm-context/search-schema-version ?version]]
             db)]
    (when (or (nil? current-version) (< (long current-version) 2))
      (let [symbols (d/q '[:find ?symbol ?name ?qualified
                           :where [?symbol :symbol/name ?name]
                                  [?symbol :symbol/qualified-name ?qualified]]
                         db)
            text-indexed (set (d/q '[:find [?symbol ...]
                                     :where [?symbol :symbol/search-text _]]
                                   db))
            grams-indexed (set (d/q '[:find [?symbol ...]
                                      :where [?symbol :symbol/search-grams _]]
                                    db))
            signatures (into {} (d/q '[:find ?symbol ?signature
                                        :where [?symbol :symbol/signature ?signature]]
                                      db))
            docs (into {} (d/q '[:find ?symbol ?doc
                                  :where [?symbol :symbol/doc ?doc]]
                                db))
            missing
            (keep
             (fn [[symbol name qualified]]
               (let [source {:symbol/name name
                             :symbol/qualified-name qualified
                             :symbol/signature (get signatures symbol)
                             :symbol/doc (get docs symbol)}
                     entity
                     (cond-> {:db/id symbol}
                       (not (contains? text-indexed symbol))
                       (assoc :symbol/search-text
                              (schema/symbol-search-text source))
                       (not (contains? grams-indexed symbol))
                       (assoc :symbol/search-grams
                              (schema/symbol-search-grams source)))]
                 (when (< 1 (count entity)) entity)))
             symbols)]
        (doseq [batch (partition-all 100 missing)]
          (d/transact! connection (vec batch)))
        (d/transact! connection
                     [{:llm-context/meta-key "search-index"
                       :llm-context/search-schema-version 2}])))))

(def graph-metadata-key "analysis-format")
(def ^:private graph-update-analyzer-name "update-in-progress")

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
      (= graph-update-analyzer-name
         (:llm-context/analyzer-name metadata)) :unavailable
      (not files?) :empty
      (= schema/graph-format-version
         (:llm-context/graph-format metadata)) :ready
      :else :incompatible)))

(defn assert-query-compatible!
  "Refuse graph reads when derived state predates the current evidence
  contract. Full analysis intentionally does not call this function."
  [store]
  (case (graph-state store)
    :incompatible
    (throw
     (ex-info
      (str "This project graph uses an incompatible format. "
           "Run `llm-context analyze --full` from the project root to rebuild it.")
      {:exit-code 2
       :type :graph/rebuild-required
       :required-format schema/graph-format-version
       :metadata (graph-metadata store)}))
    :unavailable
    (throw
     (ex-info
      (str "The previous full analysis did not finish activating its graph. "
           "Run `llm-context analyze --full` from the project root to recover.")
      {:exit-code 2
       :type :graph/update-incomplete
       :required-format schema/graph-format-version
       :metadata (graph-metadata store)}))
    nil)
  store)

(defn begin-full-replacement!
  "Persist an unavailable marker before the first mutation of a multi-
  transaction full replacement. A process interruption therefore leaves an
  explicit recovery state instead of advertising whatever batches happened
  to commit as a queryable graph."
  [store]
  (d/transact!
   (:connection store)
   [{:llm-context/meta-key graph-metadata-key
     :llm-context/graph-format schema/graph-format-version
     :llm-context/analyzer-name graph-update-analyzer-name}]))

(defn write-graph-metadata!
  [store {:keys [analyzer-name analyzer-version janet-catalog-version
                 analyzer-configuration-fingerprint
                 semantic-fingerprint-version semantic-document-version
                 semantic-index-name]}]
  (d/transact!
   (:connection store)
   [(cond-> {:llm-context/meta-key graph-metadata-key
             :llm-context/graph-format schema/graph-format-version
             :llm-context/analyzer-name analyzer-name
             :llm-context/analyzer-version analyzer-version
             :llm-context/janet-catalog-version janet-catalog-version
             :llm-context/semantic-document-version semantic-document-version
             :llm-context/semantic-index-name semantic-index-name}
      semantic-fingerprint-version
      (assoc :llm-context/semantic-fingerprint-version
             semantic-fingerprint-version)
      analyzer-configuration-fingerprint
      (assoc :llm-context/analyzer-configuration-fingerprint
             analyzer-configuration-fingerprint))]))

(defn reset-semantic-state!
  "Remove queue, indexed-record, dirty-marker, and watermark entities before
  rebuilding a versioned semantic index. Canonical graph facts are untouched."
  [store]
  (reduce
   (fn [removed identity-attribute]
     ;; Identity attributes are unique and indexed. Binding the attribute in
     ;; the query avoids materializing every graph entity/attribute pair merely
     ;; to discover the four semantic operational entity families.
     (let [eids (d/q '[:find [?entity ...]
                       :in $ ?identity-attribute
                       :where [?entity ?identity-attribute _]]
                     (database store) identity-attribute)]
       (doseq [batch (partition-all 100 eids)]
         (d/transact! (:connection store)
                      (mapv (fn [eid] [:db/retractEntity eid]) batch)))
       (+ removed (count eids))))
   0
   [:semantic.dirty/id :semantic.job/id
    :semantic.indexed/id :semantic.watermark/id]))

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
  (let [ids (vec (keep :semantic.dirty/id markers))
        existing
        (if (seq ids)
          (into {}
                (map (fn [entity]
                       [(:semantic.dirty/id entity) entity]))
                (let [eids (d/q '[:find [?entity ...]
                                  :in $ [?id ...]
                                  :where [?entity :semantic.dirty/id ?id]]
                                db ids)]
                  (if (seq eids) (d/pull-many db '[*] eids) [])))
          {})]
    (mapcat
     (fn [marker]
       (let [current (get existing (:semantic.dirty/id marker))
             old-hash (:semantic.dirty/file-hash current)]
         (cond-> []
           (and old-hash (nil? (:semantic.dirty/file-hash marker)))
           (conj [:db/retract (:db/id current)
                  :semantic.dirty/file-hash old-hash])

           true
           (conj marker))))
     markers)))

(defn- attributes-by-eid [db eids]
  (if-not (seq eids)
    {}
    (reduce
     (fn [result [eid attribute value]]
       (update result eid (fnil conj []) [attribute value]))
     {}
     (d/q '[:find ?entity ?attribute ?value
            :in $ [?entity ...]
            :where [?entity ?attribute ?value]]
          db (vec eids)))))

(defn- stale-attribute-tx
  "Map upserts do not retract attributes omitted by a newer canonical entity.
  Explicitly remove those old values while retaining the entity's stable eid."
  [entities existing-eids attributes]
  (mapcat
   (fn [entity]
     (when-let [eid (get existing-eids (entity-identity entity))]
       (let [desired (schema/with-derived-attributes entity)
             desired-attributes (set (keys desired))]
         (keep (fn [[attribute value]]
                 (when (or (not (contains? desired-attributes attribute))
                           (and (= :symbol/search-grams attribute)
                                (not (contains?
                                      (:symbol/search-grams desired)
                                      value))))
                   [:db/retract eid attribute value]))
               (get attributes eid)))))
   entities))

(def ^:private identity-pull
  (into [:db/id] canonical-identity-attributes))

(defn- replacement-snapshot [db file-id entities]
  (let [{:keys [owned inbound]} (file-retraction-plan db file-id)
        identities (mapv entity-identity entities)
        existing-eids (existing-identity-eids db identities)
        relevant-eids (set (concat owned (vals existing-eids)))
        identity-by-eid
        (if-not (seq owned)
          {}
          (into {}
                (keep (fn [entity]
                        (when-let [identity (entity-identity entity)]
                          [(:db/id entity) identity])))
                (d/pull-many db identity-pull (vec owned))))]
    {:owned owned
     :inbound inbound
     :existing-eids existing-eids
     :attributes (attributes-by-eid db relevant-eids)
     :identity-by-eid identity-by-eid}))

(defn- replacement-retraction-tx
  "Retract only file-owned identities absent from the proposed replacement.
  Retained identities are updated in place so Datalevin never sees
  retractEntity and an upsert of the same unique identity in one transaction."
  [{:keys [inbound identity-by-eid]} asserted-identities]
  (let [removed-symbol-eids
        (set
         (keep (fn [[eid identity]]
                 (when (and (= :symbol/id (first identity))
                            (not (contains? asserted-identities identity)))
                   eid))
               identity-by-eid))
        removed-owned
        (keep (fn [[eid identity]]
                (when-not (contains? asserted-identities identity)
                  eid))
              identity-by-eid)
        removed-inbound
        (keep (fn [[edge target]]
                (when (contains? removed-symbol-eids target)
                  edge))
              inbound)]
    (mapv (fn [eid] [:db/retractEntity eid])
          (distinct (concat removed-owned removed-inbound)))))

(defn- file-replacement-tx
  [db file entities]
  (let [all-entities (vec (cons file entities))
        asserted-identities (set (map entity-identity all-entities))
        snapshot (replacement-snapshot db (:file/id file) all-entities)
        retractions
        (replacement-retraction-tx snapshot asserted-identities)
        stale-attributes
        (stale-attribute-tx all-entities (:existing-eids snapshot)
                            (:attributes snapshot))
        assertions (entities->tx db all-entities #{}
                                 (:existing-eids snapshot))]
    (vec (concat retractions stale-attributes assertions))))

(defrecord DatalevinStore [connection path]
  GraphStore
  (database [_] (d/db connection))

  (transact! [_ entities]
    (doseq [entity entities]
      (schema/validate-entity! entity))
    (validate-identities! entities)
    (validate-relationships! (d/db connection) entities false)
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
    (validate-identities! entities)
    (validate-relationships! (d/db connection) entities true)
    (let [ordered (vec (dependency-order entities))
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
    (validate-file-ownership! file entities)
    (let [db (d/db connection)
          all-entities (vec (cons file entities))
          old-symbol-ids
          (set
           (d/q '[:find [?id ...]
                  :in $ ?file-id
                  :where
                  [?file :file/id ?file-id]
                  [?symbol :symbol/file ?file]
                  [?symbol :symbol/id ?id]]
                db (:file/id file)))
          _ (validate-identities! all-entities)
          _ (validate-relationships! db all-entities false old-symbol-ids)
          tx (file-replacement-tx db file entities)]
      (d/transact! connection tx)))

  (replace-file-and-mark! [_ file entities dirty-markers]
    (schema/validate-entity! file)
    (doseq [entity entities]
      (schema/validate-entity! entity))
    (validate-file-ownership! file entities)
    (let [db (d/db connection)
          all-entities (vec (cons file entities))
          old-symbol-ids
          (set
           (d/q '[:find [?id ...]
                  :in $ ?file-id
                  :where
                  [?file :file/id ?file-id]
                  [?symbol :symbol/file ?file]
                  [?symbol :symbol/id ?id]]
                db (:file/id file)))
          _ (validate-identities! all-entities)
          _ (validate-relationships! db all-entities false old-symbol-ids)
          graph-tx (file-replacement-tx db file entities)
          markers (dirty-marker-tx db dirty-markers)]
      (d/transact! connection
                   (vec (concat graph-tx markers)))))

  (delete-file! [_ file-id]
    (let [db (d/db connection)
          owned (retract-owned-tx db file-id)
          file (file-eid db file-id)
          tx (cond-> owned
               file (conj [:db/retractEntity file]))]
      (when (seq tx)
        (d/transact! connection tx))))

  (delete-file-and-mark! [_ file-id dirty-markers]
    (let [db (d/db connection)
          owned (retract-owned-tx db file-id)
          file (file-eid db file-id)
          graph-tx (cond-> owned
                     file (conj [:db/retractEntity file]))
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
        (backfill-symbol-search-index! connection)
        (->DatalevinStore connection path)
        (catch Throwable error
          (d/close connection)
          (throw error))))))

(defmacro with-store [[binding project config] & body]
  `(with-open [~binding (open ~project ~config)]
     ~@body))
