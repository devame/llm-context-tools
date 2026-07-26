(ns llm-context.analysis.full
  (:require [llm-context.analysis.files :as files]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.janet :as janet]
            [llm-context.analysis.project-analyzer :as project-analyzer]
            [llm-context.query :as query]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.store :as store]))

(def persistence-batch-size 100)
(def analyzer-name "clj-kondo+janet-semantic")

(defn- emit! [progress stage data]
  (when progress
    (progress (assoc data :stage stage))))

(defn- persist! [graph config entities progress]
  ;; A full analysis is the format upgrade boundary. Semantic operational
  ;; records belong to a versioned document/index contract and must not make a
  ;; new graph appear complete merely because an older index was complete.
  ;; Preflight the entire replacement before changing either state domain.
  (store/validate-replacement! graph entities)
  (store/reset-semantic-state! graph)
  (store/replace-all! graph entities
                      {:batch-size persistence-batch-size
                       :on-progress
                       (when progress
                         #(emit! progress :persist-progress %))})
  (let [lateon (get-in config [:semantic :lateon-code])]
    (store/write-graph-metadata!
     graph
     {:analyzer-name analyzer-name
      :analyzer-version clj-kondo/analyzer-version
      :janet-catalog-version janet/catalog-version
      :semantic-document-version (:document-version lateon)
      :semantic-index-name (:index-name lateon)}))
  (when (semantic-reconcile/enabled? config)
    (semantic-reconcile/mark-full! graph)))

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
   (let [started (System/nanoTime)]
     (emit! progress :discover-start {})
     (let [{:keys [files diagnostics]}
           (files/discover project config
                           #{:language/clojure :language/clojurescript
                             :language/clojure-common :language/janet
                             :language/edn-data})
             total (count files)
             _ (emit! progress :discover-complete
                      {:files total :diagnostics (count diagnostics)})
             _ (emit! progress :parse-progress
                      {:completed 0 :total total
                       :file (some-> files first :relative-path)})
             project-snapshot (project-analyzer/analyze project files)
             extracted (:outputs project-snapshot)
             _ (emit! progress :parse-complete
                      {:completed total :total total})
             all-entities (vec (mapcat (fn [{:keys [file entities]}]
                                         (cons file entities))
                                       extracted))
             _ (emit! progress :persist-start
                      {:entities (count all-entities)
                       :batch-size persistence-batch-size})]
         (persist! graph config all-entities progress)
         (emit! progress :analyzer-finalize-start {})
         (let [quality (query/graph-quality graph)
               _ (emit! progress :analyzer-finalize-complete quality)
               _ (emit! progress :semantic-reconcile-start {})
               semantic-plan
               (semantic-reconcile/reconcile! graph project config)
               _ (emit! progress :semantic-reconcile-complete
                        {:upserts (:queued-upserts semantic-plan)
                         :deletes (:queued-deletes semantic-plan)
                         :deferred (:deferred semantic-plan)})]
           (emit! progress :complete
                  {:elapsed-seconds
                   (long (/ (- (System/nanoTime) started) 1000000000))})
           {:mode :full
            :files total
            :entities (count all-entities)
            :graph-quality quality
            :semantic semantic-plan
            :diagnostics (vec (concat diagnostics
                                      (:diagnostics project-snapshot)
                                      (mapcat :diagnostics extracted)
                                      (:diagnostics semantic-plan)))})))))
