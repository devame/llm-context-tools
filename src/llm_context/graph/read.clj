(ns llm-context.graph.read
  "Focused, snapshot-consistent projections of the Datalevin graph.

  Functions in this namespace accept an immutable Datalevin database value.
  Selection, joins, aggregation, and graph-neighborhood discovery stay in
  Datalevin; callers receive only bounded domain records."
  (:require [datalevin.core :as d]))

(defn entity-counts [db]
  (into {}
        (map (fn [[type count]] [type count]))
        (d/q '[:find ?type (count ?entity)
               :where [?entity :entity/type ?type]]
             db)))

(defn grouped-counts [db attribute]
  (into (sorted-map)
        (map (fn [[value count]] [value count]))
        (d/q '[:find ?value (count ?entity)
               :in $ ?attribute
               :where [?entity ?attribute ?value]]
             db attribute)))

(defn any-file? [db]
  (boolean
   (d/q '[:find ?file .
          :where [?file :file/id _]]
        db)))

(defn any-entity? [db]
  (boolean
   (d/q '[:find ?entity .
          :where [?entity :entity/type _]]
        db)))

(def symbol-pull
  '[:symbol/id
    :symbol/name
    :symbol/qualified-name
    :symbol/kind
    :symbol/signature
    :symbol/doc
    :source/start-line
    {:symbol/file [:file/id :file/path]}])

(defn- symbol-record [entity]
  (when entity
    (let [file (:symbol/file entity)]
      (cond-> {:id (:symbol/id entity)
               :name (:symbol/name entity)
               :qualified-name (:symbol/qualified-name entity)
               :kind (:symbol/kind entity)
               :file (:file/path file)
               :file-id (:file/id file)
               :line (:source/start-line entity)}
        (:symbol/signature entity)
        (assoc :signature (:symbol/signature entity))

        (:symbol/doc entity)
        (assoc :doc (:symbol/doc entity))))))

(defn symbol-by-id [db id]
  (some-> (d/q '[:find ?symbol .
                 :in $ ?id
                 :where [?symbol :symbol/id ?id]]
               db id)
          (d/pull db symbol-pull)
          symbol-record))

(defn exact-symbols [db term]
  (->> (d/q '[:find [?symbol ...]
              :in $ ?term
              :where
              (or [?symbol :symbol/id ?term]
                  [?symbol :symbol/name ?term]
                  [?symbol :symbol/qualified-name ?term])]
            db term)
       (map #(d/pull db symbol-pull %))
       (map symbol-record)
       (sort-by (juxt :qualified-name :id))
       vec))

(defn symbols-by-ids [db ids]
  (if (seq ids)
    (->> (d/q '[:find ?id ?symbol
                :in $ [?id ...]
                :where [?symbol :symbol/id ?id]]
              db (vec ids))
         (map (fn [[id eid]] [id (symbol-record (d/pull db symbol-pull eid))]))
         (into {}))
    {}))

(defn substring-symbol-ids
  "Evaluate the compatibility substring predicate inside Datalevin so symbol
  rows that do not match are never materialized in application memory."
  [db needle]
  (if (empty? needle)
    []
    (d/q '[:find [?id ...]
           :in $ ?needle
           :where
           [?symbol :symbol/id ?id]
           [?symbol :symbol/name ?name]
           [?symbol :symbol/qualified-name ?qualified]
           [(clojure.string/lower-case ?name) ?lower-name]
           [(clojure.string/lower-case ?qualified) ?lower-qualified]
           (or [(clojure.string/includes? ?lower-name ?needle)]
               [(clojure.string/includes? ?lower-qualified ?needle)])]
         db needle)))

(defn neighbor-ids
  "Return symbol IDs adjacent to a bounded frontier in either direction."
  [db frontier-ids]
  (if (seq frontier-ids)
    (let [frontier (vec frontier-ids)
          outgoing
          (d/q '[:find [?id ...]
                 :in $ [?frontier-id ...]
                 :where
                 [?frontier :symbol/id ?frontier-id]
                 [?edge :edge/from ?frontier]
                 [?edge :edge/to ?neighbor]
                 [?neighbor :symbol/id ?id]]
               db frontier)
          incoming
          (d/q '[:find [?id ...]
                 :in $ [?frontier-id ...]
                 :where
                 [?frontier :symbol/id ?frontier-id]
                 [?edge :edge/to ?frontier]
                 [?edge :edge/from ?neighbor]
                 [?neighbor :symbol/id ?id]]
               db frontier)]
      (into (sorted-set) (concat outgoing incoming)))
    #{}))

(defn edges-for-symbols
  "Return edges originating in selected symbols when their resolved target is
  also selected, plus unresolved outgoing edges."
  [db symbol-ids]
  (if-not (seq symbol-ids)
    []
    (let [ids (vec symbol-ids)
          resolved
          (d/q '[:find ?id ?kind ?from-id ?to-id ?target ?resolution ?line
                 :in $ ?selected
                 :where
                 [?from :symbol/id ?from-id]
                 [(contains? ?selected ?from-id)]
                 [?edge :edge/from ?from]
                 [?edge :edge/id ?id]
                 [?edge :edge/kind ?kind]
                 [?edge :edge/to ?to]
                 [?to :symbol/id ?to-id]
                 [(contains? ?selected ?to-id)]
                 [?edge :edge/target-text ?target]
                 [?edge :edge/resolution ?resolution]
                 [?edge :source/start-line ?line]]
               db (set ids))
          unresolved
          (d/q '[:find ?id ?kind ?from-id ?target ?resolution ?line
                 :in $ ?selected
                 :where
                 [?from :symbol/id ?from-id]
                 [(contains? ?selected ?from-id)]
                 [?edge :edge/from ?from]
                 [?edge :edge/id ?id]
                 [?edge :edge/kind ?kind]
                 [?edge :edge/target-text ?target]
                 [?edge :edge/resolution ?resolution]
                 [?edge :source/start-line ?line]
                 [(missing? $ ?edge :edge/to)]]
               db (set ids))]
      (->> (concat
            (map (fn [[id kind from to target resolution line]]
                   {:id id :kind kind :from from :to to
                    :target-text target :resolution resolution :line line})
                 resolved)
            (map (fn [[id kind from target resolution line]]
                   {:id id :kind kind :from from
                    :target-text target :resolution resolution :line line})
                 unresolved))
           (sort-by (juxt :from :line :id))
           vec))))

