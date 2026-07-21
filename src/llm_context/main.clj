(ns llm-context.main
  (:gen-class)
  (:require [llm-context.version :as version]))

(defn usage []
  (str "llm-context " version/value "\n\n"
       "Usage: llm-context <command> [options] [project-root]\n\n"
       "Commands:\n"
       "  version   Print the application version\n"
       "  help      Show this help\n"))

(defn run
  "Run the CLI and return a process exit code. Kept separate from -main so it is
  inexpensive to exercise through tests and a REPL."
  [args]
  (case (first args)
    (nil "help" "--help" "-h") (do (print (usage)) 0)
    ("version" "--version" "-V") (do (println version/value) 0)
    (do (binding [*out* *err*]
          (println "Unknown command:" (first args))
          (println "Run 'llm-context help' for usage."))
        2)))

(defn -main [& args]
  (System/exit (run args)))
