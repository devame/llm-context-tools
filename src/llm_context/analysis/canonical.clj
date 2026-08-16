(ns llm-context.analysis.canonical
  "Canonicalization and whole-snapshot integrity checks for analyzer output.

  Canonicalization is intentionally upstream of persistence: Datalevin unique
  attributes must never decide which of two conflicting analyzer observations
  wins."
  (:require [clojure.string :as str]
            [llm-context.model.schema :as schema]))

(def identity-attributes
  [:file/id :symbol/id :topic/id :edge/id :reference/id :effect/id
   :aggregate/id :membership/id])

(defn entity-identity
  "Return the canonical identity tuple for a graph entity."
  [entity]
  (some (fn [attribute]
          (when-some [value (get entity attribute)]
            [attribute value]))
        identity-attributes))

(defn default-symbol-role
  "Compatibility mapping for analyzer adapters migrating to graph format 3."
  [{:symbol/keys [kind macro?]}]
  (cond
    macro? :role/macro
    (= kind :symbol.kind/macro) :role/macro
    (= kind :symbol.kind/protocol) :role/protocol
    (= kind :symbol.kind/method) :role/method
    (= kind :symbol.kind/variable) :role/variable
    (= kind :symbol.kind/constant) :role/variable
    (= kind :symbol.kind/namespace) :role/namespace
    (= kind :symbol.kind/module) :role/module
    :else :role/definition))

(defn default-symbol-scope
  "Compatibility mapping for persistent definitions. Lexical locals are not
  accepted by the format-3 scope enum and must remain analyzer-local facts."
  [{:symbol/keys [kind]}]
  (case kind
    :symbol.kind/namespace :scope/namespace
    :symbol.kind/module :scope/module
    :symbol.kind/method :scope/method
    :scope/top-level))

(defn default-indexable?
  "Every top-level definition and container is indexable in graph format 4.
  Namespace/module semantic documents provide a deterministic coarse-grained
  complement to exact child-symbol documents."
  [_]
  true)

(defn- normalize-scope [scope]
  (get {:namespace :scope/namespace
        :module :scope/module
        :top-level :scope/top-level
        :method :scope/method}
       scope scope))

(defn- normalize-role [role]
  (get {:definition :role/definition
        :macro :role/macro
        :protocol :role/protocol
        :method :role/method
        :variable :role/variable
        :namespace :role/namespace
        :module :role/module}
       role role))

(defn- evidence-of [entity]
  (or (:entity/evidence entity)
      (:edge/evidence entity)
      (:reference/evidence entity)
      (when (= :entity.type/symbol (:entity/type entity))
        :analyzer-definition)))

(defn- analyzer-of [entity fallback]
  (or (:entity/analyzer entity)
      (:symbol/analyzer entity)
      fallback
      (let [evidence (some-> (evidence-of entity) name)]
        (cond
          (str/starts-with? (or evidence "") "clj-kondo-") :clj-kondo
          (str/starts-with? (or evidence "") "janet-") :janet-semantic
          (contains? #{"literal-clojure-form" "computed-clojure-topic"}
                     evidence)
          :clojure-topic-analysis))))

(defn normalize-entity
  "Normalize one adapter entity into the format-3 IR.

  `:analyzer` is a compatibility fallback for records such as edges whose old
  representation carried only an evidence keyword. Source byte offsets cannot
  be reconstructed from line/column pairs and must be supplied by the adapter."
  ([entity]
   (normalize-entity entity nil))
  ([entity {:keys [analyzer]}]
   (let [entity (dissoc entity :db/id)
         entity (if (= :entity.type/symbol (:entity/type entity))
                  (cond-> (cond-> entity
                            (:symbol/scope entity)
                            (update :symbol/scope normalize-scope)
                            (:symbol/role entity)
                            (update :symbol/role normalize-role))
                    (nil? (:symbol/scope entity))
                    (assoc :symbol/scope (default-symbol-scope entity))
                    (nil? (:symbol/role entity))
                    (assoc :symbol/role (default-symbol-role entity))
                    (nil? (:symbol/indexable? entity))
                    (assoc :symbol/indexable? (default-indexable? entity)))
                  entity)]
     (if (contains? #{:entity.type/symbol :entity.type/edge
                      :entity.type/reference :entity.type/effect}
                    (:entity/type entity))
       (let [evidence (evidence-of entity)
             analyzer (analyzer-of entity analyzer)]
         (cond-> (assoc entity :entity/record-kind (:entity/type entity))
           evidence (assoc :entity/evidence evidence)
           analyzer (assoc :entity/analyzer analyzer)))
       entity))))

(defn- bounded-fact [entity]
  (reduce-kv
   (fn [result key value]
     (assoc result key
            (if (and (string? value) (> (count value) 256))
              (str (subs value 0 256) "...")
              value)))
   (sorted-map)
   (dissoc entity :db/id :source/snippet :symbol/doc :symbol/search-text)))

