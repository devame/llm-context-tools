(ns llm-context.cli
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.service.client :as service-client]
            [llm-context.version :as version]))

(defn- resolve-fn [symbol]
  (requiring-resolve symbol))

(defn- with-graph [context settings f]
  (let [graph ((resolve-fn 'llm-context.store/open) context settings)]
    (try
      (f graph)
      (finally (.close ^java.io.Closeable graph)))))

(def ^:private unavailable ::unavailable)

(defn- remote-value [context request]
  (if-let [response (service-client/request context request)]
    (if (:ok response)
      (:value response)
      (throw (ex-info (:error response) {:exit-code (:exit-code response)})))
    unavailable))

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
       "  query                Query the semantic graph\n"
       "  semantic             Inspect or synchronize LateOn indexing\n"
       "  context              Build an LLM context packet\n"
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

(defn- diagnostic-message [{:keys [level kind file path language message size]}]
  (str (name (or level :info)) " " (name kind) ": "
       (case kind
         :missing-include (str "configured path does not exist: " path)
         :grammar-unavailable (str file " (" (some-> language name) ")")
         :file-too-large (str file " (" size " bytes)")
         :binary-file file
         :semantic-provider-failed (or message "semantic provider failed")
         (or file path message (pr-str kind)))))

(defmethod execute "doctor" [context _ _]
  (let [checks ((resolve-fn 'llm-context.runtime.doctor/check)
                context (config/load-config context))]
    ((resolve-fn 'llm-context.runtime.doctor/print-report) checks)
    (if ((resolve-fn 'llm-context.runtime.doctor/healthy?) checks) 0 1)))

(defn- print-analysis-progress! [{:keys [stage files diagnostics completed total
                                         file entities batch-size phase
                                         elapsed-seconds]}]
  (println
   (case stage
     :discover-start "Discovering source files..."
     :discover-complete
     (format "Discovered %d supported files (%d diagnostics)" files diagnostics)
     :parse-progress (format "Parsing %d/%d: %s" completed total file)
     :parse-complete (format "Parsed %d/%d files" completed total)
     :semantic-start "Running configured semantic providers..."
     :semantic-complete "Semantic provider stage complete"
     :resolve-start "Resolving graph relationships..."
     :persist-start (format "Persisting %d entities in batches of %d..."
                            entities batch-size)
     :persist-progress
     (format "%s %d/%d entities"
             (if (= :retract phase) "Retracted" "Committed") completed total)
     :complete (format "Full analysis completed in %d seconds" elapsed-seconds)
     (str "Analysis stage: " (name stage))))
  (flush))

(defmethod execute "analyze" [context _ args]
  (when-let [unknown (first (remove #{"--full"} args))]
    (throw (ex-info (str "Unknown analyze option: " unknown) {:exit-code 2})))
  (let [settings (config/load-config context)
        force-full? (boolean (some #{"--full"} args))
        full? (or force-full?
                  (not ((resolve-fn
                         'llm-context.analysis.incremental/index-present?)
                        context settings)))
        progress (when-not (get-in context [:options :quiet?])
                   print-analysis-progress!)
        ;; Keep analysis in this process so progress remains observable and a
        ;; service timeout cannot accidentally launch a second writer.
        result (if full?
                 ((resolve-fn 'llm-context.analysis.full/analyze!)
                  context settings progress)
                 ((resolve-fn 'llm-context.analysis.incremental/analyze!)
                  context settings))]
    (when-not (get-in context [:options :quiet?])
      (println
       (if (= :incremental (:mode result))
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
    "find-symbol" ((resolve-fn 'llm-context.query/symbols)
                   graph (require-argument subcommand args))
    "search" ((resolve-fn 'llm-context.query/search)
              graph semantic-client settings
              (require-argument subcommand args))
    "callers" ((resolve-fn 'llm-context.query/callers)
               graph (require-argument subcommand args))
    "callees" ((resolve-fn 'llm-context.query/callees)
               graph (require-argument subcommand args))
    "trace" ((resolve-fn 'llm-context.query/transitive-callees)
             graph (require-argument subcommand args))
    "entry-points" ((resolve-fn 'llm-context.query/entry-points) graph)
    "effects" ((resolve-fn 'llm-context.query/effects) graph)
    "unresolved" ((resolve-fn 'llm-context.query/unresolved) graph)
    (throw (ex-info (str "Unknown query: " subcommand) {:exit-code 2}))))

(defmethod execute "query" [context _ args]
  (let [subcommand (or (first args) "stats")
        command-args (vec (next args))
        remote (remote-value context {:op :query :subcommand subcommand
                                      :args command-args})]
    (if-not (= unavailable remote)
      (pprint/pprint remote)
      (let [settings (config/load-config context)]
        (with-graph context settings
          #(pprint/pprint
            (execute-query % nil settings subcommand command-args)))))
    0))

(defn- local-semantic-status [context settings]
  (with-graph
    context settings
    #((resolve-fn 'llm-context.semantic.state/semantic-summary)
      % :lateon-code (System/currentTimeMillis))))

(defn- semantic-status [context settings]
  (let [remote (remote-value context {:op :semantic-status})]
    (if (= unavailable remote)
      (assoc (local-semantic-status context settings)
             :runtime {:status :not-running})
      remote)))

(defn- semantic-synchronized? [status]
  (and (zero? (:pending status))
       (zero? (:leased status))
       (zero? (:dirty status))))

(defmethod execute "semantic" [context _ args]
  (let [subcommand (or (first args) "status")
        options (set (next args))
        settings (config/load-config context)]
    (case subcommand
      "status"
      (do
        (when (seq options)
          (throw (ex-info "semantic status does not accept options"
                          {:exit-code 2})))
        (pprint/pprint (semantic-status context settings)))

      "sync"
      (do
        (when-let [unknown (first (remove #{"--wait"} options))]
          (throw (ex-info (str "Unknown semantic sync option: " unknown)
                          {:exit-code 2})))
        (let [initial (remote-value context {:op :semantic-sync})]
          (when (= unavailable initial)
            (throw
             (ex-info "Semantic synchronization requires a running project service"
                      {:exit-code 2})))
          (if-not (contains? options "--wait")
            (pprint/pprint initial)
            (let [timeout-ms (+ (get-in settings
                                        [:semantic :lateon-code
                                         :startup-timeout-ms])
                                (get-in settings
                                        [:semantic :lateon-code
                                         :visibility-timeout-ms]))
                  deadline (+ (System/currentTimeMillis) timeout-ms)]
              (loop [status initial]
                (cond
                  (semantic-synchronized? status)
                  (pprint/pprint status)

                  (pos? (:failed status))
                  (throw
                   (ex-info "Semantic synchronization has failed jobs"
                            {:exit-code 1 :status status}))

                  (not= :ready (get-in status [:runtime :status]))
                  (throw
                   (ex-info "LateOn semantic runtime is not ready"
                            {:exit-code 1 :status status}))

                  (>= (System/currentTimeMillis) deadline)
                  (throw
                   (ex-info "Timed out waiting for semantic synchronization"
                            {:exit-code 1 :status status}))

                  :else
                  (do
                    (Thread/sleep 250)
                    (recur (semantic-status context settings)))))))))

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
        (if (:focus result)
          (throw (ex-info (str "Unexpected context argument: " arg) {:exit-code 2}))
          (recur (next remaining) (assoc result :focus arg))))
      result)))

(defmethod execute "context" [cli-context _ args]
  (let [settings (config/load-config cli-context)
        options (parse-context-args
                 args {:max-tokens (get-in settings [:context :default-max-tokens])
                       :depth (get-in settings [:context :trace-depth])
                       :format "markdown"})]
    (when-not (:focus options)
      (throw (ex-info "context requires a symbol name or ID" {:exit-code 2})))
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
        (with-graph cli-context settings
          (fn [graph]
            (let [packet ((resolve-fn 'llm-context.context/build) graph options)]
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
                     (with-graph cli-context settings
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
             (if (:ok response)
               (do (println "stopped") 0)
               (throw (ex-info "No service is running for this project"
                               {:exit-code 2}))))
    (throw (ex-info (str "Unknown service command: " (first args))
                    {:exit-code 2}))))

(defmethod execute :default [_ command _]
  (throw (ex-info (str "Unknown command: " command)
                  {:exit-code 2 :command command})))

(defn run [args]
  (try
    (let [{:keys [project command args] :as options} (parse-args args)
          needs-project? (not (#{"help" "version"} command))
          context (when needs-project? (project/context project))]
      (execute (assoc context :options options) command args))
    (catch clojure.lang.ExceptionInfo error
      (binding [*out* *err*]
        (println (.getMessage error)))
      (or (:exit-code (ex-data error)) 1))))
