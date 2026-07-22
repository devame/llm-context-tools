(ns llm-context.store-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [llm-context.config :as config]
            [llm-context.model.ids :as ids]
            [llm-context.model.schema :as schema]
            [llm-context.project :as project]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(defn temp-project []
  (project/context (str (Files/createTempDirectory "llm-context-store-"
                                                   (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn file-entity [path content]
  {:entity/type :entity.type/file
   :file/id (ids/file-id path)
   :file/path path
   :file/language :language/clojure
   :file/content-hash (ids/content-hash content)
   :file/size (count (.getBytes content java.nio.charset.StandardCharsets/UTF_8))
   :file/modified-at 100})

(defn symbol-entity [file name line]
  (let [parts {:file-id (:file/id file) :kind :symbol.kind/function
               :qualified-name name :signature "[]"
               :start-line line :start-column 1}]
    {:entity/type :entity.type/symbol
     :symbol/id (ids/symbol-id parts)
     :symbol/name name
     :symbol/qualified-name name
     :symbol/kind :symbol.kind/function
     :symbol/file (:file/id file)
     :symbol/signature "[]"
     :source/start-line line :source/start-column 1
     :source/end-line line :source/end-column 10}))

(defn fulltext-symbol-names [graph query]
  (set
   (store/query
    graph
    '[:find [?name ...]
      :in $ ?query
      :where
      [(fulltext $ ?query {:domains ["symbols"]})
       [[?symbol ?attribute ?value]]]
      [?symbol :symbol/qualified-name ?name]]
    [query])))

(deftest native-datalevin-round-trip
  (let [project (temp-project)
        file (file-entity "src/a.clj" "(defn a [])")
        symbol (symbol-entity file "sample/a" 1)]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file [symbol])
      (is (= #{["sample/a"]}
             (store/query graph
                          '[:find ?name :where [_ :symbol/qualified-name ?name]]
                          [])))
      (is (= 2 (count (store/query graph
                                  '[:find [?entity ...]
                                    :where [?entity :entity/type _]] [])))))))

(deftest existing-symbols-are-backfilled-into-full-text-search
  (let [project (temp-project)
        file (file-entity "src/legacy.clj" "legacy")
        symbol (assoc (symbol-entity file "sample/legacy-loader" 1)
                      :symbol/doc "Hydrate historical project state")
        legacy-schema (dissoc schema/datalevin-schema :symbol/search-text)
        path (.normalize (.resolve (:root project) ".llm-context/db"))]
    (Files/createDirectories path
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (with-open [connection (d/get-conn (str path) legacy-schema)]
      (d/transact! connection
                   [(assoc file :db/id -1)
                    (assoc symbol :db/id -2 :symbol/file -1)]))
    (store/with-store [graph project (config/defaults)]
      (is (= #{["sample/legacy-loader"]}
             (store/query
              graph
              '[:find ?qualified
                :where
                [(fulltext $ "historical project"
                           {:domains ["symbols"]})
                 [[?symbol ?attribute ?value]]]
                [?symbol :symbol/qualified-name ?qualified]]
              [])))
      (is (= 1
             (store/query
              graph
              '[:find ?version .
                :where [?meta :llm-context/meta-key "search-index"]
                       [?meta :llm-context/search-schema-version ?version]]
              []))))))

(deftest replacement-and-deletion-are-cascading
  (let [project (temp-project)
        file (file-entity "src/a.clj" "old")
        old-symbol (symbol-entity file "sample/old" 1)
        new-file (file-entity "src/a.clj" "new")
        new-symbol (symbol-entity new-file "sample/new" 2)]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file [old-symbol])
      (is (= #{"sample/old"} (fulltext-symbol-names graph "old")))
      (store/replace-file! graph new-file [new-symbol])
      (is (empty? (fulltext-symbol-names graph "old")))
      (is (= #{"sample/new"} (fulltext-symbol-names graph "new")))
      (is (= #{"sample/new"}
             (set (store/query graph
                               '[:find [?name ...]
                                 :where [_ :symbol/qualified-name ?name]]
                               []))))
      (store/delete-file! graph (:file/id new-file))
      (is (empty? (fulltext-symbol-names graph "new")))
      (is (empty? (store/query graph
                               '[:find [?entity ...]
                                 :where [?entity :entity/type _]]
                               []))))))

