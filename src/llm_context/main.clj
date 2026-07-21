(ns llm-context.main
  (:gen-class)
  (:require [llm-context.cli :as cli]))

(def run cli/run)

(defn -main [& args]
  (let [status (run args)]
    ;; System/exit does not run Clojure's normal stream teardown. Commands that
    ;; intentionally use `print` (help, context, and stdout exports) therefore
    ;; need an explicit flush at the process boundary.
    (flush)
    (binding [*out* *err*]
      (flush))
    (System/exit status)))
