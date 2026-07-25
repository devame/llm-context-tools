(ns llm-context.analysis.full
  (:require [llm-context.analysis.files :as files]
            [llm-context.analysis.project-analyzer :as project-analyzer]
            [llm-context.query :as query]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.store :as store]))

(def persistence-batch-size 100)

(defn- emit! [progress stage data]
  (when progress
    (progress (assoc data :stage stage))))

(defn- persist! [graph config entities progress]
  (when (semantic-reconcile/enabled? config)
    (semantic-reconcile/mark-full! graph))
  (store/replace-all! graph entities
                      {:batch-size persistence-batch-size
                       :on-progress
                       (when progress
                         #(emit! progress :persist-progress %))}))

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
