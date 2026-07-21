(ns llm-context.main
  (:gen-class)
  (:require [llm-context.cli :as cli]))

(def run cli/run)

(defn -main [& args]
  (System/exit (run args)))
