(ns llm-context.cli
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [llm-context.config :as config]
            [llm-context.model-packages :as model-packages]
            [llm-context.project :as project]
            [llm-context.service.client :as service-client]
            [llm-context.service.progress :as analysis-progress]
            [llm-context.version :as version]))

(defn- resolve-fn [symbol]
  (requiring-resolve symbol))

(defn- with-graph [context settings f]
  (let [graph ((resolve-fn 'llm-context.store/open) context settings)]
    (try
      (f graph)
      (finally (.close ^java.io.Closeable graph)))))

(defn- with-compatible-graph [context settings f]
  (with-graph
    context settings
    (fn [graph]
      ((resolve-fn 'llm-context.store/assert-query-compatible!) graph)
      (f graph))))

(def ^:private unavailable ::unavailable)

(defn- response-value [response]
  (if response
    (if (:ok response)
      (:value response)
      (throw (ex-info (:error response) {:exit-code (:exit-code response)})))
    unavailable))

(defn- remote-value
  ([context request]
   (response-value (service-client/request context request)))
  ([context request options]
   (response-value (service-client/request context request options))))

(defn usage []
  (str "llm-context " version/value "\n\n"
       "Usage: llm-context [global-options] <command> [command-options]\n\n"
       "Global options:\n"
       "  -C, --project PATH   Project root (default: current directory)\n"
       "  -q, --quiet          Suppress informational output\n"
       "  -h, --help           Show this help\n\n"
       "Commands:\n"
       "  init [--yes]         Confirm the project root and write llm-context.edn\n"
       "  analyze              Update the semantic graph\n"
       "    --check            Validate a source snapshot without writing data\n"
       "  query                Query the semantic graph\n"
       "  semantic             Inspect or synchronize LateOn indexing\n"
       "  models               Install or inspect verified model packages\n"
       "  context              Build a symbol or natural-language context packet\n"
       "  export               Export graph data\n"
       "  summary              Export a Markdown graph summary\n"
       "  integrate            Install agent guidance\n"
       "  service              Manage the optional resident service\n"
       "  stats                Show graph statistics\n"
       "  entry-points         Show inferred entry points\n"
       "  side-effects         Show classified side effects\n"
       "  doctor               Check runtime capabilities\n"
       "  version              Print the application version\n"))

(defn parse-args
  "Parse global options without interpreting command-specific arguments."
  [args]
  (loop [remaining (seq args)
         parsed {:project "." :quiet? false :command nil :args []}]
    (if-let [arg (first remaining)]
      (cond
        (#{"-C" "--project"} arg)
        (if-let [value (second remaining)]
          (recur (nnext remaining) (assoc parsed :project value))
          (throw (ex-info (str arg " requires a path") {:exit-code 2})))

        (#{"-q" "--quiet"} arg)
        (recur (next remaining) (assoc parsed :quiet? true))

        (#{"-h" "--help"} arg)
        (recur (next remaining) (assoc parsed :command "help"))

        (nil? (:command parsed))
        (recur (next remaining) (assoc parsed :command arg))

        :else
        (recur (next remaining) (update parsed :args conj arg)))
      (update parsed :command #(or % "help")))))

(defmulti execute (fn [_context command _args] command))

(defmethod execute "help" [_ _ _]
  (print (usage))
  0)

(defmethod execute "version" [_ _ _]
  (println version/value)
  0)

(defn- parse-model-options [args]
  (loop [remaining args
         result {:source-roots {}}]
    (if-let [arg (first remaining)]
      (case arg
        "--manifest" (recur (nnext remaining) (assoc result :manifest (second remaining)))
        "--manifest-sha256" (recur (nnext remaining) (assoc result :manifest-sha256 (second remaining)))
        "--cache" (recur (nnext remaining) (assoc result :cache (second remaining)))
        "--registry" (recur (nnext remaining) (assoc result :registry (second remaining)))
        "--roles" (recur (nnext remaining)
                           (assoc result :selected-roles
                                  (mapv keyword (str/split (or (second remaining) "") #","))))
        "--source-root"
        (let [[role path] (str/split (or (second remaining) "") #"=" 2)]
          (when (or (str/blank? role) (str/blank? path))
            (throw (ex-info "--source-root requires ROLE=PATH" {:exit-code 2})))
          (recur (nnext remaining) (assoc-in result [:source-roots (keyword role)] path)))
        (throw (ex-info (str "Unknown models option: " arg) {:exit-code 2})))
      result)))

(defmethod execute "models" [_ _ args]
  (let [[subcommand & options] args]
    (case subcommand
      "install"
      (let [parsed (parse-model-options options)
            cache (or (:cache parsed) (System/getenv "LLM_CONTEXT_MODEL_CACHE"))]
        (when (str/blank? cache)
          (throw (ex-info "models install requires --cache or LLM_CONTEXT_MODEL_CACHE"
                          {:exit-code 2})))
        (let [result (model-packages/install! (assoc parsed :cache cache))]
          (doseq [[role package] (:roles result)]
            (println (format "%s: %s@%s -> %s"
                             (name role) (:model package) (:revision package)
                             (:path package)))))
        0)

      "status"
      (let [parsed (parse-model-options options)
            registry (or (:registry parsed)
                         (System/getenv "LLM_CONTEXT_MODEL_REGISTRY"))
            installed (model-packages/read-registry registry)]
        (if installed
          (pprint/pprint installed)
          (println "No installed model registry is configured."))
        0)

      (throw (ex-info "Usage: llm-context models install|status [options]"
                      {:exit-code 2})))))

(defn- confirm-project-root? [context]
  (printf "Initialize llm-context in %s? [y/N] " (:root-str context))
  (flush)
  (let [answer (some-> (read-line) str/trim str/lower-case)]
    (case answer
      ("y" "yes") true
      ("" "n" "no") false
      nil (throw (ex-info "Confirmation input is unavailable; rerun init with --yes"
                          {:exit-code 2}))
      (throw (ex-info "Please answer yes or no" {:exit-code 2})))))

(defmethod execute "init" [context _ args]
  (when-let [unknown (first (remove #{"--yes"} args))]
    (throw (ex-info (str "Unknown init option: " unknown) {:exit-code 2})))
  (if (or (some #{"--yes"} args) (confirm-project-root? context))
    (println "Created" (str (config/init! context)))
    (println "Initialization cancelled; no files were written."))
  0)

(defn- diagnostic-message
  [{:keys [level kind file path language message size row column]}]
  (str (name (or level :info)) " " (name kind) ": "
       (case kind
         :missing-include (str "configured path does not exist: " path)
         :grammar-unavailable (str file " (" (some-> language name) ")")
         :file-too-large (str file " (" size " bytes)")
         :binary-file file
         :clj-kondo (str file
                         (when (and row column)
                           (str ":" row ":" column))
                         ": " message)
         :semantic-provider-failed (or message "semantic provider failed")
         (or file path message (pr-str kind)))))

(defmethod execute "doctor" [context _ _]
  (let [checks ((resolve-fn 'llm-context.runtime.doctor/check)
                context (config/load-config context))]
    ((resolve-fn 'llm-context.runtime.doctor/print-report) checks)
    (if ((resolve-fn 'llm-context.runtime.doctor/healthy?) checks) 0 1)))

(defn- analysis-progress-message
  [{:keys [stage files diagnostics completed total file entities
           exact-edges references external dynamic ambiguous unresolved
           upserts deletes deferred batch-size phase
           elapsed-seconds]
    :as event}]
  (case stage
    :discover-start "Discovering source files..."
    :discover-complete
    (format "Discovered %d supported files (%d diagnostics)" files diagnostics)
    :parse-progress (format "Parsing %d/%d: %s" completed total file)
    :parse-complete (format "Parsed %d/%d files" completed total)
    :analyzer-phase-start
    (format "Analyzer phase started: %s" (name phase))
    :analyzer-phase-complete
    (format "Analyzer phase completed: %s (%.1f ms)"
            (name phase) (double (:elapsed-ms event)))
    :semantic-start "Running configured semantic providers..."
    :semantic-complete "Semantic provider stage complete"
    :analyzer-finalize-start "Finalizing analyzer graph facts..."
    :analyzer-finalize-complete
    (format (str "Graph quality: %d exact edges, %d references "
                 "(%d external, %d dynamic, %d ambiguous, %d unresolved)")
            exact-edges references external dynamic ambiguous unresolved)
    :semantic-reconcile-start "Reconciling semantic indexing jobs..."
    :semantic-reconcile-complete
    (format "Semantic reconciliation queued %d upserts and %d deletions (%d deferred)"
            upserts deletes deferred)
    :persist-start (format "Persisting %d entities in batches of %d..."
                           entities batch-size)
    :persist-progress
    (format "%s %d/%d entities"
            (if (= :retract phase) "Retracted" "Committed") completed total)
    :complete (format "Full analysis completed in %d seconds" elapsed-seconds)
    (str "Analysis stage: " (name stage))))

(defn- print-analysis-progress! [event]
  (let [timestamp (.format (java.time.ZonedDateTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern
                            "yyyy-MM-dd HH:mm:ss"))]
    (println (format "[%s] %s" timestamp (analysis-progress-message event)))
    (flush)))

(defn- run-local-analysis!
  "Run analysis outside the resident service while preserving the same
  durable progress contract used by service-owned analysis."
  [context settings full? progress-state progress-printer]
  (let [operation (if full? :full-analysis :incremental-analysis)
        progress-fn
        (fn [event]
          (analysis-progress/record! progress-state event)
          (when progress-printer
            (progress-printer event)))]
    (analysis-progress/begin! progress-state operation)
    (try
      (let [result
            (if full?
              ((resolve-fn 'llm-context.analysis.full/analyze!)
               context settings progress-fn)
              ((resolve-fn 'llm-context.analysis.incremental/analyze!)
               context settings))]
        (analysis-progress/complete! progress-state result)
        result)
      (catch Throwable error
        (analysis-progress/fail! progress-state error)
        (throw error)))))

(defmethod execute "analyze" [context _ args]
  (when-let [unknown (first (remove #{"--full" "--check"} args))]
    (throw (ex-info (str "Unknown analyze option: " unknown) {:exit-code 2})))
  (when (and (some #{"--full"} args) (some #{"--check"} args))
    (throw (ex-info "analyze --full and --check cannot be combined"
                    {:exit-code 2})))
  (let [settings (config/load-config context)
        check? (boolean (some #{"--check"} args))
        force-full? (boolean (some #{"--full"} args))
        graph-state (when-not check?
                      (with-graph
                        context settings
                        #((resolve-fn 'llm-context.store/graph-state) %)))
        _ (when (and (= :incompatible graph-state) (not force-full?))
            (throw
             (ex-info
              (str "This project graph uses an incompatible format. "
                   "Run `llm-context analyze --full` to rebuild it.")
              {:exit-code 2 :type :graph/rebuild-required})))
        ;; An interrupted batched rebuild is explicitly unavailable. A normal
        ;; analyze invocation repairs it with a new fully preflighted rebuild.
        full? (or force-full? (contains? #{:empty :unavailable} graph-state))
        progress (when-not (get-in context [:options :quiet?])
                   print-analysis-progress!)
        remote (if check?
                 unavailable
                 (remote-value context
                               {:op :analyze :full? full?}
                               {:request-timeout 86400000}))
        local-progress-state (when (and (not check?) (= unavailable remote))
                               (analysis-progress/create context))
        result
        (cond
          check?
          ((resolve-fn 'llm-context.analysis.check/check!) context settings)

          (not= unavailable remote) remote

          (= unavailable remote)
          (run-local-analysis! context settings full? local-progress-state
                               progress)

          :else
          remote)]
    (when-not (get-in context [:options :quiet?])
      (println
       (case (:mode result)
         :check
         (format (str "Validated %d files and %d canonical entities: "
                      "%d symbols, %d exact edges, %d references "
                      "(%d diagnostics)")
                 (:files result) (:entities result) (:symbols result)
                 (:exact-edges result) (:references result)
                 (count (:diagnostics result)))
         :incremental
         (format "Analyzed %d files: %d changed, %d deleted (%d diagnostics)"
                 (:files result) (:changed result) (:deleted result)
                 (count (:diagnostics result)))
         (format "Analyzed %d files into %d entities (%d diagnostics)"
                 (:files result) (:entities result)
                 (count (:diagnostics result)))))
      (when (get-in result [:semantic :enabled?])
        (println
         (format
          "Semantic indexing queued: %d upserts, %d deletions (%d deferred)"
          (get-in result [:semantic :queued-upserts] 0)
          (get-in result [:semantic :queued-deletes] 0)
          (get-in result [:semantic :deferred] 0))))
      (doseq [diagnostic (:diagnostics result)]
        (println "  " (diagnostic-message diagnostic))))
    0))

(defn- require-argument [subcommand args]
  (or (first args)
      (throw (ex-info (str "query " subcommand " requires an argument")
                      {:exit-code 2}))))

(defn- execute-query [graph semantic-client settings subcommand args]
  (case subcommand
    "stats" ((resolve-fn 'llm-context.query/stats) graph)
    "find-symbol" ((resolve-fn 'llm-context.query/find-symbol)
                   graph (require-argument subcommand args))
    "search"
    (let [{:keys [term mode source-preference intent-rerank?
                  semantic-timeout-ms]}
          ((resolve-fn 'llm-context.query/parse-search-args) args)]
      ((resolve-fn 'llm-context.query/search-explain)
       graph semantic-client settings term
       {:mode mode :source-preference source-preference
        :intent-rerank? intent-rerank?
        :semantic-timeout-ms semantic-timeout-ms}))
    "callers" ((resolve-fn 'llm-context.query/callers)
               graph (require-argument subcommand args))
    "callees" ((resolve-fn 'llm-context.query/callees-command) graph args)
    "trace" ((resolve-fn 'llm-context.query/trace-command)
             graph settings args)
    "entry-points" ((resolve-fn 'llm-context.query/entry-points) graph)
    "effects" ((resolve-fn 'llm-context.query/effects) graph)
    "unresolved" ((resolve-fn 'llm-context.query/unresolved-command)
                  graph args)
    ("topics" "registrations" "dispatchers" "subscribers"
     "state-readers" "state-writers")
    ((resolve-fn 'llm-context.query/topics-command) graph subcommand args)
    (throw (ex-info (str "Unknown query: " subcommand) {:exit-code 2}))))

(defmethod execute "query" [context _ args]
  (let [subcommand (or (first args) "stats")
        command-args (vec (next args))
        remote (remote-value context {:op :query :subcommand subcommand
                                      :args command-args})]
    (let [value
          (if-not (= unavailable remote)
            remote
            (let [settings (config/load-config context)]
              (with-compatible-graph context settings
                #(execute-query % nil settings subcommand command-args))))]
      (if (= "search" subcommand)
        (let [explain? (some #{"--explain"} command-args)
              retrieval (:retrieval value)]
          (when (and (not explain?)
                     (contains? #{:timeout :unavailable :error}
                                (:status retrieval)))
            (binding [*out* *err*]
              (println
               (str "warning: semantic retrieval "
                    (name (:status retrieval))
                    "; returning Datalevin FTS results"
                    (when-let [detail (:error retrieval)]
                      (str " (" detail ")"))))))
          (pprint/pprint (if explain? value (:results value))))
        (pprint/pprint value)))
    0))

(defn- local-semantic-status [context settings]
  (let [progress (analysis-progress/read-state context)]
    (try
      (assoc
       (with-graph
         context settings
         #((resolve-fn 'llm-context.semantic.state/semantic-summary)
           % :lateon-code (System/currentTimeMillis)))
       :graph-state :ready
       :analysis-progress progress)
      (catch Throwable error
        {:graph-state (if (= :running (:state progress)) :updating :unknown)
         :availability :unavailable
         :analysis-progress progress
         :error (.getMessage error)}))))

(defn- semantic-status [context settings]
  (let [remote (remote-value context {:op :semantic-status})]
    (if (= unavailable remote)
      (assoc (local-semantic-status context settings)
             :runtime {:status :not-running})
      remote)))

(defn- semantic-synchronized? [status]
  (and (zero? (:pending status))
       (zero? (:leased status))
       (zero? (:failed status))
       (zero? (:dirty status))
       (= :complete (:completeness status))))

(defn- parse-semantic-sync-options [arguments]
  (loop [remaining (seq arguments)
         parsed {:wait? false :timeout-ms nil}]
    (if-let [argument (first remaining)]
      (case argument
        "--wait" (recur (next remaining) (assoc parsed :wait? true))
        "--timeout-ms"
        (let [timeout-ms (some-> (second remaining) parse-long)]
          (when-not (pos-int? timeout-ms)
            (throw
             (ex-info "semantic sync --timeout-ms requires a positive integer"
                      {:exit-code 2})))
          (recur (nnext remaining) (assoc parsed :timeout-ms timeout-ms)))
        (throw (ex-info (str "Unknown semantic sync option: " argument)
                        {:exit-code 2})))
      parsed)))

(defn- parse-semantic-status-options [arguments]
  (loop [remaining (seq arguments)
         parsed {:watch? false :interval-ms 2000}]
    (if-let [argument (first remaining)]
      (case argument
        "--watch" (recur (next remaining) (assoc parsed :watch? true))
        "--interval-ms"
        (let [interval-ms (some-> (second remaining) parse-long)]
          (when-not (pos-int? interval-ms)
            (throw
             (ex-info "semantic status --interval-ms requires a positive integer"
                      {:exit-code 2})))
          (recur (nnext remaining) (assoc parsed :interval-ms interval-ms)))
        (throw (ex-info (str "Unknown semantic status option: " argument)
                        {:exit-code 2})))
      parsed)))

(defn- print-semantic-status! [status]
  (println (str "\n# semantic status observed at "
                (java.time.Instant/now)))
  (pprint/pprint status)
  (flush))

(defmethod execute "semantic" [context _ args]
  (let [subcommand (or (first args) "status")
        options (set (next args))
        settings (config/load-config context)]
    (case subcommand
      "status"
      (let [{:keys [watch? interval-ms]}
            (parse-semantic-status-options (next args))]
        (if-not watch?
          (pprint/pprint (semantic-status context settings))
          (loop []
            (print-semantic-status!
             (try
               (semantic-status context settings)
               (catch Throwable error
                 {:graph-state :unknown
                  :availability :unavailable
                  :error (.getMessage error)})))
            (Thread/sleep interval-ms)
            (recur))))

      "sync"
      (let [{:keys [wait? timeout-ms]}
            (parse-semantic-sync-options (next args))]
        (let [initial (remote-value context {:op :semantic-sync})]
          (when (= unavailable initial)
            (throw
             (ex-info "Semantic synchronization requires a running project service"
                      {:exit-code 2})))
          (if-not wait?
            (pprint/pprint initial)
            (let [timeout-ms (or timeout-ms
                                 (+ (get-in settings
                                            [:semantic :lateon-code
                                             :startup-timeout-ms])
                                    (get-in settings
                                            [:semantic :lateon-code
                                             :visibility-timeout-ms])))
                  deadline (+ (System/currentTimeMillis) timeout-ms)]
              (loop [status initial]
                (let [runtime (:runtime status)
                      worker-status (:worker-status runtime)]
                  (cond
                    (= :failed worker-status)
                    (throw
                     (ex-info
                      (str "LateOn semantic worker failed"
                           (when-let [detail (:worker-detail runtime)]
                             (str ": " detail)))
                      {:exit-code 1 :status status}))

                    (pos? (:failed status))
                    (throw
                     (ex-info "Semantic synchronization has failed jobs"
                              {:exit-code 1 :status status}))

                    (contains? #{:disabled :unavailable :failed :not-running}
                               (:status runtime))
                    (throw
                     (ex-info
                      (str "LateOn semantic runtime is not ready: "
                           (name (:status runtime))
                           (when-let [reason (:reason runtime)]
                             (str " (" (name reason) ")"))
                           (when-let [detail (:detail runtime)]
                             (str " - " detail)))
                      {:exit-code 1 :status status}))

                    (not (contains? #{:starting :ready}
                                    (:status runtime)))
                    (throw
                     (ex-info
                      "LateOn semantic runtime reported an unknown state"
                      {:exit-code 1 :status status}))

                    (semantic-synchronized? status)
                    (pprint/pprint status)

                    (>= (System/currentTimeMillis) deadline)
                    (throw
                     (ex-info "Timed out waiting for semantic synchronization"
                              {:exit-code 1 :status status}))

                    :else
                    (do
                      (Thread/sleep 250)
                      (recur (semantic-status context settings))))))))))

      "failures"
      (do
        (when (seq options)
          (throw (ex-info "semantic failures does not accept options"
                          {:exit-code 2})))
        (let [value (remote-value context {:op :semantic-failures})]
          (when (= unavailable value)
            (throw (ex-info
                    "Semantic failure inspection requires a running project service"
                    {:exit-code 2})))
          (pprint/pprint value)))

      "dirty"
      (do
        (when (seq options)
          (throw (ex-info "semantic dirty does not accept options"
                          {:exit-code 2})))
        (let [value (remote-value context {:op :semantic-dirty})]
          (when (= unavailable value)
            (throw (ex-info
                    "Semantic dirty inspection requires a running project service"
                    {:exit-code 2})))
          (pprint/pprint value)))

      "retry"
      (do
        (when-let [unknown (first (remove #{"--failed" "--wait"} options))]
          (throw (ex-info (str "Unknown semantic retry option: " unknown)
                          {:exit-code 2})))
        (when-not (contains? options "--failed")
          (throw (ex-info "semantic retry currently requires --failed"
                          {:exit-code 2})))
        (let [value (remote-value context {:op :semantic-retry-failed})]
          (when (= unavailable value)
            (throw (ex-info
                    "Semantic retry requires a running project service"
                    {:exit-code 2})))
          (if (contains? options "--wait")
            (execute context "semantic" ["sync" "--wait"])
            (pprint/pprint value))))

      (throw (ex-info (str "Unknown semantic command: " subcommand)
                      {:exit-code 2})))
    0))

(defmethod execute "stats" [context _ _]
  (execute context "query" ["stats"]))

(defmethod execute "entry-points" [context _ _]
  (execute context "query" ["entry-points"]))

(defmethod execute "side-effects" [context _ _]
  (execute context "query" ["effects"]))

(defn- parse-context-args [args defaults]
  (loop [remaining (seq args) result defaults]
    (if-let [arg (first remaining)]
      (case arg
        "--intent" (recur (next remaining) (assoc result :intent? true))
        "--source-preference"
        (if-let [value (second remaining)]
          (recur (nnext remaining)
                 (assoc result :source-preference
                        ((resolve-fn 'llm-context.source-role/normalize-preference)
                         value)))
          (throw (ex-info "--source-preference requires auto, production, test, or none"
                          {:exit-code 2})))
        "--semantic-timeout-ms"
        (if-let [value (second remaining)]
          (let [timeout (parse-long value)]
            (when-not (pos-int? timeout)
              (throw (ex-info "--semantic-timeout-ms requires a positive integer"
                              {:exit-code 2})))
            (recur (nnext remaining)
                   (assoc result :semantic-timeout-ms timeout)))
          (throw (ex-info "--semantic-timeout-ms requires a positive integer"
                          {:exit-code 2})))
        "--seed-mode"
        (if-let [value (second remaining)]
          (recur (nnext remaining)
                 (assoc result :seed-mode
                        ((resolve-fn 'llm-context.intent/normalize-seed-mode)
                         value)))
          (throw (ex-info "--seed-mode requires auto, single, or multi"
                          {:exit-code 2})))
        "--max-seeds"
        (if-let [value (second remaining)]
          (let [maximum (parse-long value)]
            (when-not (pos-int? maximum)
              (throw (ex-info "--max-seeds requires a positive integer"
                              {:exit-code 2})))
            (recur (nnext remaining) (assoc result :max-seeds maximum)))
          (throw (ex-info "--max-seeds requires a positive integer"
                          {:exit-code 2})))
        "--max-tokens" (if-let [value (second remaining)]
                         (recur (nnext remaining)
                                (assoc result :max-tokens (parse-long value)))
                         (throw (ex-info "--max-tokens requires an integer" {:exit-code 2})))
        "--depth" (if-let [value (second remaining)]
                    (recur (nnext remaining) (assoc result :depth (parse-long value)))
                    (throw (ex-info "--depth requires an integer" {:exit-code 2})))
        "--format" (if-let [value (second remaining)]
                     (recur (nnext remaining) (assoc result :format value))
                     (throw (ex-info "--format requires edn or markdown" {:exit-code 2})))
        "--direction"
        (if-let [value (second remaining)]
          (let [directions
                (case value
                  "outgoing" #{:outgoing}
                  "incoming" #{:incoming}
                  "both" #{:outgoing :incoming}
                  (throw (ex-info
                          "--direction requires outgoing, incoming, or both"
                          {:exit-code 2})))]
            (recur (nnext remaining) (assoc result :directions directions)))
          (throw (ex-info "--direction requires a value" {:exit-code 2})))
        "--edge-kind"
        (if-let [value (second remaining)]
          (recur (nnext remaining)
                 (update result :edge-kinds
                         (fnil conj #{}) (keyword "edge.kind" value)))
          (throw (ex-info "--edge-kind requires a value" {:exit-code 2})))
        (if (:focus result)
          (throw (ex-info (str "Unexpected context argument: " arg) {:exit-code 2}))
          (recur (next remaining) (assoc result :focus arg))))
      result)))

(defmethod execute "context" [cli-context _ args]
  (let [settings (config/load-config cli-context)
        options (parse-context-args
                 args {:max-tokens (get-in settings [:context :default-max-tokens])
                       :depth (get-in settings [:context :trace-depth])
                       :source-preference
                       (get-in settings [:context :intent-source-preference])
                       :seed-mode (get-in settings [:context :intent-seed-mode])
                       :max-seeds (get-in settings [:context :intent-max-seeds])
                       :intent-rerank?
                       (get-in settings [:context :intent-rerank])
                       :format "markdown"})]
    (when-not (:focus options)
      (throw (ex-info "context requires a symbol name, ID, or --intent query"
                      {:exit-code 2})))
    (when-not (and (pos-int? (:max-tokens options)) (nat-int? (:depth options)))
      (throw (ex-info "context budgets must be positive tokens and non-negative depth"
                      {:exit-code 2})))
    (let [format (keyword (:format options))
          _ (when-not (contains? #{:edn :markdown} format)
              (throw (ex-info (str "Unsupported context format: " (:format options))
                              {:exit-code 2})))
          remote (remote-value cli-context
                               {:op :context :options (assoc options :format format)})]
      (if-not (= unavailable remote)
        (if (= :edn format) (pprint/pprint remote) (print remote))
        (with-compatible-graph cli-context settings
          (fn [graph]
            (let [packet
                  (if (:intent? options)
                    (let [attempt
                          ((resolve-fn
                            'llm-context.query/semantic-search-attempt)
                           nil settings (:focus options)
                           {:mode :hybrid
                            :semantic-timeout-ms
                            (:semantic-timeout-ms options)
                            :candidate-count
                            (get-in settings [:context
                                              :intent-candidate-count])})
                          search
                          ((resolve-fn
                            'llm-context.query/search-explain-with-attempt)
                           graph settings (:focus options) attempt
                           {:source-preference (:source-preference options)
                            :intent-rerank? (:intent-rerank? options)
                            :seed-mode (:seed-mode options)
                            :max-seeds (:max-seeds options)})
                          resolution
                          ((resolve-fn
                            'llm-context.context/resolve-intent-focus)
                           (:focus options) search)]
                      ((resolve-fn 'llm-context.context/build-from-seeds)
                       graph options resolution))
                    ((resolve-fn 'llm-context.context/build) graph options))]
              (case format
                :edn (pprint/pprint packet)
                :markdown (print ((resolve-fn 'llm-context.context/markdown) packet))
                (throw (ex-info (str "Unsupported context format: " (:format options))
                                {:exit-code 2})))))))
    0)))

(defn- parse-export-args [args]
  (loop [remaining (seq args) result {:format :edn :output nil}]
    (if-let [arg (first remaining)]
      (case arg
        "--format" (if-let [value (second remaining)]
                     (recur (nnext remaining) (assoc result :format (keyword value)))
                     (throw (ex-info "--format requires edn, json, jsonl, or markdown"
                                     {:exit-code 2})))
        "--output" (if-let [value (second remaining)]
                     (recur (nnext remaining) (assoc result :output value))
                     (throw (ex-info "--output requires a path or -" {:exit-code 2})))
        (throw (ex-info (str "Unexpected export argument: " arg) {:exit-code 2})))
      result)))

(defmethod execute "export" [cli-context _ args]
  (let [{:keys [format output]} (parse-export-args args)
        settings (config/load-config cli-context)
        remote (remote-value cli-context {:op :export :format format})]
    (let [rendered (if-not (= unavailable remote)
                     remote
                     (with-compatible-graph cli-context settings
                       #((resolve-fn 'llm-context.export/render) % format)))]
        (if (or (nil? output) (= "-" output))
          (print rendered)
          (let [path (.normalize (.resolve ^java.nio.file.Path (:root cli-context) output))]
            (when-let [parent (.getParent path)]
              (java.nio.file.Files/createDirectories
               parent (make-array java.nio.file.attribute.FileAttribute 0)))
            (java.nio.file.Files/writeString path rendered
                                             (make-array java.nio.file.OpenOption 0))
            (println "Wrote" (str path)))))
    0))

(defmethod execute "summary" [cli-context _ args]
  (execute cli-context "export" (concat ["--format" "markdown"] args)))

(defmethod execute "integrate" [cli-context _ args]
  (let [target (some-> (first args) keyword)
        force? (boolean (some #{"--force"} (next args)))]
    (when-not target
      (throw (ex-info "integrate requires claude, codex, or generic"
                      {:exit-code 2})))
    (when-let [unknown (first (remove #{"--force"} (next args)))]
      (throw (ex-info (str "Unexpected integrate argument: " unknown)
                      {:exit-code 2})))
    (println "Installed"
             (str ((resolve-fn 'llm-context.integrations/install!)
                   cli-context target force?)))
    0))

(defmethod execute "service" [cli-context _ args]
  (case (or (first args) "status")
    "start"
    (let [result ((resolve-fn 'llm-context.service.daemon/start!)
                  cli-context)]
      (println (format "service %s (pid %d); log: %s"
                       (name (:status result)) (:pid result)
                       (:log-path result)))
      0)
    "foreground"
    ((resolve-fn 'llm-context.service.server/start!) cli-context)
    "status"
    (do
      (if (service-client/available? cli-context)
        (pprint/pprint
         (remote-value cli-context {:op :semantic-status}))
        (println "not running"))
      0)
    "stop" (let [response (service-client/request cli-context {:op :stop})]
             (cond
               (:ok response) (do (println "stopped") 0)
               (nil? response) (do (println "not running") 0)
               :else
               (throw
                (ex-info (or (:error response)
                             "Unable to stop the project service")
                         {:exit-code (or (:exit-code response) 1)
                          :type (:type response)}))))
    (throw (ex-info (str "Unknown service command: " (first args))
                    {:exit-code 2}))))

(defmethod execute :default [_ command _]
  (throw (ex-info (str "Unknown command: " command)
                  {:exit-code 2 :command command})))

(defn- print-error! [error]
  (let [{:keys [entity explain]} (ex-data error)]
    (println (.getMessage error))
    (when entity
      (println "Offending entity:" (pr-str entity)))
    (when-let [problem (first (:clojure.spec.alpha/problems explain))]
      (println "Validation failure:"
               (pr-str {:path (:path problem)
                        :value (:val problem)
                        :predicate (str (:pred problem))})))))

(defn run [args]
  (try
    (let [{:keys [project command args] :as options} (parse-args args)
          needs-project? (not (#{"help" "version" "models"} command))
          context (when needs-project? (project/context project))]
      (execute (assoc context :options options) command args))
    (catch clojure.lang.ExceptionInfo error
      (binding [*out* *err*]
        (print-error! error))
      (or (:exit-code (ex-data error)) 1))))
