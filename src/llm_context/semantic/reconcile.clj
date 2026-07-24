(ns llm-context.semantic.reconcile
  "Translate durable dirty markers into coalesced symbol upserts/deletes by
  comparing authoritative graph documents with recorded NextPlaid state."
  (:require [datalevin.core :as d]
            [llm-context.semantic.document :as document]
            [llm-context.semantic.state :as state]
            [llm-context.store :as store]))

(def provider :lateon-code)
(def project-marker "project:*")

(defn enabled? [config]
  (let [lateon (get-in config [:semantic :lateon-code])]
    (and (:enabled lateon)
         (not= :disabled (:mode lateon))
         (contains? (set (get-in config [:semantic :providers])) provider))))

(defn dirty-marker
  ([file-id file-hash operation]
   (dirty-marker file-id file-hash operation (System/currentTimeMillis)))
  ([file-id file-hash operation now]
   (cond-> {:provider provider
            :file-id file-id
            :operation operation
            :created-at now}
     file-hash (assoc :file-hash file-hash))))

(defn dirty-entity
  ([file-id file-hash operation]
   (dirty-entity file-id file-hash operation (System/currentTimeMillis)))
  ([file-id file-hash operation now]
   (state/dirty-entity (dirty-marker file-id file-hash operation now))))

(defn mark-full! [graph]
  (state/mark-dirty! graph
                     (dirty-marker project-marker nil :reconcile-all)))

(defn- file-hashes [graph]
  (into (sorted-map)
        (store/query graph
                     '[:find ?id ?hash
                       :where [?file :file/id ?id]
                              [?file :file/content-hash ?hash]]
                     [])))

(defn- indexed-by-symbol [graph]
  (into {}
        (map (juxt :semantic.indexed/symbol-id identity))
        (state/indexed-records graph provider)))

(defn- jobs-by-symbol [graph]
  (into {}
        (map (juxt :semantic.job/symbol-id identity))
        (state/job-records graph provider)))

(defn- path-for-file [graph file-id]
  (ffirst
   (store/query graph
                '[:find ?path
                  :in $ ?id
                  :where [?file :file/id ?id]
                         [?file :file/path ?path]]
                [file-id])))

(defn- same-indexed? [lateon indexed desired]
  (and indexed
       (= (:document-hash desired)
          (:semantic.indexed/document-hash indexed))
       (= (:model-revision lateon)
          (:semantic.indexed/model-revision indexed))
       (= (:document-version lateon)
          (:semantic.indexed/document-version indexed))
       (= (count (:chunks desired))
          (:semantic.indexed/chunk-count indexed))))

(defn- reconcile-file!
  [graph project lateon marker now indexed jobs]
  (let [file-id (:semantic.dirty/file-id marker)
        operation (:semantic.dirty/operation marker)
        built (if (= :delete operation)
                {:status :deleted :file-id file-id
                 :documents [] :diagnostics []}
                (document/build-file graph project lateon file-id))]
    (if (or (= :source-changed (:status built))
            (seq (:diagnostics built)))
      {:status :deferred
       :file-id file-id
       :diagnostics (:diagnostics built)
       :queued-upserts 0 :queued-deletes 0
       :cancelled 0 :unchanged 0}
      (let [desired (into {} (map (juxt :symbol-id identity))
                          (:documents built))
            current (into {}
                          (filter (fn [[_ value]]
                                    (= file-id
                                       (:semantic.indexed/file-id value))))
                          indexed)
            pending (into {}
                          (filter (fn [[_ value]]
                                    (= file-id (:semantic.job/file-id value))))
                          jobs)
            all-symbols (sort (set (concat (keys desired)
                                           (keys current)
                                           (keys pending))))
            counts
            (reduce
             (fn [result symbol-id]
               (let [wanted (get desired symbol-id)
                     indexed-record (get current symbol-id)
                     pending-record (get pending symbol-id)]
                 (cond
                   (and wanted
                        (same-indexed? lateon indexed-record wanted))
                   (do
                     (when pending-record
                       (state/cancel-job! graph provider symbol-id))
                     (-> result
                         (update :unchanged inc)
                         (update :cancelled +
                                 (if pending-record 1 0))))

                   wanted
                   (do
                     (state/enqueue-job!
                      graph {:provider provider
                             :symbol-id symbol-id
                             :file-id file-id
                             :operation :upsert
                             :document-hash (:document-hash wanted)
                             :available-at now
                             :updated-at now})
                     (update result :queued-upserts inc))

                   indexed-record
                   (do
                     (state/enqueue-job!
                      graph {:provider provider
                             :symbol-id symbol-id
                             :file-id file-id
                             :operation :delete
                             :available-at now
                             :updated-at now})
                     (update result :queued-deletes inc))

                   pending-record
                   (do
                     (state/cancel-job! graph provider symbol-id)
                     (update result :cancelled inc))

                   :else result)))
             {:queued-upserts 0 :queued-deletes 0
              :cancelled 0 :unchanged 0}
             all-symbols)]
        (state/clear-dirty! graph provider file-id)
        (assoc counts
               :status :reconciled
               :file-id file-id
               :diagnostics (:diagnostics built))))))

