(ns llm-context.cli
  (:require [llm-context.config :as config]
            [llm-context.analysis.full :as full]
            [llm-context.project :as project]
            [llm-context.runtime.doctor :as doctor]
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
  (when (seq (remove #{"--full"} args))
    (throw (ex-info (str "Unknown analyze option: " (first args)) {:exit-code 2})))
  (let [result (full/analyze! context (config/load-config context))]
    (when-not (get-in context [:options :quiet?])
      (println (format "Analyzed %d files into %d entities (%d diagnostics)"
                       (:files result) (:entities result)
                       (count (:diagnostics result)))))
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
