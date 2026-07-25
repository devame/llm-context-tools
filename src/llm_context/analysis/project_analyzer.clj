(ns llm-context.analysis.project-analyzer
  "Coordinate authoritative whole-project analyzer snapshots."
  (:require [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.clojure :as clojure-analysis]
            [llm-context.analysis.janet :as janet]
            [llm-context.model.ids :as ids]))

(defn- edn-output
  [{:keys [relative-path language content size modified-at]}]
  {:file {:entity/type :entity.type/file
          :file/id (ids/file-id relative-path)
          :file/path relative-path
          :file/language language
          :file/content-hash (ids/content-hash content)
          :file/size size
          :file/modified-at modified-at}
   :entities []
   :diagnostics []})

(defn semantic-fingerprint [{:keys [entities]}]
  (ids/content-hash
   (pr-str
    (sort-by pr-str
             (map #(dissoc % :db/id :symbol/search-text)
                  entities)))))

(defn- with-fingerprint [output]
  (assoc-in output [:file :file/semantic-hash]
            (semantic-fingerprint output)))

(defn- assign-shared-topics
  "Topic identities are project-global. Keep one canonical assertion on the
  first file that references each topic while retaining every file-owned edge."
  [outputs]
  (let [outputs (vec outputs)
        topics (->> outputs
                    (mapcat :entities)
                    (filter #(= :entity.type/topic (:entity/type %)))
                    (reduce (fn [result topic]
                              (assoc result (:topic/id topic) topic))
                            (sorted-map)))
        without-topics
        (mapv #(update % :entities
                       (fn [entities]
                         (vec (remove (fn [entity]
                                        (= :entity.type/topic
                                           (:entity/type entity)))
                                      entities))))
              outputs)
        owner-by-topic
        (reduce
         (fn [result [index output]]
           (reduce
            (fn [owners entity]
              (if (and (= :entity.type/edge (:entity/type entity))
                       (contains? topics (:edge/to entity)))
                (update owners (:edge/to entity)
                        #(if (nil? %) index (min % index)))
                owners))
            result (:entities output)))
         {} (map-indexed vector without-topics))]
    (reduce-kv
     (fn [result topic-id topic]
       (if-let [index (get owner-by-topic topic-id)]
         (update-in result [index :entities] conj topic)
         result))
     without-topics topics)))

(defn analyze
  "Analyze every discovered supported file and return one output per file in
  discovery order. clj-kondo runs once for the full Clojure family."
  [project files]
  (let [files (vec files)
        clojure-files
        (filterv #(contains? clj-kondo/clojure-languages (:language %)) files)
        janet-files (filterv #(= :language/janet (:language %)) files)
        edn-files (filterv #(= :language/edn-data (:language %)) files)
        clojure-snapshot (clj-kondo/analyze! project clojure-files)
        janet-snapshot
        (if (seq janet-files)
          (janet/analyze project janet-files)
          {:outputs [] :diagnostics [] :catalog-version janet/catalog-version})
        janet-outputs (:outputs janet-snapshot)
        outputs
        (concat
         (clojure-analysis/materialize clojure-files clojure-snapshot)
         janet-outputs
         (map edn-output edn-files))
        outputs (assign-shared-topics outputs)
        by-path (into {} (map (juxt (comp :file/path :file) identity))
                      outputs)]
    {:outputs
     (mapv #(with-fingerprint (get by-path (:relative-path %))) files)
     :analyzers
     {:clj-kondo {:version (:analyzer-version clojure-snapshot)
                  :configuration-fingerprint
                  (:configuration-fingerprint clojure-snapshot)}
      :janet {:catalog-version (:catalog-version janet-snapshot)}}
     :diagnostics (vec (concat (:diagnostics clojure-snapshot)
                               (:diagnostics janet-snapshot)))}))
