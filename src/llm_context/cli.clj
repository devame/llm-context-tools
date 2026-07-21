(ns llm-context.cli
  (:require [clojure.pprint :as pprint]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.context :as context-packet]
            [llm-context.project :as project]
            [llm-context.query :as query]
            [llm-context.runtime.doctor :as doctor]
            [llm-context.store :as store]
            [llm-context.version :as version]))

(defn usage []
  (str "llm-context " version/value "\n\n"
       "Usage: llm-context [global-options] <command> [command-options]\n\n"
       "Global options:\n"
       "  -C, --project PATH   Project root (default: current directory)\n"
       "  -q, --quiet          Suppress informational output\n"
       "  -h, --help           Show this help\n\n"
       "Commands:\n"
       "  init                 Write llm-context.edn\n"
       "  analyze              Update the semantic graph\n"
       "  query                Query the semantic graph\n"
       "  context              Build an LLM context packet\n"
       "  export               Export graph data\n"
       "  stats                Show graph statistics\n"
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

(defmethod execute "init" [context _ _]
  (println "Created" (str (config/init! context)))
  0)

(defmethod execute "doctor" [context _ _]
  (let [checks (doctor/check context (config/load-config context))]
    (doctor/print-report checks)
    (if (doctor/healthy? checks) 0 1)))

(defmethod execute "analyze" [context _ args]
  (when-let [unknown (first (remove #{"--full"} args))]
    (throw (ex-info (str "Unknown analyze option: " unknown) {:exit-code 2})))
  (let [settings (config/load-config context)
        full? (or (some #{"--full"} args)
                  (not (incremental/index-present? context settings)))
        result (if full?
                 (full/analyze! context settings)
                 (incremental/analyze! context settings))]
    (when-not (get-in context [:options :quiet?])
      (println
       (if (= :incremental (:mode result))
         (format "Analyzed %d files: %d changed, %d deleted (%d diagnostics)"
                 (:files result) (:changed result) (:deleted result)
                 (count (:diagnostics result)))
         (format "Analyzed %d files into %d entities (%d diagnostics)"
                 (:files result) (:entities result)
                 (count (:diagnostics result))))))
    0))

(defn- require-argument [subcommand args]
  (or (first args)
      (throw (ex-info (str "query " subcommand " requires an argument")
                      {:exit-code 2}))))

(defn- execute-query [graph subcommand args]
  (case subcommand
    "stats" (query/stats graph)
    "find-symbol" (query/symbols graph (require-argument subcommand args))
    "callers" (query/callers graph (require-argument subcommand args))
    "callees" (query/callees graph (require-argument subcommand args))
    "trace" (query/transitive-callees graph (require-argument subcommand args))
    "entry-points" (query/entry-points graph)
    "effects" (query/effects graph)
    "unresolved" (query/unresolved graph)
    (throw (ex-info (str "Unknown query: " subcommand) {:exit-code 2}))))

(defmethod execute "query" [context _ args]
  (let [subcommand (or (first args) "stats")
        settings (config/load-config context)]
    (store/with-store [graph context settings]
      (pprint/pprint (execute-query graph subcommand (next args))))
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
    (store/with-store [graph cli-context settings]
      (let [packet (context-packet/build graph options)]
        (case (:format options)
          "edn" (pprint/pprint packet)
          "markdown" (print (context-packet/markdown packet))
          (throw (ex-info (str "Unsupported context format: " (:format options))
                          {:exit-code 2})))))
    0))

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