(defn- reconcile-file-safely!
  [graph project lateon marker now indexed jobs]
  (try
    (reconcile-file! graph project lateon marker now indexed jobs)
    (catch Exception error
      (let [file-id (:semantic.dirty/file-id marker)
            file-path (path-for-file graph file-id)]
        {:status :deferred
         :file-id file-id
         :diagnostics
         [(cond-> {:level :warning
                   :kind :semantic-file-failed
                   :file-id file-id
                   :message (.getMessage error)}
            file-path (assoc :file file-path))]
         :queued-upserts 0 :queued-deletes 0
         :cancelled 0 :unchanged 0}))))

(defn reconcile!
  "Reconcile all durable LateOn dirty markers. Safe to call after every
  analysis and on every service start."
  ([graph project config]
   (reconcile! graph project config (System/currentTimeMillis)))
  ([graph project config now]
   (if-not (enabled? config)
     {:enabled? false :queued-upserts 0 :queued-deletes 0
      :cancelled 0 :unchanged 0 :deferred 0 :diagnostics []}
     (let [lateon (get-in config [:semantic :lateon-code])
           markers (state/dirty-records graph provider)
           full? (some #(= :reconcile-all
                           (:semantic.dirty/operation %))
                       markers)
           explicit (remove #(= :reconcile-all
                                (:semantic.dirty/operation %))
                            markers)
           marked-files (set (map :semantic.dirty/file-id explicit))
           graph-file-hashes (file-hashes graph)
           synthetic
           (when full?
             (for [[file-id file-hash] graph-file-hashes
                   :when (not (contains? marked-files file-id))]
               (state/dirty-entity
                (dirty-marker file-id file-hash :upsert now))))
           ;; Indexed files absent from the graph need deletion during a full
           ;; repair, including after an interrupted graph replacement.
           graph-files (set (keys graph-file-hashes))
           indexed (indexed-by-symbol graph)
           indexed-files (set (map :semantic.indexed/file-id (vals indexed)))
           missing
           (when full?
             (for [file-id (sort (remove graph-files indexed-files))
                   :when (not (contains? marked-files file-id))]
               (state/dirty-entity
                (dirty-marker file-id nil :delete now))))
           work (->> (concat explicit synthetic missing)
                     (sort-by :semantic.dirty/file-id)
                     vec)
           jobs (jobs-by-symbol graph)
           results (mapv #(reconcile-file-safely! graph project lateon % now
                                                   indexed jobs)
                         work)
           deferred (count (filter #(= :deferred (:status %)) results))]
       (when (and full? (zero? deferred))
         (state/clear-dirty! graph provider project-marker))
       {:enabled? true
        :dirty (count work)
        :queued-upserts (reduce + (map :queued-upserts results))
        :queued-deletes (reduce + (map :queued-deletes results))
        :cancelled (reduce + (map :cancelled results))
        :unchanged (reduce + (map :unchanged results))
        :deferred deferred
        :diagnostics (vec (mapcat :diagnostics results))}))))
