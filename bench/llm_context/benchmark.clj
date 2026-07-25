(ns llm-context.benchmark
  (:require [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.context :as context]
            [llm-context.export :as export]
            [llm-context.project :as project]
            [llm-context.query :as query]
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
      (spit (str (.resolve src (str "module_" index ".js")))
            (if (zero? index)
              "export function function0(value) { console.log(value); return value; }"
              (format
               "export function function%d(value) { return function0(value); }"
               index))))
    {:root root :project (project/context (str root))}))

(defn -main [& [count-argument]]
  (let [file-count (or (some-> count-argument parse-long) 50)
        {:keys [root project]} (fixture file-count)
        settings (-> (config/defaults)
                     (assoc-in [:analysis :include] ["src"])
                     (assoc-in [:semantic :providers] []))
        full-result (timed #(full/analyze! project settings))
        unchanged (timed #(incremental/analyze! project settings))
        changed-path (.resolve (.resolve root "src") "module_0.js")]
    (spit (str changed-path)
          "export function function0(value) { console.log(value); return value + 1; }")
    (let [changed (timed #(incremental/analyze! project settings))
          reads (store/with-store [graph project settings]
                  {:stats (timed #(query/stats graph))
                   :context (timed #(context/build graph
                                                   {:focus "function0"
                                                    :depth 2
                                                    :max-tokens 2000}))
                   :summary (timed #(export/summary-markdown graph))})
          packet (get-in reads [:context :value])
          result {:benchmark/version 2
                  :files file-count
                  :full-ms (:milliseconds full-result)
                  :unchanged-incremental-ms (:milliseconds unchanged)
                  :changed-incremental-ms (:milliseconds changed)
                  :stats-query-ms (get-in reads [:stats :milliseconds])
                  :context-query-ms (get-in reads [:context :milliseconds])
                  :summary-query-ms (get-in reads [:summary :milliseconds])
                  :entities (get-in reads [:stats :value :entities])
                  :context-symbols (count (:symbols packet))
                  :context-truncated? (:truncated? packet)}]
      (when-not (and (= file-count (get-in full-result [:value :files]))
                     (= 1 (get-in changed [:value :changed]))
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
