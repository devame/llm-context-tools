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

(defn files-by-path [db]
  (let [semantic (into {}
                       (d/q '[:find ?id ?semantic
                              :where
                              [?file :file/id ?id]
                              [?file :file/semantic-hash ?semantic]]
                            db))]
    (into {}
          (map (fn [[path id hash]]
                 [path {:id id :hash hash
                        :semantic-hash (get semantic id "")}]))
          (d/q '[:find ?path ?id ?hash
                 :where
                 [?file :file/path ?path]
                 [?file :file/id ?id]
                 [?file :file/content-hash ?hash]]
               db))))

(defn file-hashes [db]
  (into (sorted-map)
        (d/q '[:find ?id ?hash
               :where
               [?file :file/id ?id]
               [?file :file/content-hash ?hash]]
             db)))

(defn file-path [db file-id]
  (d/q '[:find ?path .
         :in $ ?id
         :where
         [?file :file/id ?id]
         [?file :file/path ?path]]
       db file-id))

(def symbol-pull
  '[:symbol/id
    :symbol/name
    :symbol/qualified-name
    :symbol/kind
    :symbol/platform
    :symbol/analyzer
    :symbol/scope
    :symbol/role
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
               :platform (:symbol/platform entity)
               :analyzer (:symbol/analyzer entity)
               :scope (:symbol/scope entity)
               :role (:symbol/role entity)
               :file (:file/path file)
               :file-id (:file/id file)
               :line (:source/start-line entity)}
        (:symbol/signature entity)
        (assoc :signature (:symbol/signature entity))

        (:symbol/doc entity)
        (assoc :doc (:symbol/doc entity))))))

(defn symbol-by-id [db id]
  (when-let [eid (d/q '[:find ?symbol .
                        :in $ ?id
                        :where [?symbol :symbol/id ?id]]
                      db id)]
    (symbol-record (d/pull db symbol-pull eid))))

(defn exact-symbols
  ([db term]
   (let [eids
         (d/q '[:find [?symbol ...]
                :in $ ?term
                :where
                (or [?symbol :symbol/id ?term]
                    [?symbol :symbol/name ?term]
                    [?symbol :symbol/qualified-name ?term])]
              db term)]
     (->> (if (seq eids) (d/pull-many db symbol-pull eids) [])
          (map symbol-record)
          (sort-by (juxt :qualified-name :id))
          vec)))
  ([db term limit]
   (let [rows
         (d/q
          (conj
           '[:find ?qualified ?id ?symbol
             :in $ ?term
             :where
             (or [?symbol :symbol/id ?term]
                 [?symbol :symbol/name ?term]
                 [?symbol :symbol/qualified-name ?term])
             [?symbol :symbol/id ?id]
             [?symbol :symbol/qualified-name ?qualified]
             :order-by [?qualified :asc ?id :asc]
             :limit]
           (long limit))
          db term)
         eids (mapv #(nth % 2) rows)]
     (mapv symbol-record
           (if (seq eids) (d/pull-many db symbol-pull eids) [])))))

(defn symbols-by-ids [db ids]
  (if (seq ids)
    (let [eids (d/q '[:find [?symbol ...]
                      :in $ [?id ...]
                      :where [?symbol :symbol/id ?id]]
                    db (vec ids))]
      (->> (d/pull-many db symbol-pull eids)
           (map symbol-record)
           (map (juxt :id identity))
           (into {})))
    {}))