(defn canonicalize-entities
  "Normalize and validate analyzer observations.

  Structurally identical observations with one canonical identity collapse.
  Reusing an identity for differing facts is an analyzer contract violation.
  Ordering follows the first occurrence so canonicalization is deterministic
  without erasing legitimate repetitions that have distinct identities."
  ([entities]
   (canonicalize-entities entities nil))
  ([entities options]
   (let [{:keys [result]}
         (reduce
          (fn [{:keys [seen] :as state} raw]
            (let [entity (normalize-entity raw options)
                  identity (entity-identity entity)]
              (when-not identity
                (throw (ex-info "Canonical entity has no identity"
                                {:entity (bounded-fact entity)})))
              (if-let [existing (get seen identity)]
                (if (= existing entity)
                  state
                  (throw
                   (ex-info
                    "Conflicting facts for canonical entity identity"
                    {:identity identity
                     :conflicting-facts
                     [(bounded-fact existing) (bounded-fact entity)]})))
                (do
                  (schema/validate-entity! entity)
                  (-> state
                      (assoc-in [:seen identity] entity)
                      (update :result conj entity))))))
          {:seen {} :result []}
          entities)]
     result)))

(defn- require-target!
  [by-identity entity attribute target-attribute target-types]
  (let [target-id (get entity attribute)
        target (get by-identity [target-attribute target-id])]
    (when-not (and target (contains? target-types (:entity/type target)))
      (throw
       (ex-info "Canonical snapshot has an invalid foreign key"
                {:identity (entity-identity entity)
                 :attribute attribute
                 :target [target-attribute target-id]
                 :expected-types target-types})))
    target))

(defn- owning-file
  [by-identity entity]
  (case (:entity/type entity)
    :entity.type/file entity
    :entity.type/symbol
    (require-target! by-identity entity :symbol/file :file/id
                     #{:entity.type/file})
    :entity.type/edge
    (recur by-identity
           (require-target! by-identity entity :edge/from :symbol/id
                            #{:entity.type/symbol}))
    :entity.type/reference
    (recur by-identity
           (require-target! by-identity entity :reference/symbol :symbol/id
                            #{:entity.type/symbol}))
    :entity.type/effect
    (recur by-identity
           (require-target! by-identity entity :effect/symbol :symbol/id
                            #{:entity.type/symbol}))
    :entity.type/aggregate
    (require-target! by-identity entity :aggregate/file :file/id
                     #{:entity.type/file})
    :entity.type/membership
    (recur by-identity
           (require-target! by-identity entity :membership/aggregate
                            :aggregate/id #{:entity.type/aggregate}))
    nil))

(defn- audit-range!
  [by-identity entity]
  (when (some #(contains? entity %) schema/source-range-keys)
    (when-not (schema/valid-optional-source-range? entity)
      (throw (ex-info "Canonical entity has an invalid source range"
                      {:identity (entity-identity entity)
                       :range (select-keys entity schema/source-range-keys)})))
    (when-let [file (owning-file by-identity entity)]
      (when (and (contains? entity :source/end-byte)
                 (> (:source/end-byte entity) (:file/size file)))
        (throw
         (ex-info "Canonical source range exceeds its owning file"
                  {:identity (entity-identity entity)
                   :file-id (:file/id file)
                   :file-size (:file/size file)
                   :range (select-keys entity schema/source-range-keys)}))))))

(defn audit-snapshot!
  "Audit every foreign key and source range in a complete canonical snapshot.
  Returns the entities unchanged. Incremental writers should audit the merged
  committed snapshot, not only a changed-file fragment."
  [entities]
  (let [entities (vec entities)
        by-identity (into {} (map (juxt entity-identity identity)) entities)]
    (doseq [entity entities]
      (case (:entity/type entity)
        :entity.type/symbol
        (require-target! by-identity entity :symbol/file :file/id
                         #{:entity.type/file})
        :entity.type/edge
        (do
          (require-target! by-identity entity :edge/from :symbol/id
                           #{:entity.type/symbol})
          (let [target-id (:edge/to entity)
                target-attribute
                (if (and (string? target-id)
                         (str/starts-with? target-id "topic:"))
                  :topic/id :symbol/id)]
            (require-target! by-identity entity :edge/to target-attribute
                             #{:entity.type/symbol :entity.type/topic})))
        :entity.type/reference
        (require-target! by-identity entity :reference/symbol :symbol/id
                         #{:entity.type/symbol})
        :entity.type/effect
        (require-target! by-identity entity :effect/symbol :symbol/id
                         #{:entity.type/symbol})
        :entity.type/aggregate
        (do
          (require-target! by-identity entity :aggregate/owner :symbol/id
                           #{:entity.type/symbol})
          (require-target! by-identity entity :aggregate/file :file/id
                           #{:entity.type/file}))
        :entity.type/membership
        (require-target! by-identity entity :membership/aggregate
                         :aggregate/id #{:entity.type/aggregate})
        nil)
      (audit-range! by-identity entity))
    entities))

(defn canonical-snapshot
  "Canonicalize, deduplicate, validate, and audit a complete graph snapshot."
  ([entities]
   (canonical-snapshot entities nil))
  ([entities options]
   (audit-snapshot! (canonicalize-entities entities options))))
