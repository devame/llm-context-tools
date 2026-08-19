(ns llm-context.cli
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [llm-context.accelerator :as accelerator]
            [llm-context.config :as config]
            [llm-context.health :as health]
            [llm-context.model-packages :as model-packages]
            [llm-context.project :as project]
            [llm-context.runtime.setup :as runtime-setup]
            [llm-context.semantic.runtime :as semantic-runtime]
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
      (throw (ex-info (:error response) {:exit-code (:exit-code response)
                                         :type (:type response)})))
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
       "    --no-service       Do not start the semantic indexing service\n"
       "  query                Query the semantic graph\n"
       "  semantic             Inspect or synchronize LateOn indexing\n"
       "  maintenance          Inspect storage or create verified maintenance copies\n"
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
       "  setup                Check GPU/CUDA prerequisites and offer safe fixes\n"
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
  [{:keys [level kind file path language message size row column count]}]
  (let [count (long (or count 1))]
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
           (or file path message (pr-str kind)))
         (when (> count 1)
           (format " (%d occurrences)" count)))))

(defn- diagnostic-count [diagnostics]
  (reduce + 0 (map #(long (or (:count %) 1)) diagnostics)))

(defmethod execute "doctor" [context _ _]
  (let [checks ((resolve-fn 'llm-context.runtime.doctor/check)
                context (config/load-config context))]
    ((resolve-fn 'llm-context.runtime.doctor/print-report) checks)
    (if ((resolve-fn 'llm-context.runtime.doctor/healthy?) checks) 0 1)))

(defmethod execute "setup" [_ _ args]
  (let [unknown (first (remove #{"--install-cudnn" "--yes"} args))]
    (when unknown
      (throw (ex-info (str "Unknown setup option: " unknown) {:exit-code 2})))
    (runtime-setup/run!
     {:install-cudnn? (boolean (some #{"--install-cudnn"} args))
      :yes? (boolean (some #{"--yes"} args))})))

(defn- analysis-progress-message
  [{:keys [stage files diagnostics completed total file entities
           exact-edges references external dynamic ambiguous unresolved
           upserts deletes deferred batch-size phase
           written skipped
           elapsed-seconds archive-path probe-path usable-bytes
           minimum-free-space-bytes]
    :as event}]
  (case stage
    :discover-start "Discovering source files..."
    :legacy-graph-archived
    (str "Archived the interrupted legacy graph at " archive-path
         "; rebuilding from a fresh database...")
    :storage-preflight
    (format "Storage preflight: %.1f GiB usable at %s (%.1f GiB reserve)"
            (/ (double usable-bytes) 1073741824.0) probe-path
            (/ (double minimum-free-space-bytes) 1073741824.0))
    :storage-sample
    (format "Storage growth: %.2f GiB"
            (/ (double (or (:operation-growth-bytes event) 0))
               1073741824.0))
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
    (case phase
      :upsert
      (format "Examined %d/%d entities (%d written, %d unchanged)"
              completed total (or written 0) (or skipped 0))
      :cleanup (format "Removed %d/%d stale entities" completed total)
      (format "Processed %d/%d entities" completed total))
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

(defn- maybe-start-semantic-service!
  "Start the project service after local analysis has queued semantic work.
  The analysis must finish first so the service never competes with the
  analyzer for the project's Datalevin writer lock."
  [context result local-analysis? check? no-service?]
  (when (and local-analysis?
             (not check?)
             (not no-service?)
             (true? (get-in result [:semantic :enabled?])))
    (try
      {:result ((resolve-fn 'llm-context.service.daemon/start!) context)}
      (catch Exception error
        {:error error}))))

(defn- semantic-acceleration-warning [context settings]
  (let [lateon-settings (get-in settings [:semantic :lateon-code])
        enabled? (and (true? (:enabled lateon-settings))
                      (= :background (:mode lateon-settings))
                      (contains? (set (get-in settings [:semantic :providers]))
                                 :lateon-code))
        executable (when enabled?
                     (semantic-runtime/find-executable
                      (first (:next-plaid-command lateon-settings))))
        model-path (when (and enabled? executable)
                     (semantic-runtime/model-path context lateon-settings))]
    (when (and enabled? executable model-path)
      (try
        (let [selection (accelerator/resolve-runtime
                         lateon-settings executable model-path)]
          (when-let [reasons (seq (:fallback-reasons selection))]
            (format
             "Warning: semantic inference is using %s. GPU acceleration was not selected; %s. Run 'llm-context doctor' for details."
             (accelerator/describe selection)
             (accelerator/fallback-actions reasons))))
        (catch clojure.lang.ExceptionInfo error
          (format
           "Warning: semantic GPU acceleration is unavailable: %s. Run 'llm-context doctor' for details."
           (.getMessage error)))))))

(defn- print-semantic-service-start! [service-result]
  (let [{:keys [status pid log-path]} service-result
        state (if (= :running status) "started" "starting")
        pid-text (if pid (format " (pid %d)" pid) "")]
    (println (format "Semantic indexing service %s%s; log: %s"
                     state pid-text log-path))))

(defn- semantic-service-warning [context]
  (try
    (let [response (service-client/request
                    context {:op :semantic-status}
                    {:request-timeout 1000})
          runtime (get-in response [:value :runtime])
          first-alert (first (get-in response [:value :health :alerts]))]
      (cond
        (and response (not (:ok response)))
        (str "Warning: project service health is unavailable: "
             (:error response) ". Run 'llm-context doctor' for details.")

        first-alert
        (str "Warning: " (:detail first-alert)
             (when-let [action (:action first-alert)]
               (str " Action: " action ".")))

        (:runtime-diagnostic runtime)
        (let [{:keys [detail action]} (:runtime-diagnostic runtime)]
          (str "Warning: semantic inference is degraded: " detail
               " Action: " action
               ". Run 'llm-context doctor' for details."))

        (= :failed (:status runtime))
        (str "Warning: semantic inference failed to start: "
             (or (:detail runtime) "unknown runtime error")
             ". Run 'llm-context doctor' for details.")))
    (catch Throwable error
      (str "Warning: unable to read project service health: "
           (.getMessage error) ". Run 'llm-context doctor' for details."))))

(defmethod execute "analyze" [context _ args]
  (when-let [unknown (first (remove #{"--full" "--check" "--no-service"} args))]
    (throw (ex-info (str "Unknown analyze option: " unknown) {:exit-code 2})))
  (when (and (some #{"--full"} args) (some #{"--check"} args))
    (throw (ex-info "analyze --full and --check cannot be combined"
                    {:exit-code 2})))
  (let [settings (config/load-config context)
        check? (boolean (some #{"--check"} args))
        no-service? (boolean (some #{"--no-service"} args))
        _ (when (and (not check?) (not no-service?))
            ((resolve-fn 'llm-context.service.daemon/ensure-compatible!)
             context))
        force-full? (boolean (some #{"--full"} args))
        graph-state (when-not check?
                      (with-graph
                        context settings
                        #((resolve-fn 'llm-context.store/graph-state) %)))
        ;; An interrupted batched rebuild is explicitly unavailable. A normal
        ;; analyze invocation repairs it with a new fully preflighted rebuild.
        ;; The same automatic boundary also upgrades older graph formats.
        full? (or force-full?
                   (contains? #{:empty :incompatible :unavailable} graph-state))
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
    (let [service-start
          (maybe-start-semantic-service!
           context result (= unavailable remote) check? no-service?)]
      (when-not (get-in context [:options :quiet?])
        (println
         (case (:mode result)
           :check
           (format (str "Validated %d files and %d canonical entities: "
                        "%d symbols, %d exact edges, %d references "
                        "(%d diagnostics)")
                   (:files result) (:entities result) (:symbols result)
                   (:exact-edges result) (:references result)
                   (diagnostic-count (:diagnostics result)))
           :incremental
           (format "Analyzed %d files: %d changed, %d deleted (%d diagnostics)"
                   (:files result) (:changed result) (:deleted result)
                   (diagnostic-count (:diagnostics result)))
           (format "Analyzed %d files into %d entities (%d diagnostics)"
                   (:files result) (:entities result)
                   (diagnostic-count (:diagnostics result)))))
        (when (get-in result [:semantic :enabled?])
          (println
           (format
            "Semantic indexing queued: %d upserts, %d deletions (%d deferred)"
            (get-in result [:semantic :queued-upserts] 0)
            (get-in result [:semantic :queued-deletes] 0)
            (get-in result [:semantic :deferred] 0))))
        (doseq [diagnostic (:diagnostics result)]
          (println "  " (diagnostic-message diagnostic)))
        (when-not check?
          (when-let [warning (semantic-acceleration-warning context settings)]
            (println warning)))
        (when-let [started (:result service-start)]
          (print-semantic-service-start! started))
        (when (and (not check?) (not no-service?))
          (when-let [warning (semantic-service-warning context)]
            (println warning))))
      (if-let [error (:error service-start)]
        (do
          (binding [*out* *err*]
            (println (str "Semantic indexing service failed to start: "
                          (or (.getMessage error) (str error)))))
          1)
        0))))

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
    (let [status
          (if (= unavailable remote)
            (assoc (local-semantic-status context settings)
                   :service-state :not-running
                   :runtime {:status (if (get-in settings
                                                 [:semantic :lateon-code
                                                  :enabled])
                                       :not-running :disabled)
                             :worker-status
                             (if (get-in settings
                                         [:semantic :lateon-code :enabled])
                               :not-running :disabled)
                             :watcher-status
                             (if (get-in settings [:service :watch])
                               :not-running :disabled)
                             :query-router-status
                             (if (get-in settings [:context :query-router
                                                   :enabled])
                               :not-running :disabled)
                             :candidate-reranker-status
                             (if (get-in settings
                                         [:context :candidate-reranker
                                          :enabled])
                               :not-running :disabled)})
            remote)
          status (if (:health status)
                   status
                   (assoc status :health
                          (health/semantic-health
                           status (System/currentTimeMillis))))
          diagnostics (get-in status [:analysis-progress :result :diagnostics])
          skipped-diagnostics
          (filter #(= :aggregate-analysis-skipped (:kind %)) diagnostics)
          skipped-paths (distinct (keep :file skipped-diagnostics))
          skipped-files (if (seq skipped-paths)
                          (count skipped-paths)
                          (count skipped-diagnostics))]
      (if (:aggregate-analysis status)
        (assoc-in status [:aggregate-analysis :skipped-files] skipped-files)
        status))))

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
         parsed {:watch? false :verbose? false :interval-ms 2000}]
    (if-let [argument (first remaining)]
      (case argument
        "--watch" (recur (next remaining) (assoc parsed :watch? true))
        "--verbose" (recur (next remaining) (assoc parsed :verbose? true))
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

(defn- print-verbose-semantic-status! [status]
  (println (str "\n# semantic status observed at "
                (java.time.Instant/now)))
  (pprint/pprint status)
  (flush))

(defn- pending-document-count [status]
  (cond
    (number? (:pending status)) (long (:pending status))
    (and (number? (:desired status)) (number? (:indexed status)))
    (max 0 (- (long (:desired status)) (long (:indexed status))))
    :else nil))

(defn- semantic-processing-speed
  "Return documents per second. Prefer the worker's bounded recent window,
  then its cumulative compatibility rate, then CLI polling deltas."
  [status previous-status elapsed-ms]
  (let [pending (pending-document-count status)
        health-state (get-in status [:health :state])
        recent-symbols-per-second
        (get-in status [:runtime :worker-progress
                        :recent-completed-symbols-per-second])
        documents-per-minute
        (get-in status [:runtime :worker-progress :documents-per-minute])]
    (cond
      (zero? (or pending 0)) 0.0
      (contains? #{:stalled :failed :recovering} health-state) 0.0
      (number? recent-symbols-per-second)
      (double recent-symbols-per-second)
      (number? documents-per-minute)
      (/ (double documents-per-minute) 60.0)
      (and previous-status (pos? (long (or elapsed-ms 0)))
           (number? (:indexed status))
           (number? (:indexed previous-status)))
      (/ (* 1000.0
            (max 0 (- (long (:indexed status))
                      (long (:indexed previous-status)))))
         (double elapsed-ms))
      :else 0.0)))

(defn- semantic-status-summary
  ([status] (semantic-status-summary status nil nil))
  ([status previous-status elapsed-ms]
   (if-let [pending (pending-document-count status)]
     (format (str "%d/%d documents indexed; %d pending, %d leased, "
                  "%d failed, %d dirty; processing speed: %.2f docs/s")
             (long (or (:indexed status) 0)) (long (or (:desired status) 0))
             pending (long (or (:leased status) 0))
             (long (or (:failed status) 0)) (long (or (:dirty status) 0))
             (semantic-processing-speed status previous-status elapsed-ms))
     (str "semantic status unavailable"
          (when-let [error (:error status)] (str ": " error))))))

(defn- aggregate-analysis-summary [status]
  (if-let [{:keys [aggregates memberships semantic-documents skipped-files]}
           (:aggregate-analysis status)]
    (format "Aggregate analysis: %d aggregates, %d memberships; semantic documents: %s; skipped files: %d"
            (long (or aggregates 0))
            (long (or memberships 0))
            (if (keyword? semantic-documents)
              (name semantic-documents)
              (str (or semantic-documents :unknown)))
            (long (or skipped-files 0)))
    "Aggregate analysis unavailable"))

(defn- print-semantic-summary!
  ([status] (print-semantic-summary! status nil nil))
  ([status previous-status elapsed-ms]
   (println (semantic-status-summary status previous-status elapsed-ms))
   (println (aggregate-analysis-summary status))
   (doseq [{:keys [id severity detail action]}
           (get-in status [:health :alerts])
           :when (not (some #(= id (:id %))
                            (get-in previous-status [:health :alerts])))]
     (println (str (str/capitalize (name severity)) ": " detail
                   (when (seq action) (str " Action: " action ".")))))
   (when-let [diagnostic (get-in status [:runtime :runtime-diagnostic])]
     (when (and (not= diagnostic
                      (get-in previous-status [:runtime :runtime-diagnostic]))
                (not-any? #(= (:kind diagnostic) (:kind %))
                          (get-in status [:health :alerts])))
       (println
        (str (if (get diagnostic :degrades-runtime? true)
               "Warning: semantic inference is degraded: "
               "Warning: ")
             (:detail diagnostic) " Action: " (:action diagnostic)
             ". Run 'llm-context doctor' for details."))))
   (when-let [inference (get-in status [:runtime :inference])]
     (when (and (seq (:fallback-reasons inference))
                (not= (:fallback-reasons inference)
                      (get-in previous-status [:runtime :inference
                                               :fallback-reasons])))
       (println
        (str "Warning: semantic inference is using "
             (accelerator/describe inference)
             ". Run 'llm-context doctor' for details."))))
   (flush)))

(defmethod execute "semantic" [context _ args]
  (let [subcommand (or (first args) "status")
        options (set (next args))
        settings (config/load-config context)]
    (case subcommand
      "status"
      (let [{:keys [watch? verbose? interval-ms]}
            (parse-semantic-status-options (next args))]
        (if-not watch?
          (let [status (semantic-status context settings)]
            (if verbose?
              (pprint/pprint status)
              (print-semantic-summary! status)))
          (loop [previous-status nil
                 previous-observed-at nil]
            (let [observed-at (System/nanoTime)
                  status
                  (try
                    (semantic-status context settings)
                    (catch Throwable error
                      {:graph-state :unknown
                       :availability :unavailable
                       :error (.getMessage error)}))
                  elapsed-ms
                  (when previous-observed-at
                    (long (/ (- observed-at previous-observed-at) 1000000)))]
              (if verbose?
                (print-verbose-semantic-status! status)
                (print-semantic-summary! status previous-status elapsed-ms))
              (Thread/sleep interval-ms)
              (recur status observed-at)))))

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

                    (not (contains? #{:starting :recovering :ready}
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

(defn- compact-copy-destination [cli-context args]
  (loop [remaining (seq args) output nil]
    (if-let [argument (first remaining)]
      (case argument
        "--output"
        (if-let [value (second remaining)]
          (recur (nnext remaining) value)
          (throw (ex-info "maintenance compact-copy --output requires a path"
                          {:exit-code 2})))
        (throw (ex-info (str "Unknown maintenance compact-copy option: " argument)
                        {:exit-code 2})))
      (let [relative (or output
                         (str ".llm-context/maintenance/graph-copy-"
                              (System/currentTimeMillis)))]
        (.normalize
         (let [path (java.nio.file.Paths/get relative (make-array String 0))]
           (if (.isAbsolute path)
             path
             (.resolve ^java.nio.file.Path (:root cli-context) path))))))))

(defn- parse-cleanup-options [args]
  (loop [remaining (seq args) options {:apply? false}]
    (if-let [argument (first remaining)]
      (case argument
        "--older-than-days"
        (if-let [value (second remaining)]
          (let [days (parse-long value)]
            (when-not (and days (pos? days))
              (throw (ex-info "--older-than-days requires a positive integer"
                              {:exit-code 2})))
            (recur (nnext remaining) (assoc options :older-than-days days)))
          (throw (ex-info "maintenance cleanup --older-than-days requires a value"
                          {:exit-code 2})))
        "--apply" (recur (next remaining) (assoc options :apply? true))
        (throw (ex-info (str "Unknown maintenance cleanup option: " argument)
                        {:exit-code 2})))
      (do
        (when-not (:older-than-days options)
          (throw (ex-info "maintenance cleanup requires --older-than-days DAYS"
                          {:exit-code 2})))
        options))))

(defn- parse-supervisor-options [args]
  (loop [remaining (seq args) options {}]
    (if-let [argument (first remaining)]
      (case argument
        "--format" (if-let [value (second remaining)]
                     (recur (nnext remaining) (assoc options :format (keyword value)))
                     (throw (ex-info "service supervisor --format requires a value"
                                     {:exit-code 2})))
        "--executable" (if-let [value (second remaining)]
                         (recur (nnext remaining) (assoc options :executable value))
                         (throw (ex-info "service supervisor --executable requires a path"
                                         {:exit-code 2})))
        "--output" (if-let [value (second remaining)]
                     (recur (nnext remaining) (assoc options :output value))
                     (throw (ex-info "service supervisor --output requires a path"
                                     {:exit-code 2})))
        (throw (ex-info (str "Unknown service supervisor option: " argument)
                        {:exit-code 2})))
      (do
        (when-not (:format options)
          (throw (ex-info "service supervisor requires --format systemd|launchd|windows"
                          {:exit-code 2})))
        options))))

(defmethod execute "maintenance" [cli-context _ args]
  (case (or (first args) "status")
    "status"
    (do
      (when-let [unknown (second args)]
        (throw (ex-info (str "Unknown maintenance status option: " unknown)
                        {:exit-code 2})))
      (pprint/pprint
       ((resolve-fn 'llm-context.storage/inventory)
        cli-context (config/load-config cli-context)))
      0)
    "compact-copy"
    (let [destination (compact-copy-destination cli-context (next args))
          settings (config/load-config cli-context)
          remote (remote-value
                  cli-context
                  {:op :maintenance-compact-copy
                   :destination (str destination)}
                  {:request-timeout 86400000})
          result
          (if-not (= unavailable remote)
            remote
            (with-compatible-graph
              cli-context settings
              #((resolve-fn 'llm-context.store/compact-copy!) % destination)))]
      (pprint/pprint result)
      0)
    "cleanup"
    (let [{:keys [apply? older-than-days]} (parse-cleanup-options (next args))
          operation (if apply?
                      'llm-context.storage/apply-cleanup!
                      'llm-context.storage/cleanup-plan)]
      (pprint/pprint
       ((resolve-fn operation) cli-context (config/load-config cli-context)
        older-than-days))
      0)
    (throw (ex-info (str "Unknown maintenance command: " (first args))
                    {:exit-code 2}))))

(defmethod execute "service" [cli-context _ args]
  (case (or (first args) "status")
    "supervisor"
    (let [{:keys [output] :as options} (parse-supervisor-options (next args))
          rendered ((resolve-fn 'llm-context.supervisor/render)
                    cli-context options)]
      (if output
        (let [path (.normalize
                    (.resolve ^java.nio.file.Path (:root cli-context) output))]
          (when-let [parent (.getParent path)]
            (java.nio.file.Files/createDirectories
             parent (make-array java.nio.file.attribute.FileAttribute 0)))
          (java.nio.file.Files/writeString
           path rendered (make-array java.nio.file.OpenOption 0))
          (println "Wrote" (str path)))
        (print rendered))
      0)
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
    (let [response (service-client/request cli-context
                                           {:op :semantic-status})]
      (cond
        (nil? response) (do (println "not running") 0)
        (:ok response) (do (pprint/pprint (:value response)) 0)
        :else (do
                (binding [*out* *err*]
                  (println (str (name (or (:type response) :service/error))
                                ": " (:error response))))
                1)))
    "stop" (let [response ((resolve-fn 'llm-context.service.daemon/stop!)
                            cli-context)]
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

(defn- print-durable-health-banner! [context command]
  (when (and (:state-dir context)
             (not (get-in context [:options :quiet?]))
             (not (contains? #{"analyze" "doctor" "semantic" "service"}
                             command)))
    (when-let [snapshot (health/read-snapshot context)]
      (when-let [alerts (seq (:alerts snapshot))]
        (let [{:keys [detail action]} (first alerts)]
          (binding [*out* *err*]
            (println
             (str "warning: unresolved project health: " detail
                  (when (seq action) (str "; " action))))))))))

(defn run [args]
  (try
    (let [{:keys [project command args] :as options} (parse-args args)
          needs-project? (not (#{"help" "version" "models"} command))
          context (when needs-project? (project/context project))
          context (assoc context :options options)]
      (print-durable-health-banner! context command)
      (execute context command args))
    (catch clojure.lang.ExceptionInfo error
      (binding [*out* *err*]
        (print-error! error))
      (or (:exit-code (ex-data error)) 1))))