(defn aggregates-for-symbols
  "Return deterministic aggregate evidence keyed by canonical owner symbol ID.
  Memberships are source facts; completeness is analyzer-owned and must be
  checked before treating the members as an exhaustive inventory."
  [db symbol-ids]
  (if-not (seq symbol-ids)
    {}
    (let [aggregate-rows
          (d/q '[:find ?owner-id ?aggregate-id ?name ?kind ?completeness
                        ?member-count ?member-kind ?path ?line
                 :in $ [?owner-id ...]
                 :where
                 [?owner :symbol/id ?owner-id]
                 [?aggregate :aggregate/owner ?owner]
                 [?aggregate :aggregate/id ?aggregate-id]
                 [?aggregate :aggregate/name ?name]
                 [?aggregate :aggregate/kind ?kind]
                 [?aggregate :aggregate/completeness ?completeness]
                 [?aggregate :aggregate/member-count ?member-count]
                 [?aggregate :aggregate/member-kind ?member-kind]
                 [?aggregate :aggregate/file ?file]
                 [?file :file/path ?path]
                 [(get-else $ ?aggregate :source/start-line 1) ?line]]
               db (vec symbol-ids))
          aggregate-ids (mapv second aggregate-rows)
          member-rows
          (if (seq aggregate-ids)
            (d/q '[:find ?aggregate-id ?ordinal ?key ?value ?value-kind
                          ?evidence
                   :in $ [?aggregate-id ...]
                   :where
                   [?aggregate :aggregate/id ?aggregate-id]
                   [?member :membership/aggregate ?aggregate]
                   [?member :membership/ordinal ?ordinal]
                   [(get-else $ ?member :membership/key "") ?key]
                   [?member :membership/value ?value]
                   [?member :membership/value-kind ?value-kind]
                   [?member :membership/evidence ?evidence]]
                 db aggregate-ids)
            [])
          members-by-aggregate
          (->> member-rows
               (map (fn [[aggregate-id ordinal key value value-kind evidence]]
                      [aggregate-id
                       {:ordinal ordinal :key (when (seq key) key)
                        :value value :value-kind value-kind
                        :evidence evidence}]))
               (group-by first)
               (into {}
                     (map (fn [[aggregate-id rows]]
                            [aggregate-id
                             (->> rows (map second)
                                  (sort-by (juxt :ordinal :value)) vec)]))))]
      (->> aggregate-rows
           (map (fn [[owner-id aggregate-id name kind completeness
                      member-count member-kind path line]]
                  [owner-id
                   {:id aggregate-id :name name :kind kind
                    :completeness completeness :member-count member-count
                    :member-kind member-kind :file path :line line
                    :members (get members-by-aggregate aggregate-id [])}]))
           (group-by first)
           (into {}
                 (map (fn [[owner-id rows]]
                        [owner-id
                         (->> rows (map second) (sort-by :id) vec)])))))))

(defn containers-for-symbols
  "Return exact namespace/module membership for selected container symbols.
  The result is a deterministic coarse summary backed only by canonical
  `contains` edges."
  [db symbol-ids]
  (if-not (seq symbol-ids)
    []
    (let [rows
          (d/q '[:find ?container-id ?container-name ?container-kind
                        ?member-id ?member-name ?qualified ?member-kind
                        ?path ?line
                 :in $ [?container-id ...]
                 :where
                 [?container :symbol/id ?container-id]
                 [?container :symbol/qualified-name ?container-name]
                 [?container :symbol/kind ?container-kind]
                 [(contains? #{:symbol.kind/namespace :symbol.kind/module}
                             ?container-kind)]
                 [?edge :edge/from ?container]
                 [?edge :edge/kind :edge.kind/contains]
                 [?edge :edge/to ?member]
                 [?member :symbol/id ?member-id]
                 [?member :symbol/name ?member-name]
                 [?member :symbol/qualified-name ?qualified]
                 [?member :symbol/kind ?member-kind]
                 [?member :symbol/file ?file]
                 [?file :file/path ?path]
                 [(get-else $ ?member :source/start-line 1) ?line]]
               db (vec symbol-ids))]
      (->> rows
           (group-by first)
           (map (fn [[container-id entries]]
                  (let [[_ container-name container-kind] (first entries)]
                    {:id container-id :name container-name
                     :kind container-kind :completeness :complete-static
                     :members
                     (->> entries
                          (map (fn [[_ _ _ member-id member-name qualified
                                     member-kind path line]]
                                 {:id member-id :name member-name
                                  :qualified-name qualified :kind member-kind
                                  :file path :line line}))
                          (sort-by (juxt :qualified-name :id)) vec)})))
           (sort-by :name)
           vec))))

