(require '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[datalevin.core :as d]
         '[llm-context.config :as config]
         '[llm-context.model.schema :as schema]
         '[llm-context.project :as project]
         '[llm-context.query :as query]
         '[llm-context.store :as store])

(defn fail! [message data]
  (throw (ex-info message data)))

(defn check! [value message data]
  (when-not value
    (fail! message data)))

(defn read-edn-file [path]
  (edn/read-string (slurp path)))

(defn normalized-export [path]
  (update (read-edn-file path)
          :entities
          (fn [entities]
            (mapv #(dissoc % :file/modified-at) entities))))

(defn open-graph [root]
  (let [context (project/context root)]
    (store/open context (config/load-config context))))

(defn entities [graph]
  (let [db (store/database graph)
        eids (d/q '[:find [?entity ...]
                    :in $ ?canonical-types
                    :where
                    [?entity :entity/type ?type]
                    [(contains? ?canonical-types ?type)]]
                  db
                  #{:entity.type/file :entity.type/symbol
                    :entity.type/edge :entity.type/reference
                    :entity.type/topic :entity.type/effect
                    :entity.type/aggregate :entity.type/membership})]
    (d/pull-many db '[*] eids)))

(def identity-attributes
  [:file/id :symbol/id :topic/id :edge/id :reference/id :effect/id
   :aggregate/id :membership/id])

(def reference-attributes
  #{:symbol/file :edge/from :edge/to :reference/symbol :effect/symbol
    :aggregate/owner :aggregate/file :membership/aggregate
    :membership/member})

(defn stored-snapshot
  "Return a database-independent canonical snapshot. Datalevin entity IDs and
  file mtimes are storage details, while reference attributes are translated
  back to their public canonical IDs."
  [graph]
  (let [all (entities graph)
        identity-by-eid
        (into {}
              (keep (fn [entity]
                      (when-let [identity
                                 (some #(get entity %) identity-attributes)]
                        [(:db/id entity) identity])))
              all)
        canonical-reference
        (fn [value]
          (let [eid (if (map? value) (:db/id value) value)]
            (or (get identity-by-eid eid) value)))]
    (->> all
         (mapv
          (fn [entity]
            (reduce-kv
             (fn [result attribute value]
               (cond
                 (= attribute :db/id) result
                 (= attribute :file/modified-at) result
                 (contains? reference-attributes attribute)
                 (assoc result attribute (canonical-reference value))
                 :else (assoc result attribute value)))
             {} entity)))
         (sort-by #(some (fn [attribute] (get % attribute))
                         identity-attributes))
         vec)))

(def provenance-types
  #{:entity.type/symbol :entity.type/edge
    :entity.type/reference :entity.type/effect})

(defn check-canonical-entities! [graph]
  (let [all (entities graph)
        by-type (group-by :entity/type all)
        files (get by-type :entity.type/file)
        symbols (get by-type :entity.type/symbol)
        edges (get by-type :entity.type/edge)
        references (get by-type :entity.type/reference)
        languages (set (map :file/language files))
        symbol-names (set (map :symbol/qualified-name symbols))]
    (check! (seq all) "Packaged analysis produced an empty graph" {})
    (check! (contains? languages :language/clojure)
            "Release corpus did not produce Clojure graph facts"
            {:languages languages})
    (check! (contains? languages :language/janet)
            "Release corpus did not produce Janet graph facts"
            {:languages languages})
    (doseq [qualified ["quality.names/normalize"
                       "quality.core/greet"
                       "quality.core/loud-greeting"
                       "quality.core/run"]]
      (check! (contains? symbol-names qualified)
              "Expected Clojure definition is absent"
              {:qualified-name qualified}))
    (check! (some #(= "format-name" (:symbol/name %)) symbols)
            "Expected Janet definition is absent" {})
    (check! (some #(= "run" (:symbol/name %)) symbols)
            "Expected run definition is absent" {})
    (doseq [entity all]
      (when (contains? provenance-types (:entity/type entity))
        (check! (and (= (:entity/type entity) (:entity/record-kind entity))
                     (keyword? (:entity/evidence entity))
                     (keyword? (:entity/analyzer entity)))
                "Canonical entity is missing normalized provenance"
                {:entity-type (:entity/type entity) :db-id (:db/id entity)}))
      (check! (schema/valid-optional-source-range? entity)
                "Canonical entity has a partial or invalid source range"
                {:entity-type (:entity/type entity) :db-id (:db/id entity)}))
    (check! (some #(every? (fn [attribute] (contains? % attribute))
                           schema/source-range-keys)
                  all)
            "Release corpus did not exercise complete UTF-8 source ranges" {})
    (doseq [edge edges]
      (check! (and (= :resolution/exact (:edge/resolution edge))
                   (= 1.0 (double (:edge/confidence edge)))
                   (keyword? (:edge/evidence edge))
                   (map? (:edge/from edge))
                   (map? (:edge/to edge)))
              "Traversable edge violates the exact-edge contract"
              {:edge-id (:edge/id edge)}))
    (doseq [reference references]
      (check! (contains? #{:external :dynamic :ambiguous :unresolved}
                         (:reference/classification reference))
              "Diagnostic reference has an invalid classification"
              {:reference-id (:reference/id reference)}))
    (check! (seq references)
            "Release corpus did not exercise diagnostic references" {})
    {:entities (count all)
     :files (count files)
     :symbols (count symbols)
     :edges (count edges)
     :references (count references)}))

(defn snapshot-by-identity [snapshot]
  (into (sorted-map)
        (map (fn [entity]
               [(some (fn [attribute]
                        (when-let [value (get entity attribute)]
                          [attribute value]))
                      identity-attributes)
                entity]))
        snapshot))

(defn snapshot-difference [incremental full]
  (let [incremental (snapshot-by-identity incremental)
        full (snapshot-by-identity full)
        incremental-ids (set (keys incremental))
        full-ids (set (keys full))
        shared (sort (set/intersection incremental-ids full-ids))]
    {:incremental-only (vec (sort (set/difference incremental-ids full-ids)))
     :full-only (vec (sort (set/difference full-ids incremental-ids)))
     :changed (->> shared
                   (keep (fn [identity]
                           (when-not (= (get incremental identity)
                                        (get full identity))
                             {:identity identity
                              :incremental (get incremental identity)
                              :full (get full identity)})))
                   vec)}))

(let [[incremental-root full-root incremental-export full-export] *command-line-args*]
  (when-not (every? some?
                    [incremental-root full-root incremental-export full-export])
    (fail! "usage: verify-release-graph.clj INC-ROOT FULL-ROOT INC-EXPORT FULL-EXPORT"
           {}))
  (with-open [incremental (open-graph incremental-root)
              full (open-graph full-root)]
    (doseq [[label graph] [["incremental" incremental] ["full" full]]]
      (check! (= :ready (store/graph-state graph))
              "Packaged graph is not query-compatible"
              {:mode label :state (store/graph-state graph)})
      (check! (= 4 (:llm-context/graph-format (store/graph-metadata graph)))
              "Packaged graph metadata is not format 4"
              {:mode label :metadata (store/graph-metadata graph)})
      (check! (= 4 (:llm-context/semantic-document-version
                    (store/graph-metadata graph)))
              "Packaged graph metadata has the wrong semantic document version"
              {:mode label :metadata (store/graph-metadata graph)})
      (check! (= "llm-context-v4"
                 (:llm-context/semantic-index-name
                  (store/graph-metadata graph)))
              "Packaged graph metadata has the wrong semantic index name"
              {:mode label :metadata (store/graph-metadata graph)}))
    (let [incremental-quality (check-canonical-entities! incremental)
          full-quality (check-canonical-entities! full)
          incremental-stats (query/stats incremental)
          full-stats (query/stats full)]
      (check! (= incremental-stats full-stats)
              "Incremental and full analysis produced different graph statistics"
              {:incremental incremental-stats :full full-stats})
      (let [incremental-snapshot (stored-snapshot incremental)
            full-snapshot (stored-snapshot full)]
        (check! (= incremental-snapshot full-snapshot)
              "Incremental and full analysis produced different persisted facts"
                (snapshot-difference incremental-snapshot full-snapshot)))
      (check! (= (normalized-export incremental-export)
                 (normalized-export full-export))
              "Incremental and full analysis produced different canonical exports"
              {})
      (println "Packaged graph quality passed:"
               {:incremental incremental-quality
                :full full-quality
                :stats incremental-stats}))))
