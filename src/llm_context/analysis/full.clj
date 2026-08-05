(ns llm-context.analysis.full
  (:require [llm-context.analysis.files :as files]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.janet :as janet]
            [llm-context.analysis.manifest :as manifest]
            [llm-context.analysis.project-analyzer :as project-analyzer]
            [llm-context.model.canonical-hash :as canonical-hash]
            [llm-context.model.ids :as ids]
            [llm-context.query :as query]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.document :as semantic-document]
            [llm-context.store :as store]))

(def persistence-batch-size 1000)
(def analyzer-name "clj-kondo+janet-semantic")
(def supported-languages
  #{:language/clojure :language/clojurescript :language/clojure-common
    :language/janet :language/edn-data})

(defn- emit! [progress stage data]
  (when progress
    (progress (assoc data :stage stage))))

(defn- persist!
  ([graph config entities progress]
   (persist! graph config entities nil progress))
  ([graph config entities analyzers progress]
  ;; A full analysis is the format upgrade boundary. Semantic operational
  ;; records belong to a versioned document/index contract and must not make a
  ;; new graph appear complete merely because an older index was complete.
  ;; Preflight the entire replacement before changing either state domain.
  (store/validate-replacement! graph entities)
  (store/begin-full-replacement! graph)
  (store/replace-all! graph entities
                      {:batch-size persistence-batch-size
                       :on-progress
                       (when progress
                         #(emit! progress :persist-progress %))})
  ;; Only reset versioned semantic state after the canonical graph has
  ;; completely landed. If graph persistence fails, the unavailable marker is
  ;; durable and the previous semantic recovery records remain intact.
  (store/reset-semantic-state! graph)
  (when (semantic-reconcile/enabled? config)
    (semantic-reconcile/mark-full! graph))
  (let [lateon (get-in config [:semantic :lateon-code])]
    (store/write-graph-metadata!
     graph
     {:analyzer-name analyzer-name
      :analyzer-version clj-kondo/analyzer-version
      :analyzer-configuration-fingerprint
      (get-in analyzers [:clj-kondo :configuration-fingerprint])
      :semantic-fingerprint-version canonical-hash/contract-version
      :janet-catalog-version janet/catalog-version
      :semantic-document-version (:document-version lateon)
      :semantic-index-name (:index-name lateon)}))
  nil))

(defn- source-inventory [files]
  (mapv (fn [{:keys [relative-path content]}]
          [relative-path (ids/content-hash content)])
        files))

(defn prepare
  "Prepare and validate a complete candidate without opening or mutating the
  graph. The returned inventory is revalidated immediately before activation."
  ([project config] (prepare project config nil :full))
  ([project config progress] (prepare project config progress :full))
  ([project config progress mode]
   (let [started (System/nanoTime)]
     (emit! progress :discover-start {})
     (let [{:keys [files diagnostics]}
           (files/discover project config supported-languages)
           total (count files)
           _ (emit! progress :discover-complete
                    {:files total :diagnostics (count diagnostics)})
           _ (emit! progress :parse-progress
                    {:completed 0 :total total
                     :file (some-> files first :relative-path)})
           project-snapshot (project-analyzer/analyze project files progress)
           outputs (:outputs project-snapshot)
           preserved (filterv :preserve? outputs)
           _ (emit! progress :parse-complete
                    {:completed total :total total})]
       (when (seq preserved)
         (throw
          (ex-info
           (str (if (= :incremental mode) "Incremental" "Full")
                " analysis produced an incomplete snapshot; existing graph was preserved")
           {:exit-code 1
            :type :analysis/incomplete-snapshot
            :files (mapv (comp :file/path :file) preserved)
            :diagnostics
            (vec (concat diagnostics
                         (:diagnostics project-snapshot)
                         (mapcat :diagnostics preserved)))})))
       {:started started
        :files files
        :file-count total
        :source-inventory (source-inventory files)
        :outputs outputs
        :entities (vec (mapcat (fn [{:keys [file entities]}]
                                (cons file entities))
                              outputs))
        :analysis-metrics (:analysis-metrics project-snapshot)
        :analyzers (:analyzers project-snapshot)
        :diagnostics
        (vec (concat diagnostics
                     (:diagnostics project-snapshot)
                     (mapcat :diagnostics outputs)))}))))

(defn stale-candidate?
  [project config candidate]
  (let [{:keys [files]} (files/discover project config supported-languages)]
    (not= (:source-inventory candidate) (source-inventory files))))

(defn prepare-current
  "Prepare and revalidate once, retrying one stale preparation."
  ([project config] (prepare-current project config nil :full))
  ([project config progress] (prepare-current project config progress :full))
  ([project config progress mode]
   (loop [attempt 0]
     (let [candidate (prepare project config progress mode)]
       (if-not (stale-candidate? project config candidate)
         candidate
         (if (zero? attempt)
           (recur 1)
           {:stale? true
            :mode :stale-source
            :type :analysis/stale-source
            :attempts 2}))))))

(defn commit-candidate!
  "Activate a prepared full candidate. Callers coordinate this short mutation
  boundary; semantic reconciliation intentionally happens afterward."
  [graph project config candidate progress]
  (let [entities (:entities candidate)]
    (emit! progress :persist-start
           {:entities (count entities) :batch-size persistence-batch-size})
    (persist! graph config entities (:analyzers candidate) progress)
    (emit! progress :analyzer-finalize-start {})
    (let [quality (query/graph-quality graph)
          graph-revision (semantic-document/graph-revision
                          (store/database graph))
          manifest-index (manifest/write! project candidate graph-revision)]
      (emit! progress :analyzer-finalize-complete quality)
      {:mode :full
       :files (:file-count candidate)
       :entities (count entities)
       :analysis-metrics (:analysis-metrics candidate)
       :graph-quality quality
       :manifest {:version (:version manifest-index)
                  :graph-revision graph-revision
                  :files (count (:files manifest-index))}
       :diagnostics (:diagnostics candidate)
       :started (:started candidate)})))

(defn finish-candidate!
  "Reconcile semantic state after graph activation and outside graph mutation
  coordination."
  [graph project config result progress]
  (emit! progress :semantic-reconcile-start {})
  (let [semantic-plan (semantic-reconcile/reconcile! graph project config)]
    (emit! progress :semantic-reconcile-complete
           {:upserts (:queued-upserts semantic-plan)
            :deletes (:queued-deletes semantic-plan)
            :deferred (:deferred semantic-plan)})
    (emit! progress :complete
           {:elapsed-seconds
            (long (/ (- (System/nanoTime) (:started result)) 1000000000))})
    (-> result
        (dissoc :started)
        (assoc :semantic semantic-plan)
        (update :diagnostics into (:diagnostics semantic-plan)))))

(defn analyze!
  "Perform a complete project scan and replace Datalevin facts in bounded
  transactions. Analyzer adapters emit final exact edges and classified
  references; persistence never promotes syntax into graph relationships."
  ([project config]
   (analyze! project config nil))
  ([project config progress]
   (store/with-store [graph project config]
     (analyze! graph project config progress)))
  ([graph project config progress]
   (let [candidate (prepare-current project config progress)]
     (if (:stale? candidate)
       candidate
       (finish-candidate!
        graph project config
        (commit-candidate! graph project config candidate progress)
        progress)))))
