(ns llm-context.export
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [datalevin.core :as d]
            [llm-context.graph.read :as graph-read]
            [llm-context.query :as query]
            [llm-context.store :as store]))

(def schema-version 1)

(def ^:private source-pattern
  [:source/start-line :source/start-column
   :source/end-line :source/end-column :source/snippet])

(def ^:private export-patterns
  {:entity.type/file
   [:entity/type :file/id :file/path :file/language :file/content-hash
    :file/size :file/modified-at]

   :entity.type/symbol
   (into [:entity/type :symbol/id :symbol/name :symbol/qualified-name
          :symbol/kind :symbol/signature :symbol/doc
          {:symbol/file [:file/id]}]
         source-pattern)

   :entity.type/edge
   (into [:entity/type :edge/id :edge/kind :edge/target-text
          :edge/resolution :edge/confidence
          {:edge/from [:symbol/id]} {:edge/to [:symbol/id]}]
         source-pattern)

   :entity.type/effect
   (into [:entity/type :effect/id :effect/kind :effect/detail
          :effect/confidence {:effect/symbol [:symbol/id]}]
         source-pattern)})

(defn- flatten-reference [entity reference output-key identity-key]
  (let [target (get entity reference)]
    (cond-> (dissoc entity reference)
      target (assoc output-key (get target identity-key)))))

(defn- export-record [entity]
  (case (:entity/type entity)
    :entity.type/symbol
    (flatten-reference entity :symbol/file :symbol/file :file/id)

    :entity.type/edge
    (-> entity
        (flatten-reference :edge/from :edge/from :symbol/id)
        (flatten-reference :edge/to :edge/to :symbol/id))

    :entity.type/effect
    (flatten-reference entity :effect/symbol :effect/symbol :symbol/id)

    entity))

(defn entities [graph]
  (let [db (store/database graph)
        eids-by-type
        (reduce (fn [result [type eid]]
                  (update result type (fnil conj []) eid))
                {}
                (d/q '[:find ?type ?entity
                       :where [?entity :entity/type ?type]]
                     db))]
    (->> export-patterns
         (mapcat (fn [[type pattern]]
                   (when-let [eids (seq (get eids-by-type type))]
                     (map export-record (d/pull-many db pattern eids)))))
         (sort-by (juxt :entity/type #(or (:file/id %) (:symbol/id %)
                                         (:edge/id %) (:effect/id %))))
         vec)))

(defn- keyword-string [value]
  (if-let [namespace (namespace value)]
    (str namespace "/" (name value))
    (name value)))

(defn json-ready [value]
  (cond
    (keyword? value) (keyword-string value)
    (map? value) (into (sorted-map)
                       (map (fn [[key item]]
                              [(if (keyword? key) (keyword-string key) (str key))
                               (json-ready item)])) value)
    (sequential? value) (mapv json-ready value)
    (set? value) (mapv json-ready (sort value))
    :else value))

(defn summary-markdown [graph]
  (let [db (store/database graph)
        stats (query/stats graph)
        entries (graph-read/summary-entry-points db)
        effects (graph-read/summary-effects db)
        unresolved-count (graph-read/unresolved-reference-count db)]
    (str "# Semantic graph summary\n\n"
         "Generated from the Datalevin graph; no claims are inferred beyond stored facts.\n\n"
         "## Counts\n\n"
         "- Files: " (:files stats) "\n"
         "- Symbols: " (:symbols stats) "\n"
         "- Relationships: " (:edges stats) "\n"
         "- Effects: " (:effects stats) "\n"
         "- Unresolved or ambiguous relationships: " unresolved-count "\n\n"
         "## Languages\n\n"
         (if (seq (:languages stats))
           (str/join "\n" (for [[language count] (:languages stats)]
                              (str "- " (name language) ": " count)))
           "None")
         "\n\n## Entry points\n\n"
         (if (seq entries)
           (str/join "\n" (for [{:keys [qualified-name file line]} entries]
                              (str "- `" qualified-name "` — `" file ":" line "`")))
           "None")
         "\n\n## Observed effects\n\n"
         (if (seq effects)
           (str/join "\n" (for [{:keys [kind symbol file line]} effects]
                              (str "- " (name kind) ": `" symbol "` at `"
                                   file ":" line "`")))
           "None")
         "\n")))

(defn render [graph format]
  (case format
    :edn (with-out-str (pprint/pprint {:schema/version schema-version
                                      :entities (entities graph)}))
    :json (json/write-str (json-ready {:schema/version schema-version
                                       :entities (entities graph)}))
    :jsonl (str (str/join "\n" (map #(json/write-str (json-ready %))
                                     (entities graph))) "\n")
    :markdown (summary-markdown graph)
    (throw (ex-info (str "Unsupported export format: " (name format))
                    {:exit-code 2 :format format}))))
