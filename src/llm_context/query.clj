(ns llm-context.query
  (:require [clojure.string :as str]
            [llm-context.store :as store]))

(defn- frequencies-query [graph query-form]
  (->> (store/query graph query-form [])
       frequencies
       (into (sorted-map))))

(defn stats [graph]
  (let [types (store/query graph
                           '[:find [?type ...] :where [_ :entity/type ?type]] [])]
    {:entities (count types)
     :files (count (filter #{:entity.type/file} types))
     :symbols (count (filter #{:entity.type/symbol} types))
     :edges (count (filter #{:entity.type/edge} types))
     :effects (count (filter #{:entity.type/effect} types))
     :languages (frequencies-query
                 graph '[:find [?language ...] :where [_ :file/language ?language]])
     :symbol-kinds (frequencies-query
                    graph '[:find [?kind ...] :where [_ :symbol/kind ?kind]])
     :edge-resolution (frequencies-query
                       graph '[:find [?state ...] :where [_ :edge/resolution ?state]])}))

(defn symbols
  "Find symbols by exact simple/qualified name or case-insensitive substring."
  [graph term]
  (let [needle (str/lower-case term)]
    (->> (store/query
          graph
          '[:find ?id ?name ?qualified ?kind ?path ?line
            :where [?symbol :symbol/id ?id]
                   [?symbol :symbol/name ?name]
                   [?symbol :symbol/qualified-name ?qualified]
                   [?symbol :symbol/kind ?kind]
                   [?symbol :symbol/file ?file]
                   [?file :file/path ?path]
                   [?symbol :source/start-line ?line]]
          [])
         (keep (fn [[id name qualified kind path line]]
                 (when (or (= term name) (= term qualified)
                           (str/includes? (str/lower-case name) needle)
                           (str/includes? (str/lower-case qualified) needle))
                   {:id id :name name :qualified-name qualified
                    :kind kind :file path :line line})))
         (sort-by (juxt #(if (or (= term (:name %))
                                 (= term (:qualified-name %))) 0 1)
                       :qualified-name))
         vec)))

(defn callers [graph target]
  (->> (store/query
        graph
        '[:find ?caller-id ?caller-name ?path ?line ?resolution
          :in $ ?target
          :where [?callee :symbol/id ?target]
                 [?edge :edge/to ?callee]
                 [?edge :edge/from ?caller]
                 [?edge :edge/resolution ?resolution]
                 [?caller :symbol/id ?caller-id]
                 [?caller :symbol/qualified-name ?caller-name]
                 [?caller :symbol/file ?file]
                 [?file :file/path ?path]
                 [?edge :source/start-line ?line]]
        [target])
       (mapv (fn [[id name path line resolution]]
               {:id id :name name :file path :line line
                :resolution resolution}))))

(defn callees [graph source]
  (let [resolved (store/query
                  graph
                  '[:find ?target-id ?target-name ?line ?resolution
                    :in $ ?source
                    :where [?caller :symbol/id ?source]
                           [?edge :edge/from ?caller]
                           [?edge :edge/to ?target]
                           [?target :symbol/id ?target-id]
                           [?target :symbol/qualified-name ?target-name]
                           [?edge :edge/resolution ?resolution]
                           [?edge :source/start-line ?line]]
                  [source])
        unresolved (store/query
                    graph
                    '[:find ?target-text ?line ?resolution
                      :in $ ?source
                      :where [?caller :symbol/id ?source]
                             [?edge :edge/from ?caller]
                             [?edge :edge/target-text ?target-text]
                             [?edge :edge/resolution ?resolution]
                             [?edge :source/start-line ?line]
                             [(not= ?resolution :resolution/exact)]
                             [(not= ?resolution :resolution/heuristic)]]
                    [source])]
    (vec (concat
          (map (fn [[id name line resolution]]
                 {:id id :name name :line line :resolution resolution}) resolved)
          (map (fn [[target line resolution]]
                 {:target target :line line :resolution resolution}) unresolved)))))

(defn effects [graph]
  (->> (store/query
        graph
        '[:find ?kind ?symbol-id ?symbol-name ?path ?line ?detail ?confidence
          :where [?effect :effect/kind ?kind]
                 [?effect :effect/symbol ?symbol]
                 [?symbol :symbol/id ?symbol-id]
                 [?symbol :symbol/qualified-name ?symbol-name]
                 [?symbol :symbol/file ?file]
                 [?file :file/path ?path]
                 [?effect :source/start-line ?line]
                 [?effect :effect/detail ?detail]
                 [?effect :effect/confidence ?confidence]]
        [])
       (mapv (fn [[kind id name path line detail confidence]]
               {:kind kind :symbol-id id :symbol name :file path :line line
                :detail detail :confidence confidence}))))

(defn unresolved [graph]
  (->> (store/query
        graph
        '[:find ?kind ?target ?from-id ?from-name ?path ?line ?resolution
          :where [?edge :edge/kind ?kind]
                 [?edge :edge/target-text ?target]
                 [?edge :edge/from ?from]
                 [?from :symbol/id ?from-id]
                 [?from :symbol/qualified-name ?from-name]
                 [?from :symbol/file ?file]
                 [?file :file/path ?path]
                 [?edge :source/start-line ?line]
                 [?edge :edge/resolution ?resolution]
                 [(not= ?resolution :resolution/exact)]
                 [(not= ?resolution :resolution/heuristic)]]
        [])
       (mapv (fn [[kind target id name path line resolution]]
               {:kind kind :target target :from-id id :from name
                :file path :line line :resolution resolution}))))

(def reachability-rules
  '[[(reachable ?from ?to)
     [?edge :edge/from ?from]
     [?edge :edge/to ?to]]
    [(reachable ?from ?to)
     [?edge :edge/from ?from]
     [?edge :edge/to ?middle]
     (reachable ?middle ?to)]])

(defn transitive-callees [graph source]
  (->> (store/query
        graph
        '[:find ?id ?name
          :in $ % ?source-id
          :where [?source :symbol/id ?source-id]
                 (reachable ?source ?target)
                 [?target :symbol/id ?id]
                 [?target :symbol/qualified-name ?name]]
        [reachability-rules source])
       (mapv (fn [[id name]] {:id id :name name}))))

(defn entry-points [graph]
  (let [called (set (store/query graph
                                 '[:find [?id ...]
                                   :where [?edge :edge/to ?symbol]
                                          [?symbol :symbol/id ?id]
                                          [?edge :edge/kind :edge.kind/calls]] []))]
    (->> (symbols graph "")
         (filter #(contains? #{:symbol.kind/function :symbol.kind/method} (:kind %)))
         (remove #(contains? called (:id %)))
         vec)))
