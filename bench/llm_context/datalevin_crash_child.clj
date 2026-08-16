(ns llm-context.datalevin-crash-child
  "Child process used only by the isolated provider qualification harness."
  (:require [datalevin.core :as d]))

(def schema
  {:qualification/id {:db/valueType :db.type/string
                      :db/unique :db.unique/identity}
   :qualification/value {:db/valueType :db.type/long}})

(defn -main [mode directory]
  (let [connection (d/get-conn directory schema)]
    (case mode
      "before-commit"
      (Runtime/getRuntime)

      "after-commit"
      (do
        (d/transact! connection
                     [{:qualification/id "committed"
                       :qualification/value 1}])
        (println "committed")
        (flush))

      (throw (ex-info "Unknown Datalevin crash qualification mode"
                      {:mode mode})))
    ;; Deliberately skip d/close so reopening exercises abrupt JVM loss.
    (.halt (Runtime/getRuntime) 137)))