(defn adjacent-exact
  "Return a bounded exact-edge frontier over symbol and topic IDs. Filtering
  uses Datalevin's indexed reverse references so traversal does not repeatedly
  plan four Datalog joins for every visited node."
  [db frontier-ids {:keys [directions edge-kinds limit]
                    :or {directions #{:outgoing :incoming}
                         limit 200}}]
  (if-not (and (seq frontier-ids) (pos? limit))
    []
    (let [kinds (set edge-kinds)
          node-id (fn [entity]
                    (or (:symbol/id entity) (:topic/id entity)))
          node-type (fn [entity]
                      (if (:topic/id entity) :topic :symbol))
          edge-record
          (fn [direction edge]
            (let [from (:edge/from edge)
                  to (:edge/to edge)]
              {:edge-id (:edge/id edge)
               :kind (:edge/kind edge)
               :from (node-id from)
               :to (node-id to)
               :direction direction
               :from-type (node-type from)
               :to-type (node-type to)
               :evidence (:edge/evidence edge)
               :line (or (:source/start-line edge) 1)
               ::resolution (:edge/resolution edge)}))
          rows
          (mapcat
           (fn [frontier-id]
             (when-let [node (or (d/entity db [:symbol/id frontier-id])
                                 (d/entity db [:topic/id frontier-id]))]
               (concat
                (when (directions :outgoing)
                  (map #(edge-record :outgoing %) (:edge/_from node)))
                (when (directions :incoming)
                  (map #(edge-record :incoming %) (:edge/_to node))))))
           frontier-ids)]
      (->> rows
           (filter #(and (= :resolution/exact (::resolution %))
                         (contains? kinds (:kind %))))
           (sort-by (juxt :kind :from :to :edge-id :direction))
           (take limit)
           (mapv #(dissoc % ::resolution))))))

(defn outgoing-call-targets
  "Return at most limit distinct exact call targets from a frontier, excluding
  already visited symbol IDs. Results are stable by qualified name and ID."
  [db source-ids visited limit]
  (if-not (and (seq source-ids) (pos? limit))
    []
    (mapv
     (fn [[id qualified]] {:id id :qualified-name qualified})
     (d/q
      (conj
       '[:find ?target-id ?qualified
         :in $ [?source-id ...] ?visited
         :where
         [?source :symbol/id ?source-id]
         [?edge :edge/from ?source]
         [?edge :edge/kind :edge.kind/calls]
         [?edge :edge/resolution :resolution/exact]
         [?edge :edge/to ?target]
         [?target :symbol/id ?target-id]
         [?target :symbol/qualified-name ?qualified]
         [(not (contains? ?visited ?target-id))]
         :order-by [?qualified :asc ?target-id :asc]
         :limit]
       (long limit))
      db (vec source-ids) visited))))

(defn topics-by-ids [db ids]
  (if-not (seq ids)
    {}
    (->> (d/q '[:find ?id ?kind ?key ?platform
                :in $ [?id ...]
                :where
                [?topic :topic/id ?id]
                [?topic :topic/kind ?kind]
                [?topic :topic/key ?key]
                [?topic :topic/platform ?platform]]
              db (vec ids))
         (map (fn [[id kind key platform]]
                [id {:id id :kind kind :key key :platform platform}]))
         (into {}))))

(defn reference-summary [db symbol-ids]
  (if-not (seq symbol-ids)
    {}
    (into
     (sorted-map)
     (d/q '[:find ?classification (count ?reference)
            :in $ [?symbol-id ...]
            :where
            [?symbol :symbol/id ?symbol-id]
            [?reference :reference/symbol ?symbol]
            [?reference :reference/classification ?classification]]
          db (vec symbol-ids)))))

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

(defn summary-entry-points
  "Return a stable, database-bounded entry-point sample for human summaries."
  [db]
  (->> (d/q '[:find ?id ?qualified ?path ?line
              :in $ ?kinds
              :where
              [?symbol :symbol/id ?id]
              [?symbol :symbol/qualified-name ?qualified]
              [?symbol :symbol/kind ?kind]
              [(contains? ?kinds ?kind)]
              [?symbol :symbol/file ?file]
              [?file :file/path ?path]
              [?symbol :source/start-line ?line]
              (not-join [?symbol]
                        [?edge :edge/to ?symbol]
                        [?edge :edge/kind :edge.kind/calls])
              :order-by [?qualified :asc]
              :limit 20]
            db #{:symbol.kind/function :symbol.kind/method})
       (mapv (fn [[id qualified file line]]
               {:id id :qualified-name qualified :file file :line line}))))

(defn summary-effects
  "Return a stable, database-bounded effect sample for human summaries."
  [db]
  (->> (d/q '[:find ?kind ?symbol-name ?path ?line
              :where
              [?effect :effect/kind ?kind]
              [?effect :effect/symbol ?symbol]
              [?symbol :symbol/qualified-name ?symbol-name]
              [?symbol :symbol/file ?file]
              [?file :file/path ?path]
              [?effect :source/start-line ?line]
              :order-by [?symbol-name :asc ?line :asc ?kind :asc]
              :limit 20]
            db)
       (mapv (fn [[kind symbol file line]]
               {:kind kind :symbol symbol :file file :line line}))))

(defn unresolved-reference-count [db]
  (or
   (d/q '[:find (count ?reference) .
          :where
          [?reference :reference/classification ?classification]
          [(contains? #{:unresolved :ambiguous} ?classification)]]
        db)
   0))

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
  (let [entity-counts
        (into {}
              (d/q '[:find ?type (count ?entity)
                     :where [?entity :entity/type ?type]]
                   db))
        desired
        (or (d/q '[:find (count ?symbol) .
                   :where
                   [?symbol :symbol/id _]
                   [?symbol :symbol/indexable? true]]
                 db)
            0)
        indexed-current
        (or (d/q '[:find (count ?record) .
                   :in $ ?provider
                   :where
                   [?record :semantic.indexed/provider ?provider]
                   [?record :semantic.indexed/symbol-id ?symbol-id]
                   [?symbol :symbol/id ?symbol-id]
                   [?symbol :symbol/indexable? true]]
                 db provider)
            0)
        indexed
        (or (d/q '[:find (count ?record) .
                   :in $ ?provider
                   :where [?record :semantic.indexed/provider ?provider]]
                 db provider)
            0)
        status-rows
        (d/q '[:find ?status (count ?job) (min ?updated)
               :in $ ?provider
               :where
               [?job :semantic.job/provider ?provider]
               [?job :semantic.job/status ?status]
               [?job :semantic.job/updated-at ?updated]]
             db provider)
        by-status (into {} (map (fn [[status count _]] [status count]))
                        status-rows)
        oldest (some (fn [[status _ updated]]
                       (when (= :pending status) updated))
                     status-rows)
        accepted
        (or (d/q '[:find (count ?job) .
                   :in $ ?provider
                   :where
                   [?job :semantic.job/provider ?provider]
                   [?job :semantic.job/status :leased]
                   [?job :semantic.job/accepted-at _]]
                 db provider)
            0)
        dirty
        (or (d/q '[:find (count ?marker) .
                   :in $ ?provider
                   :where [?marker :semantic.dirty/provider ?provider]]
                 db provider)
            0)]
    {:desired desired
     :indexed indexed
     :indexed-current indexed-current
     :pending (get by-status :pending 0)
     :leased (get by-status :leased 0)
     :accepted accepted
     :failed (get by-status :failed 0)
     :oldest-pending-at oldest
     :dirty dirty
     :aggregate-analysis
     {:aggregates (get entity-counts :entity.type/aggregate 0)
      :memberships (get entity-counts :entity.type/membership 0)}}))

(defn semantic-indexed-for-file [db provider file-id]
  (let [eids (d/q '[:find [?record ...]
                    :in $ ?provider ?file-id
                    :where
                    [?record :semantic.indexed/provider ?provider]
                    [?record :semantic.indexed/file-id ?file-id]]
                  db provider file-id)]
    (->> (if (seq eids) (d/pull-many db '[*] eids) [])
         (map (juxt :semantic.indexed/symbol-id identity))
         (into {}))))

(defn semantic-jobs-for-file [db provider file-id]
  (let [eids (d/q '[:find [?job ...]
                    :in $ ?provider ?file-id
                    :where
                    [?job :semantic.job/provider ?provider]
                    [?job :semantic.job/file-id ?file-id]]
                  db provider file-id)]
    (->> (if (seq eids) (d/pull-many db '[*] eids) [])
         (map (juxt :semantic.job/symbol-id identity))
         (into {}))))

(defn semantic-indexed-file-ids [db provider]
  (set
   (d/q '[:find [?file-id ...]
          :in $ ?provider
          :where
          [?record :semantic.indexed/provider ?provider]
          [?record :semantic.indexed/file-id ?file-id]]
        db provider)))

(defn symbol-at-point [db file-path line column]
  (->> (d/q '[:find ?id ?name ?qualified ?sl ?sc ?el ?ec
              :in $ ?path ?line ?column
              :where
              [?file :file/path ?path]
              [?symbol :symbol/file ?file]
              [?symbol :symbol/id ?id]
              [?symbol :symbol/name ?name]
              [?symbol :symbol/qualified-name ?qualified]
              [?symbol :source/start-line ?sl]
              [?symbol :source/start-column ?sc]
              [?symbol :source/end-line ?el]
              [?symbol :source/end-column ?ec]
              [(<= ?sl ?line)]
              [(>= ?el ?line)]]
            db file-path line column)
       (filter (fn [[_ _ _ sl sc el ec]]
                 (and (not (neg? (compare [line column] [sl sc])))
                      (not (pos? (compare [line column] [el ec]))))))
       (sort-by (fn [[_ _ _ sl _ el _]]
                  (- (* 1000000 el) sl)))
       first
       ((fn [row]
          (when row
            (let [[id name qualified] row]
              {:symbol-id id :name name :qualified-name qualified}))))))
