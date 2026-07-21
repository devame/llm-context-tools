(ns llm-context.export
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [llm-context.query :as query]
            [llm-context.store :as store]))

(def schema-version 1)

(defn- attribute-map [graph id-attribute attribute]
  (into {}
        (store/query graph
                     '[:find ?id ?value
                       :in $ ?id-attribute ?attribute
                       :where [?entity ?id-attribute ?id]
                              [?entity ?attribute ?value]]
                     [id-attribute attribute])))

(defn- source-attributes [graph id-attribute]
  (let [attributes [:source/start-line :source/start-column
                    :source/end-line :source/end-column :source/snippet]
        maps (into {} (map (fn [attribute]
                             [attribute (attribute-map graph id-attribute attribute)]))
                   attributes)]
    (fn [id]
      (into {} (keep (fn [[attribute values]]
                       (when-let [value (get values id)] [attribute value]))) maps))))

(defn entities [graph]
  (let [file-rows (store/query
                   graph
                   '[:find ?id ?path ?language ?hash ?size ?modified
                     :where [?file :file/id ?id]
                            [?file :file/path ?path]
                            [?file :file/language ?language]
                            [?file :file/content-hash ?hash]
                            [?file :file/size ?size]
                            [?file :file/modified-at ?modified]] [])
        symbol-source (source-attributes graph :symbol/id)
        signatures (attribute-map graph :symbol/id :symbol/signature)
        docs (attribute-map graph :symbol/id :symbol/doc)
        symbol-rows (store/query
                     graph
                     '[:find ?id ?name ?qualified ?kind ?file-id
                       :where [?symbol :symbol/id ?id]
                              [?symbol :symbol/name ?name]
                              [?symbol :symbol/qualified-name ?qualified]
                              [?symbol :symbol/kind ?kind]
                              [?symbol :symbol/file ?file]
                              [?file :file/id ?file-id]] [])
        edge-source (source-attributes graph :edge/id)
        edge-targets (into {} (store/query
                               graph
                               '[:find ?id ?target-id
                                 :where [?edge :edge/id ?id]
                                        [?edge :edge/to ?target]
                                        [?target :symbol/id ?target-id]] []))
        edge-rows (store/query
                   graph
                   '[:find ?id ?kind ?from-id ?target-text ?resolution ?confidence
                     :where [?edge :edge/id ?id]
                            [?edge :edge/kind ?kind]
                            [?edge :edge/from ?from]
                            [?from :symbol/id ?from-id]
                            [?edge :edge/target-text ?target-text]
                            [?edge :edge/resolution ?resolution]
                            [?edge :edge/confidence ?confidence]] [])
        effect-source (source-attributes graph :effect/id)
        effect-rows (store/query
                     graph
                     '[:find ?id ?kind ?symbol-id ?detail ?confidence
                       :where [?effect :effect/id ?id]
                              [?effect :effect/kind ?kind]
                              [?effect :effect/symbol ?symbol]
                              [?symbol :symbol/id ?symbol-id]
                              [?effect :effect/detail ?detail]
                              [?effect :effect/confidence ?confidence]] [])]
    (->> (concat
          (map (fn [[id path language hash size modified]]
                 {:entity/type :entity.type/file :file/id id :file/path path
                  :file/language language :file/content-hash hash
                  :file/size size :file/modified-at modified}) file-rows)
          (map (fn [[id name qualified kind file-id]]
                 (merge {:entity/type :entity.type/symbol :symbol/id id
                         :symbol/name name :symbol/qualified-name qualified
                         :symbol/kind kind :symbol/file file-id}
                        (symbol-source id)
                        (when-let [value (get signatures id)] {:symbol/signature value})
                        (when-let [value (get docs id)] {:symbol/doc value}))) symbol-rows)
          (map (fn [[id kind from-id target-text resolution confidence]]
                 (merge {:entity/type :entity.type/edge :edge/id id :edge/kind kind
                         :edge/from from-id :edge/target-text target-text
                         :edge/resolution resolution :edge/confidence confidence}
                        (when-let [target (get edge-targets id)] {:edge/to target})
                        (edge-source id))) edge-rows)
          (map (fn [[id kind symbol-id detail confidence]]
                 (merge {:entity/type :entity.type/effect :effect/id id
                         :effect/kind kind :effect/symbol symbol-id
                         :effect/detail detail :effect/confidence confidence}
                        (effect-source id))) effect-rows))
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
  (let [stats (query/stats graph)
        entries (take 20 (query/entry-points graph))
        effects (query/effects graph)
        unresolved (query/unresolved graph)]
    (str "# Semantic graph summary\n\n"
         "Generated from the Datalevin graph; no claims are inferred beyond stored facts.\n\n"
         "## Counts\n\n"
         "- Files: " (:files stats) "\n"
         "- Symbols: " (:symbols stats) "\n"
         "- Relationships: " (:edges stats) "\n"
         "- Effects: " (:effects stats) "\n"
         "- Unresolved or ambiguous relationships: " (count unresolved) "\n\n"
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
