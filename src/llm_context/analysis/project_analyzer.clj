(ns llm-context.analysis.project-analyzer
  "Coordinate authoritative whole-project analyzer snapshots."
  (:require [clojure.set :as set]
            [llm-context.analysis.canonical :as canonical]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.clojure :as clojure-analysis]
            [llm-context.analysis.ir :as ir]
            [llm-context.analysis.janet :as janet]
            [llm-context.model.ids :as ids]))

(defn- edn-output
  [{:keys [relative-path language content modified-at]}]
  {:file {:entity/type :entity.type/file
          :file/id (ids/file-id relative-path)
          :file/path relative-path
          :file/language language
          :file/content-hash (ids/content-hash content)
          :file/size
          (alength
           (.getBytes ^String content
                      java.nio.charset.StandardCharsets/UTF_8))
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

(defn- emit! [progress stage data]
  (when progress
    (progress (assoc data :stage stage))))

(defn- run-phase [progress phase operation summary]
  (emit! progress :analyzer-phase-start {:phase phase})
  (let [started (System/nanoTime)
        value (operation)
        elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
        details (summary value)]
    (emit! progress :analyzer-phase-complete
           (assoc details :phase phase :elapsed-ms elapsed-ms))
    {:value value :elapsed-ms elapsed-ms :details details}))

(defn- analysis-counts [snapshot]
  (into (sorted-map)
        (map (fn [[kind records]] [kind (count records)]))
        (:analysis snapshot)))

(defn- output-counts [outputs]
  (let [entities (mapcat :entities outputs)
        by-type (frequencies (map :entity/type entities))]
    {:files (count outputs)
     :entities (reduce + 0 (map #(count (:entities %)) outputs))
     :symbols (get by-type :entity.type/symbol 0)
     :exact-edges (get by-type :entity.type/edge 0)
     :references (get by-type :entity.type/reference 0)
     :topics (get by-type :entity.type/topic 0)
     :effects (get by-type :entity.type/effect 0)}))

(defn- outputs-by-path!
  "Require the analyzer boundary to be a bijection with discovery. Silent
  last-write-wins path maps previously hid duplicate adapter outputs."
  [files outputs]
  (let [expected (set (map :relative-path files))
        grouped (group-by (comp :file/path :file) outputs)
        duplicate-paths (->> grouped
                             (keep (fn [[path values]]
                                     (when (not= 1 (count values)) path)))
                             sort vec)
        actual (set (keys grouped))
        missing (sort (set/difference expected actual))
        extra (sort (set/difference actual expected))]
    (when (or (seq duplicate-paths) (seq missing) (seq extra))
      (throw
       (ex-info "Analyzer output does not match discovered source inventory"
                {:duplicate-paths duplicate-paths
                 :missing-paths (vec missing)
                 :extra-paths (vec extra)})))
    (into {} (map (fn [[path values]] [path (first values)])) grouped)))

(defn- canonical-output-owner
  [entity file-path-by-id symbol-file-by-id topic-owner-by-id]
  (case (:entity/type entity)
    :entity.type/file (:file/path entity)
    :entity.type/symbol (get file-path-by-id (:symbol/file entity))
    :entity.type/edge
    (get file-path-by-id (get symbol-file-by-id (:edge/from entity)))
    :entity.type/reference
    (get file-path-by-id (get symbol-file-by-id (:reference/symbol entity)))
    :entity.type/effect
    (get file-path-by-id (get symbol-file-by-id (:effect/symbol entity)))
    :entity.type/topic (get topic-owner-by-id (:topic/id entity))
    nil))

(defn- canonicalize-outputs
  "Canonicalize the complete project before facts are assigned back to
  file-scoped persistence units. Shared topics get one deterministic owner;
  legitimate repeated observations retain their distinct identities."
  [files outputs]
  (let [raw-by-path (outputs-by-path! files outputs)
        entities (canonical/canonical-snapshot
                  (mapcat (fn [{:keys [file entities]}]
                            (cons file entities))
                          outputs))
        file-path-by-id
        (into {} (keep (fn [entity]
                         (when (= :entity.type/file (:entity/type entity))
                           [(:file/id entity) (:file/path entity)])))
              entities)
        symbol-file-by-id
        (into {} (keep (fn [entity]
                         (when (= :entity.type/symbol (:entity/type entity))
                           [(:symbol/id entity) (:symbol/file entity)])))
              entities)
        topic-owner-by-id
        (reduce
         (fn [owners entity]
           (if (and (= :entity.type/edge (:entity/type entity))
                    (string? (:edge/to entity))
                    (.startsWith ^String (:edge/to entity) "topic:"))
             (let [path (get file-path-by-id
                             (get symbol-file-by-id (:edge/from entity)))]
               (update owners (:edge/to entity)
                       #(if (and % (pos? (compare path %))) % path)))
             owners))
         {} entities)
        canonical-files
        (into {} (keep (fn [entity]
                         (when (= :entity.type/file (:entity/type entity))
                           [(:file/path entity) entity])))
              entities)
        grouped
        (reduce
         (fn [result entity]
           (if (= :entity.type/file (:entity/type entity))
             result
             (let [path (canonical-output-owner
                         entity file-path-by-id symbol-file-by-id
                         topic-owner-by-id)]
               (when-not path
                 (throw
                  (ex-info "Canonical entity has no persistence owner"
                           {:identity (canonical/entity-identity entity)})))
               (update result path (fnil conj []) entity))))
         {} entities)]
    (mapv
     (fn [{:keys [relative-path]}]
       (let [raw (get raw-by-path relative-path)]
         (assoc raw
                :file (get canonical-files relative-path)
                :entities (vec (get grouped relative-path [])))))
     files)))

(defn- fingerprint-outputs [outputs]
  (mapv with-fingerprint outputs))

(defn analyze
  "Analyze every discovered supported file and return one output per file in
  discovery order. clj-kondo runs once for the full Clojure family."
  ([project files]
   (analyze project files nil))
  ([project files progress]
  (let [files (vec files)
        clojure-files
        (filterv #(contains? clj-kondo/clojure-languages (:language %)) files)
        janet-files (filterv #(= :language/janet (:language %)) files)
        edn-files (filterv #(= :language/edn-data (:language %)) files)
        clojure-phase
        (run-phase progress :clj-kondo
                   #(clj-kondo/analyze! project clojure-files)
                   (fn [snapshot]
                     {:files (count clojure-files)
                      :records (reduce + 0 (vals (analysis-counts snapshot)))}))
        clojure-snapshot (:value clojure-phase)
        janet-phase
        (run-phase progress :janet-analysis
                   #(if (seq janet-files)
                      (janet/analyze project janet-files)
                      {:outputs [] :diagnostics []
                       :catalog-version janet/catalog-version})
                   (fn [snapshot]
                     {:files (count janet-files)
                      :entities (reduce + 0
                                        (map (comp count :entities)
                                             (:outputs snapshot)))}))
        janet-snapshot (:value janet-phase)
        janet-outputs (:outputs janet-snapshot)
        materialize-phase
        (run-phase progress :relationship-materialization
                   #(mapv ir/normalize-output
                          (concat
                           (clojure-analysis/materialize
                            clojure-files clojure-snapshot)
                           janet-outputs
                           (map edn-output edn-files)))
                   output-counts)
        raw-outputs (:value materialize-phase)
        canonical-phase
        (run-phase progress :canonicalization
                   #(canonicalize-outputs files raw-outputs)
                   output-counts)
        canonical-outputs (:value canonical-phase)
        fingerprint-phase
        (run-phase progress :fingerprinting
                   #(fingerprint-outputs canonical-outputs)
                   output-counts)
        outputs (:value fingerprint-phase)
        timings
        {:clj-kondo-ms (:elapsed-ms clojure-phase)
         :janet-analysis-ms (:elapsed-ms janet-phase)
         :relationship-materialization-ms (:elapsed-ms materialize-phase)
         :canonicalization-ms (:elapsed-ms canonical-phase)
         :fingerprinting-ms (:elapsed-ms fingerprint-phase)}]
    {:outputs
     outputs
     :analyzers
     {:clj-kondo {:version (:analyzer-version clojure-snapshot)
                  :configuration-fingerprint
                  (:configuration-fingerprint clojure-snapshot)}
      :janet {:catalog-version (:catalog-version janet-snapshot)}}
     :analysis-metrics
     {:timings timings
      :clojure-records (analysis-counts clojure-snapshot)
      :output (output-counts outputs)}
     ;; File-scoped clj-kondo integrity diagnostics live on their output so
     ;; preservation decisions and user reporting share one source of truth.
     :diagnostics (vec (:diagnostics janet-snapshot))})))
