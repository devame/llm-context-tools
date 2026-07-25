(ns refresh-janet-catalog
  "Maintenance entry point for reviewing the checked-in Janet catalog.

  Refreshes are intentionally review-driven: project analysis must never fetch
  or execute code. Pass the proposed catalog file and this script validates its
  version and deterministic ordering before it is committed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn -main [& [path]]
  (when-not path
    (throw (ex-info "usage: clojure -M script/refresh-janet-catalog.clj FILE"
                    {:exit-code 2})))
  (let [catalog (edn/read-string (slurp (io/file path)))]
    (when-not (re-matches #"\d+\.\d+\.\d+" (:version catalog))
      (throw (ex-info "Janet catalog requires a semantic version"
                      {:catalog path})))
    (doseq [key [:special-forms :binding-forms :definition-forms :core]]
      (when-not (seq (get catalog key))
        (throw (ex-info (str "Janet catalog is missing " key)
                        {:catalog path :key key}))))
    (println "Validated Janet catalog" (:version catalog)
             "with" (count (:core catalog)) "core names")))

(apply -main *command-line-args*)
