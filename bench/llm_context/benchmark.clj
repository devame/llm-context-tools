(ns llm-context.benchmark
  (:require [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.context :as context]
            [llm-context.export :as export]
            [llm-context.model.ids :as ids]
            [llm-context.project :as project]
            [llm-context.query :as query]
            [llm-context.semantic.document :as document]
            [llm-context.semantic.reconcile :as reconcile]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(defn timed [operation]
  (let [started (System/nanoTime)
        value (operation)]
    {:milliseconds (/ (- (System/nanoTime) started) 1000000.0)
     :value value}))

(defn fixture [file-count]
  (let [root (Files/createTempDirectory "llm-context-benchmark-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")]
    (Files/createDirectories src (make-array java.nio.file.attribute.FileAttribute 0))
    (doseq [index (range file-count)]
      (spit (str (.resolve src (str "module_" index ".clj")))
            (if (zero? index)
              "(ns bench.module-0)\n(defn function0 [value] (println value) value)\n"
              (format
               (str "(ns bench.module-%1$d "
                    "(:require [bench.module-0 :as root]))\n"
                    "(defn function%1$d [value] (root/function0 value))\n")
               index))))
    {:root root :project (project/context (str root))}))

(defn -main [& [count-argument]]
  (let [file-count (or (some-> count-argument parse-long) 50)
        {:keys [root project]} (fixture file-count)
        settings (-> (config/defaults)
                     (assoc-in [:analysis :include] ["src"])
                     (assoc-in [:semantic :providers] []))
        semantic-settings (assoc-in (config/defaults)
                                    [:analysis :include] ["src"])
        full-result (timed #(full/analyze! project settings))
        unchanged (timed #(incremental/analyze! project settings))
        changed-path (.resolve (.resolve root "src") "module_0.clj")]
    (spit (str changed-path)
          "(ns bench.module-0)\n(defn function0 [value] (println value) (inc value))\n")
    (let [changed (timed #(incremental/analyze! project settings))
        reads
        (store/with-store [graph project settings]
          (let [trace-source
                (:id (first (query/symbols
                             graph (str "function" (dec file-count)) 1)))
                document-file (ids/file-id "src/module_0.clj")
                stats (timed #(query/stats graph))
                context (timed #(context/build graph
                                               {:focus "function0"
                                                :depth 2
                                                :max-tokens 2000}))
                summary (timed #(export/summary-markdown graph))
                trace (timed #(query/transitive-callees
                               graph trace-source {:depth 4 :limit 200}))
                suggestions (timed #(query/symbol-suggestions
                                     graph "functoin0"))
                semantic-document
                (timed #(document/build-file
                         graph project
                         (get-in semantic-settings [:semantic :lateon-code])
                         document-file))
                _ (reconcile/mark-full! graph)
                semantic-reconcile
                (timed #(reconcile/reconcile!
                         graph project semantic-settings))]
            {:stats stats :context context :summary summary :trace trace
             :suggestions suggestions
             :semantic-document semantic-document
             :semantic-reconcile semantic-reconcile}))
          packet (get-in reads [:context :value])
          result {:benchmark/version 2
                  :files file-count
                  :full-ms (:milliseconds full-result)
                  :unchanged-incremental-ms (:milliseconds unchanged)
                  :changed-incremental-ms (:milliseconds changed)
                  :stats-query-ms (get-in reads [:stats :milliseconds])
                  :context-query-ms (get-in reads [:context :milliseconds])
                  :summary-query-ms (get-in reads [:summary :milliseconds])
                  :trace-query-ms (get-in reads [:trace :milliseconds])
                  :suggestion-query-ms
                  (get-in reads [:suggestions :milliseconds])
                  :semantic-document-ms
                  (get-in reads [:semantic-document :milliseconds])
                  :semantic-reconcile-ms
                  (get-in reads [:semantic-reconcile :milliseconds])
                  :entities (get-in reads [:stats :value :entities])
                  :context-symbols (count (:symbols packet))
                  :context-truncated? (:truncated? packet)}]
      (when-not (and (= file-count (get-in full-result [:value :files]))
                     (= 1 (get-in changed [:value :changed]))
                     (<= (count (get-in reads [:trace :value :results])) 200)
                     (= :ready (get-in reads [:semantic-document :value :status]))
                     (true? (get-in reads [:semantic-reconcile :value :enabled?]))
                     (<= (get-in packet [:budget :estimated-tokens]) 2000)
                     (<= (:context-symbols result) 250))
        (throw (ex-info "Graph scaling correctness gate failed"
                        {:result result
                         :full (:value full-result)
                         :changed (:value changed)})))
      (prn result)
      (flush)
      (shutdown-agents)
      (System/exit 0))))