(deftest whole-graph-replacement-resolves-forward-cross-file-references
  (let [project (temp-project)
        source-file (file-entity "src/source.clj" "source")
        target-file (file-entity "src/target.clj" "target")
        source (symbol-entity source-file "sample/source" 1)
        target (symbol-entity target-file "sample/target" 1)
        edge {:entity/type :entity.type/edge
              :edge/id "edge:forward"
              :edge/kind :edge.kind/calls
              :edge/from (:symbol/id source)
              :edge/to (:symbol/id target)
              :edge/target-text "sample/target"
              :edge/resolution :resolution/exact
              :edge/confidence 1.0}]
    (store/with-store [graph project (config/defaults)]
      ;; The edge intentionally precedes its target in input order.
      (store/replace-all! graph [source-file source edge target-file target])
      (is (= #{["edge:forward" "sample/target"]}
             (store/query graph
                          '[:find ?edge-id ?target-name
                            :where [?edge :edge/id ?edge-id]
                                   [?edge :edge/to ?target]
                                   [?target :symbol/qualified-name ?target-name]]
                          [])))
      (store/replace-all! graph [])
      (is (empty? (store/query graph
                               '[:find [?entity ...]
                                 :where [?entity :entity/type _]]
                               []))))))

(deftest transaction-order-puts-reference-targets-first
  (let [file (file-entity "src/a.clj" "source")
        symbol (symbol-entity file "sample/a" 1)
        edge {:entity/type :entity.type/edge :edge/id "edge:a"
              :edge/from (:symbol/id symbol) :edge/to (:symbol/id symbol)}
        effect {:entity/type :entity.type/effect :effect/id "effect:a"
                :effect/symbol (:symbol/id symbol)}]
    (is (= [:entity.type/file :entity.type/symbol
            :entity.type/edge :entity.type/effect]
           (mapv :entity/type
                 (#'store/dependency-order [effect edge symbol file]))))))

(deftest duplicate-canonical-identities-are-rejected
  (let [project (temp-project)
        file (file-entity "src/a.clj" "source")]
    (store/with-store [graph project (config/defaults)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Duplicate canonical entity identities"
           (store/replace-all! graph [file file]))))))

(deftest whole-graph-replacement-commits-bounded-batches
  (let [project (temp-project)
        file-a (file-entity "src/a.clj" "source-a")
        file-b (file-entity "src/b.clj" "source-b")
        symbols (mapv (fn [index]
                        (symbol-entity (if (even? index) file-a file-b)
                                       (str "sample/function-" index)
                                       (inc index)))
                      (range 205))
        events (atom [])]
    (store/with-store [graph project (config/defaults)]
      (store/replace-all! graph
                          (concat symbols [file-b file-a])
                          {:batch-size 100
                           :on-progress #(swap! events conj %)})
      (is (= [{:phase :assert :completed 100 :total 207}
              {:phase :assert :completed 200 :total 207}
              {:phase :assert :completed 207 :total 207}]
             @events))
      (is (= 207
             (count (store/query graph
                                 '[:find [?entity ...]
                                   :where [?entity :entity/type _]]
                                 [])))))))

(deftest target-file-replacement-preserves-inbound-evidence
  (let [project (temp-project)
        source-file (file-entity "src/source.clj" "source")
        target-file (file-entity "src/target.clj" "target")
        source (symbol-entity source-file "sample/source" 1)
        target (symbol-entity target-file "sample/target" 1)
        edge {:entity/type :entity.type/edge
              :edge/id "edge:inbound"
              :edge/kind :edge.kind/calls
              :edge/from (:symbol/id source)
              :edge/to (:symbol/id target)
              :edge/target-text "target"
              :edge/resolution :resolution/exact
              :edge/confidence 1.0}]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph source-file [source])
      (store/replace-file! graph target-file [target])
      (store/transact! graph [edge])
      (store/delete-file! graph (:file/id target-file))
      (is (= #{["edge:inbound" :resolution/unresolved]}
             (store/query graph
                          '[:find ?id ?resolution
                            :where [?edge :edge/id ?id]
                                   [?edge :edge/resolution ?resolution]]
                          [])))
      (store/reconcile-edges! graph
                              [{:edge-id "edge:inbound"
                                :target-id nil
                                :resolution :resolution/unresolved
                                :confidence 0.0}])
      (is (empty? (store/query graph
                               '[:find [?target ...]
                                 :where [?edge :edge/id "edge:inbound"]
                                        [?edge :edge/to ?target]]
                               []))))))
