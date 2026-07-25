(ns llm-context.analysis.full
  (:require [llm-context.analysis.files :as files]
            [llm-context.analysis.project-analyzer :as project-analyzer]
            [llm-context.analysis.resolve :as resolve]
            [llm-context.graph.read :as graph-read]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.store :as store]))

(def persistence-batch-size 100)
(def resolution-batch-size 1000)

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

(defn- resolve-persisted! [graph progress]
  (let [db (store/database graph)
        edge-ids (graph-read/all-edge-ids db)
        _ (emit! progress :resolve-edges-selected {:edges (count edge-ids)})
        edges (graph-read/edge-resolution-inputs db edge-ids)
        _ (emit! progress :resolve-edges-loaded {:edges (count edges)})
        symbols (graph-read/resolution-candidate-symbols db edges)
        _ (emit! progress :resolve-candidates-selected
                 {:candidates (count symbols)})
        decisions (resolve/resolution-decisions symbols edges {})]
    (emit! progress :resolve-plan
           {:edges (count edges) :candidates (count symbols)
            :exact 0 :batch-size resolution-batch-size})
    (store/reconcile-edges!
     graph decisions
     {:batch-size resolution-batch-size
      :on-progress
      (when progress #(emit! progress :resolve-progress %))})
    {:edges (count edges) :candidates (count symbols)
     :exact 0}))

(defn analyze!
  "Perform a complete project scan and replace Datalevin facts in bounded
  transactions. A missing optional semantic provider degrades resolution, not
  availability."
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
         (emit! progress :resolve-start {})
         (let [resolution (resolve-persisted! graph progress)
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
            :resolution resolution
            :semantic semantic-plan
            :diagnostics (vec (concat diagnostics
                                      (:diagnostics project-snapshot)
                                      (mapcat :diagnostics extracted)
                                      (:diagnostics semantic-plan)))})))))