(defn effects-for-symbols [db symbol-ids]
  (if-not (seq symbol-ids)
    []
    (->> (d/q '[:find ?kind ?symbol-id ?detail ?confidence ?path ?line
                :in $ ?selected
                :where
                [?symbol :symbol/id ?symbol-id]
                [(contains? ?selected ?symbol-id)]
                [?effect :effect/symbol ?symbol]
                [?effect :effect/kind ?kind]
                [?effect :effect/detail ?detail]
                [?effect :effect/confidence ?confidence]
                [?symbol :symbol/file ?file]
                [?file :file/path ?path]
                [?effect :source/start-line ?line]]
              db (set symbol-ids))
         (map (fn [[kind symbol-id detail confidence file line]]
                {:kind kind :symbol-id symbol-id :detail detail
                 :confidence confidence :file file :line line}))
         (sort-by (juxt :symbol-id :line :kind))
         vec)))

(defn entry-points [db]
  (->> (d/q '[:find ?id ?name ?qualified ?kind ?path ?line
              :in $ ?kinds
              :where
              [?symbol :symbol/id ?id]
              [?symbol :symbol/name ?name]
              [?symbol :symbol/qualified-name ?qualified]
              [?symbol :symbol/kind ?kind]
              [(contains? ?kinds ?kind)]
              [?symbol :symbol/file ?file]
              [?file :file/path ?path]
              [?symbol :source/start-line ?line]
              (not-join [?symbol]
                        [?edge :edge/to ?symbol]
                        [?edge :edge/kind :edge.kind/calls])]
            db #{:symbol.kind/function :symbol.kind/method})
       (map (fn [[id name qualified kind file line]]
              {:id id :name name :qualified-name qualified :kind kind
               :file file :line line}))
       (sort-by (juxt :qualified-name :id))
       vec))

(defn semantic-candidate-state
  "Return freshness state only for bounded semantic candidate symbol IDs."
  [db provider symbol-ids]
  (if-not (seq symbol-ids)
    {:indexed {} :jobs #{} :dirty-files #{}}
    (let [ids (set symbol-ids)
          indexed-rows
          (d/q '[:find ?symbol-id ?file-id ?hash ?revision ?version
                 :in $ ?provider ?ids
                 :where
                 [?record :semantic.indexed/provider ?provider]
                 [?record :semantic.indexed/symbol-id ?symbol-id]
                 [(contains? ?ids ?symbol-id)]
                 [?record :semantic.indexed/file-id ?file-id]
                 [?record :semantic.indexed/document-hash ?hash]
                 [?record :semantic.indexed/model-revision ?revision]
                 [?record :semantic.indexed/document-version ?version]]
               db provider ids)
          indexed
          (into {}
                (map (fn [[symbol-id file-id hash revision version]]
                       [symbol-id
                        {:semantic.indexed/symbol-id symbol-id
                         :semantic.indexed/file-id file-id
                         :semantic.indexed/document-hash hash
                         :semantic.indexed/model-revision revision
                         :semantic.indexed/document-version version}]))
                indexed-rows)
          jobs
          (set
           (d/q '[:find [?symbol-id ...]
                  :in $ ?provider ?ids
                  :where
                  [?job :semantic.job/provider ?provider]
                  [?job :semantic.job/symbol-id ?symbol-id]
                  [(contains? ?ids ?symbol-id)]]
                db provider ids))
          files (conj (set (map second indexed-rows)) "project:*")
          dirty-files
          (set
           (d/q '[:find [?file-id ...]
                  :in $ ?provider ?files
                  :where
                  [?dirty :semantic.dirty/provider ?provider]
                  [?dirty :semantic.dirty/file-id ?file-id]
                  [(contains? ?files ?file-id)]]
                db provider files))]
      {:indexed indexed :jobs jobs :dirty-files dirty-files})))

(defn semantic-counts [db provider]
  (let [indexed
        (or (d/q '[:find (count ?record) .
                   :in $ ?provider
                   :where [?record :semantic.indexed/provider ?provider]]
                 db provider)
            0)
        by-status
        (into {}
              (d/q '[:find ?status (count ?job)
                     :in $ ?provider
                     :where
                     [?job :semantic.job/provider ?provider]
                     [?job :semantic.job/status ?status]]
                   db provider))
        oldest
        (d/q '[:find (min ?updated) .
               :in $ ?provider
               :where
               [?job :semantic.job/provider ?provider]
               [?job :semantic.job/status :pending]
               [?job :semantic.job/updated-at ?updated]]
             db provider)
        dirty
        (or (d/q '[:find (count ?marker) .
                   :in $ ?provider
                   :where [?marker :semantic.dirty/provider ?provider]]
                 db provider)
            0)]
    {:indexed indexed
     :pending (get by-status :pending 0)
     :leased (get by-status :leased 0)
     :failed (get by-status :failed 0)
     :oldest-pending-at oldest
     :dirty dirty}))
