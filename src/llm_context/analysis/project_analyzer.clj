(ns llm-context.analysis.project-analyzer
  "Coordinate authoritative whole-project analyzer snapshots."
  (:require [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.clojure :as clojure-analysis]
            [llm-context.analysis.effects :as effects]
            [llm-context.analysis.structural :as structural]
            [llm-context.indexer :as indexer]
            [llm-context.model.ids :as ids]
            [llm-context.parser.jtreesitter :as jtreesitter]))

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

(defn- enrich-janet-effects [{:keys [file entities] :as output}]
  (let [edges (filter :edge/id entities)]
    (update output :entities into
            (effects/analyze (:file/language file) edges))))

(defn semantic-fingerprint [{:keys [entities]}]
  (ids/content-hash
   (pr-str
    (sort-by pr-str
             (map #(dissoc % :db/id :symbol/search-text)
                  entities)))))

(defn- with-fingerprint [output]
  (assoc-in output [:file :file/semantic-hash]
            (semantic-fingerprint output)))

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
        janet-outputs
        (if (seq janet-files)
          (with-open [parser (jtreesitter/open project)]
            (let [analyzer (structural/create parser)]
              (mapv #(-> (indexer/index-file analyzer %)
                         enrich-janet-effects)
                    janet-files)))
          [])
        outputs
        (concat
         (clojure-analysis/materialize clojure-files clojure-snapshot)
         janet-outputs
         (map edn-output edn-files))
        by-path (into {} (map (juxt (comp :file/path :file) identity))
                      outputs)]
    {:outputs
     (mapv #(with-fingerprint (get by-path (:relative-path %))) files)
     :analyzers
     {:clj-kondo {:version (:analyzer-version clojure-snapshot)
                  :configuration-fingerprint
                  (:configuration-fingerprint clojure-snapshot)}}
     :diagnostics (:diagnostics clojure-snapshot)}))
