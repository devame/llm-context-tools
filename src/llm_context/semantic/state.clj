(ns llm-context.semantic.state
  "Durable operational state for asynchronous semantic indexing.

  All identities are stable strings rather than refs to graph entities. A
  delete job must remain meaningful after its symbol has been retracted from
  the authoritative graph."
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [llm-context.graph.read :as graph-read]
            [llm-context.store :as store]))

(def job-statuses #{:pending :leased :failed})
(def job-operations #{:upsert :delete})
(def dirty-operations #{:upsert :delete :reconcile-all})
(def watermark-states #{:idle :indexing :ready :degraded :failed})
(def max-error-length 2000)

(defn dirty-id [provider file-id]
  (str (name provider) "|" file-id))

(defn job-id [provider symbol-id]
  (str (name provider) "|" symbol-id))

(defn indexed-id [provider symbol-id]
  (str (name provider) "|" symbol-id))

(defn watermark-id [provider]
  (name provider))

(defn- connection [graph]
  (or (:connection graph)
      (throw (ex-info "Semantic state requires a Datalevin store"
                      {:store (type graph)}))))

(defn- require-key! [record key predicate message]
  (when-not (predicate (get record key))
    (throw (ex-info message {:record record :key key}))))

(defn- validate-dirty! [{:keys [provider file-id operation created-at] :as marker}]
  (require-key! marker :provider keyword? "Semantic dirty provider must be a keyword")
  (require-key! marker :file-id #(and (string? %) (seq %))
                "Semantic dirty file ID must be a non-empty string")
  (require-key! marker :operation dirty-operations
                "Unknown semantic dirty operation")
  (require-key! marker :created-at nat-int?
                "Semantic dirty creation time must be a non-negative integer")
  (when (and (= :upsert operation)
             (not (and (string? (:file-hash marker))
                       (str/starts-with? (:file-hash marker) "sha256:"))))
    (throw (ex-info "Semantic upsert dirty marker requires a content hash"
                    {:marker marker})))
  marker)

(defn dirty-entity
  "Validate and convert a public dirty marker into its Datalevin entity."
  [marker]
  (let [{:keys [provider file-id file-hash operation created-at]}
        (validate-dirty! marker)]
    (cond-> {:semantic.dirty/id (dirty-id provider file-id)
             :semantic.dirty/provider provider
             :semantic.dirty/file-id file-id
             :semantic.dirty/operation operation
             :semantic.dirty/created-at created-at}
      file-hash (assoc :semantic.dirty/file-hash file-hash))))

(defn- validate-job! [{:keys [provider symbol-id file-id operation
                               document-hash available-at updated-at] :as job}]
  (require-key! job :provider keyword? "Semantic job provider must be a keyword")
  (require-key! job :symbol-id #(and (string? %) (seq %))
                "Semantic job symbol ID must be a non-empty string")
  (require-key! job :file-id #(and (string? %) (seq %))
                "Semantic job file ID must be a non-empty string")
  (require-key! job :operation job-operations "Unknown semantic job operation")
  (require-key! job :available-at nat-int?
                "Semantic job availability must be a non-negative integer")
  (require-key! job :updated-at nat-int?
                "Semantic job update time must be a non-negative integer")
  (when (and (= :upsert operation)
             (not (and (string? document-hash)
                       (str/starts-with? document-hash "sha256:"))))
    (throw (ex-info "Semantic upsert job requires a document hash" {:job job})))
  job)

(defn- validate-indexed!
  [{:keys [provider symbol-id file-id document-hash model-revision
           document-version chunk-count updated-at] :as indexed}]
  (require-key! indexed :provider keyword?
                "Semantic indexed provider must be a keyword")
  (doseq [[key value message] [[:symbol-id symbol-id "symbol ID"]
                               [:file-id file-id "file ID"]
                               [:document-hash document-hash "document hash"]
                               [:model-revision model-revision "model revision"]]]
    (require-key! indexed key #(and (string? %) (seq %))
                  (str "Semantic indexed " message " must be a non-empty string")))
  (require-key! indexed :document-version pos-int?
                "Semantic indexed document version must be positive")
  (require-key! indexed :chunk-count pos-int?
                "Semantic indexed chunk count must be positive")
  (require-key! indexed :updated-at nat-int?
                "Semantic indexed update time must be non-negative")
  indexed)

(defn- pull-many [db eids]
  (if (seq eids)
    (d/pull-many db '[*] eids)
    []))

(defn- eid-by [db attribute value]
  (d/q '[:find ?entity .
         :in $ ?attribute ?value
         :where [?entity ?attribute ?value]]
       db attribute value))

(defn- retract-present [entity attributes]
  (keep (fn [attribute]
          (when-let [value (get entity attribute)]
            [:db/retract (:db/id entity) attribute value]))
        attributes))

(defprotocol SemanticState
  (mark-dirty! [graph marker]
    "Create or replace one coalesced dirty-file marker.")
  (dirty-records [graph provider]
    "Return dirty markers for a provider in deterministic order.")
  (clear-dirty! [graph provider file-id]
    "Remove a dirty marker after it has been reconciled.")
  (record-dirty-diagnostic! [graph provider file-id timestamp message]
    "Persist the latest deferred reconciliation diagnostic.")
  (enqueue-job! [graph job]
    "Create or supersede one coalesced symbol job.")
  (cancel-job! [graph provider symbol-id]
    "Remove queued or leased work that is no longer desired.")
  (apply-reconciliation! [graph plan]
    "Apply one file's precomputed job changes and dirty-marker clear atomically.")
  (lease-jobs! [graph provider owner now lease-ms limit]
    "Atomically lease up to limit currently available jobs.")
  (renew-job-lease! [graph job-id owner now lease-ms]
    "Extend a worker-owned lease during a long model or visibility operation.")
  (renew-job-leases! [graph job-ids owner now lease-ms]
    "Extend current worker-owned leases in one conditional transaction.")
  (mark-jobs-accepted! [graph job-ids owner accepted-at]
    "Record which currently leased jobs were accepted by the semantic provider.")
  (recover-expired-leases! [graph provider now]
    "Return expired leases to pending and report how many were recovered.")
  (complete-job! [graph completion]
    "Conditionally complete a worker-owned job and update indexed state.")
  (complete-jobs! [graph completions]
    "Conditionally complete worker-owned jobs in one transaction. Returns the
    set of job IDs that were still owned and completed.")
  (retry-job! [graph failure]
    "Conditionally release a worker-owned job for retry or mark it failed.")
  (job-records [graph provider]
    "Return all jobs for a provider in deterministic order.")
  (indexed-records [graph provider]
    "Return current derived-index records for a provider.")
  (put-indexed! [graph indexed]
    "Record an indexed symbol directly, primarily for reconciliation.")
  (semantic-summary [graph provider now]
    "Return queue, indexed, failure, and lag statistics.")
  (record-watermark! [graph watermark]
    "Update provider-level health and progress metadata.")
  (watermark [graph provider]
    "Return provider-level health and progress metadata, if present."))

(extend-type llm_context.store.DatalevinStore
  SemanticState

  (mark-dirty! [graph marker]
    (let [{:semantic.dirty/keys [provider file-id file-hash] :as entity}
          (dirty-entity marker)
          conn (connection graph)
          db (d/db conn)
          existing-eid (eid-by db :semantic.dirty/id
                               (dirty-id provider file-id))
          existing (when existing-eid (d/pull db '[*] existing-eid))]
      (d/transact!
       conn
       (vec
        (concat
         (when (and existing
                    (:semantic.dirty/file-hash existing)
                    (nil? file-hash))
           [[:db/retract existing-eid :semantic.dirty/file-hash
             (:semantic.dirty/file-hash existing)]])
         [entity])))
      marker))

  (dirty-records [graph provider]
    (let [db (store/database graph)
          eids (d/q '[:find [?entity ...]
                      :in $ ?provider
                      :where [?entity :semantic.dirty/provider ?provider]]
                    db provider)]
      (->> (pull-many db eids)
           (sort-by (juxt :semantic.dirty/created-at :semantic.dirty/id))
           vec)))

  (clear-dirty! [graph provider file-id]
    (let [conn (connection graph)
          db (d/db conn)]
      (when-let [eid (eid-by db :semantic.dirty/id (dirty-id provider file-id))]
        (d/transact! conn [[:db/retractEntity eid]])
        true)))

  (record-dirty-diagnostic! [graph provider file-id timestamp message]
    (let [conn (connection graph)
          db (d/db conn)]
      (when-let [eid (eid-by db :semantic.dirty/id
                             (dirty-id provider file-id))]
        (d/transact!
         conn
         [{:db/id eid
           :semantic.dirty/last-error-at timestamp
           :semantic.dirty/last-error
           (subs (str message)
                 0 (min max-error-length (count (str message))))}])
        true)))

  (enqueue-job! [graph job]
    (let [{:keys [provider symbol-id file-id operation document-hash
                  available-at updated-at]} (validate-job! job)
          conn (connection graph)
          id (job-id provider symbol-id)
          db (d/db conn)
          eid (eid-by db :semantic.job/id id)
          existing (when eid (d/pull db '[*] eid))
          same? (and existing
                     (= operation (:semantic.job/operation existing))
                     (= document-hash (:semantic.job/document-hash existing))
                     (contains? #{:pending :leased}
                                (:semantic.job/status existing)))]
      (if same?
        existing
        (let [entity (cond-> {:semantic.job/id id
                              :semantic.job/provider provider
                              :semantic.job/symbol-id symbol-id
                              :semantic.job/file-id file-id
                              :semantic.job/operation operation
                              :semantic.job/status :pending
                              :semantic.job/attempts 0
                              :semantic.job/available-at available-at
                              :semantic.job/updated-at updated-at}
                       document-hash
                       (assoc :semantic.job/document-hash document-hash))
              obsolete (when existing
                         (retract-present existing
                                          [:semantic.job/document-hash
                                           :semantic.job/lease-owner
                                           :semantic.job/lease-until
                                           :semantic.job/accepted-at
                                           :semantic.job/last-error]))]
          (d/transact! conn (vec (concat obsolete [entity])))
          (d/pull (d/db conn) '[*]
                  (eid-by (d/db conn) :semantic.job/id id))))))

  (cancel-job! [graph provider symbol-id]
    (let [conn (connection graph)
          db (d/db conn)]
      (when-let [eid (eid-by db :semantic.job/id
                             (job-id provider symbol-id))]
        (d/transact! conn [[:db/retractEntity eid]])
        true)))

  (apply-reconciliation!
    [graph {:keys [provider file-id file-hash operation marker
                   pending indexed actions]}]
    (let [conn (connection graph)
          db (d/db conn)
          current-pending (graph-read/semantic-jobs-for-file
                           db provider file-id)
          current-indexed (graph-read/semantic-indexed-for-file
                           db provider file-id)
          current-file-hash
          (d/q '[:find ?hash .
                 :in $ ?file-id
                 :where
                 [?file :file/id ?file-id]
                 [?file :file/content-hash ?hash]]
               db file-id)
          marker-eid (:db/id marker)
          current-marker (when marker-eid (d/pull db '[*] marker-eid))
          marker-keys [:semantic.dirty/id :semantic.dirty/provider
                       :semantic.dirty/file-id :semantic.dirty/file-hash
                       :semantic.dirty/operation :semantic.dirty/created-at]
          marker-current?
          (if marker-eid
            (= (select-keys marker marker-keys)
               (select-keys current-marker marker-keys))
            true)
          file-current?
          (= file-hash current-file-hash)
          state-current?
          (and (= pending current-pending)
               (= indexed current-indexed))]
      (if-not (and marker-current? file-current? state-current?)
        {:applied? false :reason :stale-plan}
        (let [job-tx
              (mapcat
               (fn [{:keys [action job existing]}]
                 (case action
                   :cancel
                   (when existing [[:db/retractEntity (:db/id existing)]])

                   :enqueue
                   (let [{:keys [provider symbol-id file-id operation
                                 document-hash available-at updated-at]}
                         (validate-job! job)
                         same? (and existing
                                    (= operation
                                       (:semantic.job/operation existing))
                                    (= document-hash
                                       (:semantic.job/document-hash existing))
                                    (contains? #{:pending :leased}
                                               (:semantic.job/status existing)))
                         entity
                         (cond-> {:semantic.job/id (job-id provider symbol-id)
                                  :semantic.job/provider provider
                                  :semantic.job/symbol-id symbol-id
                                  :semantic.job/file-id file-id
                                  :semantic.job/operation operation
                                  :semantic.job/status :pending
                                  :semantic.job/attempts 0
                                  :semantic.job/available-at available-at
                                  :semantic.job/updated-at updated-at}
                           document-hash
                           (assoc :semantic.job/document-hash document-hash))]
                     (when-not same?
                       (concat
                        (when existing
                          (retract-present
                           existing [:semantic.job/document-hash
                                     :semantic.job/lease-owner
                                     :semantic.job/lease-until
                                     :semantic.job/accepted-at
                                     :semantic.job/last-error]))
                        [entity])))

                   (throw (ex-info "Unknown semantic reconciliation action"
                                   {:action action :file-id file-id}))))
               actions)
              tx (vec (concat job-tx
                              (when marker-eid
                                [[:db/retractEntity marker-eid]])))]
          (when (seq tx)
            (d/transact! conn tx))
          {:applied? true :actions (count actions)}))))

  (lease-jobs! [graph provider owner now lease-ms limit]
    (when-not (and (string? owner) (seq owner))
      (throw (ex-info "Semantic lease owner must be a non-empty string"
                      {:owner owner})))
    (when-not (and (nat-int? now) (pos-int? lease-ms) (pos-int? limit))
      (throw (ex-info "Semantic lease times and limit must be valid"
                      {:now now :lease-ms lease-ms :limit limit})))
    (let [conn (connection graph)
          candidates
          (d/q
           (conj
            '[:find ?entity ?available ?id
              :in $ ?provider ?now
              :where
              [?entity :semantic.job/provider ?provider]
              [?entity :semantic.job/status :pending]
              [?entity :semantic.job/available-at ?available]
              [(<= ?available ?now)]
              [?entity :semantic.job/id ?id]
              :order-by [?available :asc ?id :asc]
              :limit]
            (long limit))
           (d/db conn) provider now)
          eids (mapv first candidates)
          lease-one
          (fn [leased eid]
            (try
              (d/transact! conn
                           [[:db.fn/cas eid :semantic.job/status
                             :pending :leased]
                            {:db/id eid
                             :semantic.job/lease-owner owner
                             :semantic.job/lease-until (+ now lease-ms)
                             :semantic.job/updated-at now}])
              (conj leased (d/pull (d/db conn) '[*] eid))
              (catch clojure.lang.ExceptionInfo error
                (if (= :transact/cas (:error (ex-data error)))
                  leased
                  (throw error)))))]
      (if (empty? eids)
        []
        (let [tx (mapcat
                  (fn [eid]
                    [[:db.fn/cas eid :semantic.job/status :pending :leased]
                     {:db/id eid
                      :semantic.job/lease-owner owner
                      :semantic.job/lease-until (+ now lease-ms)
                      :semantic.job/updated-at now}])
                  eids)]
          (try
            (d/transact! conn (vec tx))
            (pull-many (d/db conn) eids)
            (catch clojure.lang.ExceptionInfo error
              (if (= :transact/cas (:error (ex-data error)))
                ;; Preserve the original per-job race isolation for callers
                ;; that lease without holding the project graph lock.
                (reduce lease-one [] eids)
                (throw error))))))))

  (renew-job-lease! [graph job-id owner now lease-ms]
    (let [conn (connection graph)
          db (d/db conn)
          eid (eid-by db :semantic.job/id job-id)
          job (when eid (d/pull db '[*] eid))]
      (when (and job
                 (= :leased (:semantic.job/status job))
                 (= owner (:semantic.job/lease-owner job)))
        (try
          (d/transact!
           conn
           [[:db.fn/cas eid :semantic.job/lease-owner owner owner]
            {:db/id eid
             :semantic.job/lease-until (+ now lease-ms)
             :semantic.job/updated-at now}])
          true
          (catch clojure.lang.ExceptionInfo error
            (if (= :transact/cas (:error (ex-data error)))
              false
              (throw error)))))))

  (renew-job-leases! [graph job-ids owner now lease-ms]
    (let [job-ids (vec (distinct job-ids))]
      (when-not (and (string? owner) (seq owner))
        (throw (ex-info "Semantic lease owner must be a non-empty string"
                        {:owner owner})))
      (when-not (and (nat-int? now) (pos-int? lease-ms))
        (throw (ex-info "Semantic lease times must be valid"
                        {:now now :lease-ms lease-ms})))
      (if (empty? job-ids)
        #{}
        (let [conn (connection graph)
              db (d/db conn)
              current
              (into {}
                    (map (fn [[id entity lease-owner status]]
                           [id [entity lease-owner status]]))
                    (d/q '[:find ?id ?entity ?lease-owner ?status
                           :in $ [?id ...]
                           :where
                           [?entity :semantic.job/id ?id]
                           [?entity :semantic.job/lease-owner ?lease-owner]
                           [?entity :semantic.job/status ?status]]
                         db job-ids))
              renewable
              (filterv (fn [job-id]
                         (let [[_ lease-owner status] (get current job-id)]
                           (and (= owner lease-owner) (= :leased status))))
                       job-ids)
              tx
              (mapcat (fn [job-id]
                        (let [[eid] (get current job-id)]
                          [[:db.fn/cas eid :semantic.job/lease-owner owner owner]
                           {:db/id eid
                            :semantic.job/lease-until (+ now lease-ms)
                            :semantic.job/updated-at now}]))
                      renewable)]
          (if-not (seq tx)
            #{}
            (try
              (d/transact! conn (vec tx))
              (set renewable)
              (catch clojure.lang.ExceptionInfo error
                (if (= :transact/cas (:error (ex-data error)))
                  ;; A superseding graph mutation can race callers that do not
                  ;; hold the project graph lock. Preserve per-job isolation in
                  ;; that exceptional path.
                  (into #{}
                        (filter #(renew-job-lease!
                                  graph % owner now lease-ms))
                        renewable)
                  (throw error)))))))))

  (mark-jobs-accepted! [graph job-ids owner accepted-at]
    (let [job-ids (vec (distinct job-ids))]
      (when-not (and (string? owner) (seq owner) (nat-int? accepted-at))
        (throw (ex-info "Semantic acceptance owner and time must be valid"
                        {:owner owner :accepted-at accepted-at})))
      (if (empty? job-ids)
        #{}
        (let [conn (connection graph)
              db (d/db conn)
              current
              (into {}
                    (map (fn [[id entity lease-owner status]]
                           [id [entity lease-owner status]]))
                    (d/q '[:find ?id ?entity ?lease-owner ?status
                           :in $ [?id ...]
                           :where
                           [?entity :semantic.job/id ?id]
                           [?entity :semantic.job/lease-owner ?lease-owner]
                           [?entity :semantic.job/status ?status]]
                         db job-ids))
              accepted
              (filterv (fn [job-id]
                         (let [[_ lease-owner status] (get current job-id)]
                           (and (= owner lease-owner) (= :leased status))))
                       job-ids)
              tx
              (mapcat (fn [job-id]
                        (let [[eid] (get current job-id)]
                          [[:db.fn/cas eid :semantic.job/lease-owner owner owner]
                           {:db/id eid
                            :semantic.job/accepted-at accepted-at
                            :semantic.job/updated-at accepted-at}]))
                      accepted)]
          (if-not (seq tx)
            #{}
            (try
              (d/transact! conn (vec tx))
              (set accepted)
              (catch clojure.lang.ExceptionInfo error
                (if (= :transact/cas (:error (ex-data error)))
                  #{}
                  (throw error)))))))))

  (recover-expired-leases! [graph provider now]
    (when-not (nat-int? now)
      (throw (ex-info "Semantic recovery time must be non-negative" {:now now})))
    (let [conn (connection graph)
          expired
          (d/q '[:find ?entity
                 :in $ ?provider ?now
                 :where
                 [?entity :semantic.job/provider ?provider]
                 [?entity :semantic.job/status :leased]
                 [?entity :semantic.job/lease-until ?until]
                 [(<= ?until ?now)]]
               (d/db conn) provider now)]
      (reduce
       (fn [count [eid]]
         (let [current (d/pull (d/db conn) '[*] eid)]
           (try
             (d/transact!
              conn
              (vec
               (concat
                [[:db.fn/cas eid :semantic.job/status :leased :pending]
                 {:db/id eid
                  :semantic.job/available-at now
                  :semantic.job/updated-at now}]
                (retract-present current [:semantic.job/lease-owner
                                          :semantic.job/lease-until
                                          :semantic.job/accepted-at]))))
             (inc count)
             (catch clojure.lang.ExceptionInfo error
               (if (= :transact/cas (:error (ex-data error)))
                 count
                 (throw error))))))
       0 expired)))

  (complete-job! [graph {:keys [job-id lease-owner indexed completed-at]}]
    (when-not (nat-int? completed-at)
      (throw (ex-info "Semantic completion time must be non-negative"
                      {:completed-at completed-at})))
    (let [conn (connection graph)
          db (d/db conn)
          eid (eid-by db :semantic.job/id job-id)
          job (when eid (d/pull db '[*] eid))]
      (when (and job (= lease-owner (:semantic.job/lease-owner job)))
        (let [indexed (when indexed (validate-indexed! indexed))
              old-indexed-eid
              (eid-by db :semantic.indexed/id
                      (indexed-id (:semantic.job/provider job)
                                  (:semantic.job/symbol-id job)))
              completion-token (str lease-owner "|complete|" completed-at)
              tx (cond-> [[:db.fn/cas eid :semantic.job/lease-owner
                            lease-owner completion-token]]
                   (and old-indexed-eid (nil? indexed))
                   (conj [:db/retractEntity old-indexed-eid])

                   indexed
                   (conj {:semantic.indexed/id
                          (indexed-id (:provider indexed) (:symbol-id indexed))
                          :semantic.indexed/provider (:provider indexed)
                          :semantic.indexed/symbol-id (:symbol-id indexed)
                          :semantic.indexed/file-id (:file-id indexed)
                          :semantic.indexed/document-hash (:document-hash indexed)
                          :semantic.indexed/model-revision (:model-revision indexed)
                          :semantic.indexed/document-version
                          (:document-version indexed)
                          :semantic.indexed/chunk-count (:chunk-count indexed)
                          :semantic.indexed/updated-at (:updated-at indexed)})

                   true
                   (conj [:db/retractEntity eid]))]
          (try
            (d/transact! conn tx)
            true
            (catch clojure.lang.ExceptionInfo error
              (if (= :transact/cas (:error (ex-data error)))
                false
                (throw error))))))))

  (complete-jobs! [graph completions]
    (let [completions (vec completions)]
      (doseq [{:keys [completed-at]} completions]
        (when-not (nat-int? completed-at)
          (throw (ex-info "Semantic completion time must be non-negative"
                          {:completed-at completed-at}))))
      (if (empty? completions)
        #{}
        (let [conn (connection graph)
              db (d/db conn)
              by-id (into {} (map (juxt :job-id identity)) completions)
              job-ids (vec (keys by-id))
              jobs
              (into {}
                    (map (fn [job]
                           [(:semantic.job/id job) job]))
                    (pull-many
                     db
                     (d/q '[:find [?entity ...]
                            :in $ [?id ...]
                            :where [?entity :semantic.job/id ?id]]
                          db job-ids)))
              eligible
              (into []
                    (keep (fn [job-id]
                            (let [{:keys [lease-owner indexed] :as completion}
                                  (get by-id job-id)
                                  job (get jobs job-id)]
                              (when (and job
                                         (= lease-owner
                                            (:semantic.job/lease-owner job)))
                                (assoc completion
                                       :job job
                                       :indexed
                                       (when indexed
                                         (validate-indexed! indexed)))))))
                    job-ids)
              indexed-ids
              (mapv (fn [{:keys [job]}]
                      (indexed-id (:semantic.job/provider job)
                                  (:semantic.job/symbol-id job)))
                    eligible)
              old-indexed
              (into {}
                    (d/q '[:find ?id ?entity
                           :in $ [?id ...]
                           :where [?entity :semantic.indexed/id ?id]]
                         db indexed-ids))
              tx
              (mapcat
               (fn [{:keys [job-id lease-owner indexed completed-at job]}]
                 (let [eid (:db/id job)
                       old-indexed-eid
                       (get old-indexed
                            (indexed-id (:semantic.job/provider job)
                                        (:semantic.job/symbol-id job)))
                       completion-token
                       (str lease-owner "|complete|" completed-at "|" job-id)]
                   (cond-> [[:db.fn/cas eid :semantic.job/lease-owner
                             lease-owner completion-token]]
                     (and old-indexed-eid (nil? indexed))
                     (conj [:db/retractEntity old-indexed-eid])

                     indexed
                     (conj {:semantic.indexed/id
                            (indexed-id (:provider indexed)
                                        (:symbol-id indexed))
                            :semantic.indexed/provider (:provider indexed)
                            :semantic.indexed/symbol-id (:symbol-id indexed)
                            :semantic.indexed/file-id (:file-id indexed)
                            :semantic.indexed/document-hash
                            (:document-hash indexed)
                            :semantic.indexed/model-revision
                            (:model-revision indexed)
                            :semantic.indexed/document-version
                            (:document-version indexed)
                            :semantic.indexed/chunk-count
                            (:chunk-count indexed)
                            :semantic.indexed/updated-at (:updated-at indexed)})

                     true
                     (conj [:db/retractEntity eid]))))
               eligible)]
          (try
            (when (seq tx)
              (d/transact! conn (vec tx)))
            (set (map :job-id eligible))
            (catch clojure.lang.ExceptionInfo error
              (if (= :transact/cas (:error (ex-data error)))
                (into #{}
                      (keep (fn [{:keys [job-id] :as completion}]
                              (when (complete-job! graph completion)
                                job-id)))
                      eligible)
                (throw error))))))))

  (retry-job! [graph {:keys [job-id lease-owner failed-at available-at
                             error max-attempts consume-attempt?]
                      :or {consume-attempt? true}}]
    (when-not (and (nat-int? failed-at) (nat-int? available-at)
                   (pos-int? max-attempts))
      (throw (ex-info "Semantic retry times and attempt limit must be valid"
                      {:failed-at failed-at :available-at available-at
                       :max-attempts max-attempts})))
    (let [conn (connection graph)
          db (d/db conn)
          eid (eid-by db :semantic.job/id job-id)
          job (when eid (d/pull db '[*] eid))]
      (when (and job (= lease-owner (:semantic.job/lease-owner job)))
        (let [attempts (+ (long (:semantic.job/attempts job))
                          (if consume-attempt? 1 0))
              terminal? (>= attempts max-attempts)
              release-token (str lease-owner "|release|" failed-at)
              message (subs (str error) 0 (min max-error-length
                                               (count (str error))))
              tx (vec
                  (concat
                   [[:db.fn/cas eid :semantic.job/lease-owner
                     lease-owner release-token]
                    {:db/id eid
                     :semantic.job/status (if terminal? :failed :pending)
                     :semantic.job/attempts attempts
                     :semantic.job/available-at available-at
                     :semantic.job/last-error message
                     :semantic.job/updated-at failed-at}]
                   [[:db/retract eid :semantic.job/lease-owner release-token]]
                   (when-let [until (:semantic.job/lease-until job)]
                     [[:db/retract eid :semantic.job/lease-until until]])
                   (when-let [accepted-at (:semantic.job/accepted-at job)]
                     [[:db/retract eid :semantic.job/accepted-at
                       accepted-at]])))]
          (try
            (d/transact! conn tx)
            {:status (if terminal? :failed :pending) :attempts attempts}
            (catch clojure.lang.ExceptionInfo error
              (if (= :transact/cas (:error (ex-data error)))
                nil
                (throw error))))))))

  (job-records [graph provider]
    (let [db (store/database graph)
          eids (d/q '[:find [?entity ...]
                      :in $ ?provider
                      :where [?entity :semantic.job/provider ?provider]]
                    db provider)]
      (->> (pull-many db eids)
           (sort-by :semantic.job/id)
           vec)))

  (indexed-records [graph provider]
    (let [db (store/database graph)
          eids (d/q '[:find [?entity ...]
                      :in $ ?provider
                      :where [?entity :semantic.indexed/provider ?provider]]
                    db provider)]
      (->> (pull-many db eids)
           (sort-by :semantic.indexed/id)
           vec)))

  (put-indexed! [graph indexed]
    (let [{:keys [provider symbol-id file-id document-hash model-revision
                  document-version chunk-count updated-at]}
          (validate-indexed! indexed)]
      (d/transact!
       (connection graph)
       [{:semantic.indexed/id (indexed-id provider symbol-id)
         :semantic.indexed/provider provider
         :semantic.indexed/symbol-id symbol-id
         :semantic.indexed/file-id file-id
         :semantic.indexed/document-hash document-hash
         :semantic.indexed/model-revision model-revision
         :semantic.indexed/document-version document-version
         :semantic.indexed/chunk-count chunk-count
         :semantic.indexed/updated-at updated-at}])
      indexed))

  (semantic-summary [graph provider now]
    (let [{:keys [oldest-pending-at] :as counts}
          (graph-read/semantic-counts (store/database graph) provider)]
      (let [desired (:desired counts)
            indexed (:indexed-current counts)
            complete? (and (= desired indexed)
                           (zero? (:pending counts))
                           (zero? (:leased counts))
                           (zero? (:failed counts))
                           (zero? (:dirty counts)))]
       {:provider provider
       :desired desired
       :indexed indexed
       :indexed-records (:indexed counts)
       :coverage-percent
       (if (zero? desired)
         100.0
         (double (/ (Math/round (* 10000.0 (/ indexed desired))) 100.0)))
       :completeness (if complete? :complete :partial)
       :pending (:pending counts)
       :leased (:leased counts)
       :accepted (:accepted counts)
       :failed (:failed counts)
       :oldest-pending-ms (when oldest-pending-at
                            (max 0 (- now oldest-pending-at)))
       :dirty (:dirty counts)
       :aggregate-analysis
       (assoc (:aggregate-analysis counts)
              :semantic-documents
              (if complete? :complete :partial))
       :watermark (watermark graph provider)})))

  (record-watermark! [graph {:keys [provider state last-success-at
                                    last-error-at last-error graph-revision
                                    index-generation]
                             :as value}]
    (require-key! value :provider keyword?
                  "Semantic watermark provider must be a keyword")
    (require-key! value :state watermark-states
                  "Unknown semantic watermark state")
    (let [conn (connection graph)
          db (d/db conn)
          existing-eid (eid-by db :semantic.watermark/id
                               (watermark-id provider))
          existing (when existing-eid (d/pull db '[*] existing-eid))
          entity (cond-> {:semantic.watermark/id (watermark-id provider)
                          :semantic.watermark/provider provider
                          :semantic.watermark/state state}
                   last-success-at
                   (assoc :semantic.watermark/last-success-at last-success-at)
                   last-error-at
                   (assoc :semantic.watermark/last-error-at last-error-at)
                   last-error
                   (assoc :semantic.watermark/last-error
                          (subs (str last-error)
                                0 (min max-error-length
                                       (count (str last-error)))))
                   graph-revision
                   (assoc :semantic.watermark/graph-revision graph-revision)
                   index-generation
                   (assoc :semantic.watermark/index-generation
                          index-generation))
          clear-errors (when (and existing (nil? last-error))
                         (retract-present
                          existing
                          [:semantic.watermark/last-error
                           :semantic.watermark/last-error-at]))]
      (d/transact! conn (vec (concat clear-errors [entity])))
      value))

  (watermark [graph provider]
    (let [db (store/database graph)]
      (when-let [eid (eid-by db :semantic.watermark/id
                             (watermark-id provider))]
        (d/pull db '[*] eid)))))

(defn failure-records
  "Return operator-facing terminal failures with graph file paths."
  [graph provider]
  (let [db (store/database graph)]
    (->> (job-records graph provider)
         (filter #(= :failed (:semantic.job/status %)))
         (mapv
          (fn [job]
            {:symbol-id (:semantic.job/symbol-id job)
             :file-id (:semantic.job/file-id job)
             :file (graph-read/file-path db (:semantic.job/file-id job))
             :operation (:semantic.job/operation job)
             :attempts (:semantic.job/attempts job)
             :timestamp (:semantic.job/updated-at job)
             :last-error (:semantic.job/last-error job)})))))

(defn dirty-details
  "Return deferred file markers and their last durable reconciliation state."
  [graph provider]
  (let [db (store/database graph)]
    (mapv
     (fn [marker]
       {:file-id (:semantic.dirty/file-id marker)
        :file (graph-read/file-path db (:semantic.dirty/file-id marker))
        :operation (:semantic.dirty/operation marker)
        :created-at (:semantic.dirty/created-at marker)
        :file-hash (:semantic.dirty/file-hash marker)
        :last-error-at (:semantic.dirty/last-error-at marker)
        :last-error (:semantic.dirty/last-error marker)})
     (dirty-records graph provider))))
