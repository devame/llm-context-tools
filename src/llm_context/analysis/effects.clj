(ns llm-context.analysis.effects
  (:require [clojure.string :as str]
            [llm-context.model.ids :as ids]))

(def patterns
  {:language/javascript
   [{:match #"^(?:node:)?fs[./](?:promises[./])?readFile(?:Sync)?$"
     :kind :effect.kind/file-read :confidence 0.98}
    {:match #"^(?:node:)?fs[./](?:promises[./])?(?:writeFile|appendFile)(?:Sync)?$"
     :kind :effect.kind/file-write :confidence 0.98}
    {:match #"^(?:fetch|axios[./](?:get|post|put|patch|delete)|https?[./](?:get|request))$"
     :kind :effect.kind/network :confidence 0.9}
    {:match #"^console[./](?:log|info|warn|error|debug)$"
     :kind :effect.kind/logging :confidence 0.99}
    {:match #"^(?:child_process[./])?(?:exec|execFile|spawn|fork)(?:Sync)?$"
     :kind :effect.kind/process :confidence 0.82}]

   :language/typescript
   [{:match #"^(?:node:)?fs[./](?:promises[./])?readFile(?:Sync)?$"
     :kind :effect.kind/file-read :confidence 0.98}
    {:match #"^(?:node:)?fs[./](?:promises[./])?(?:writeFile|appendFile)(?:Sync)?$"
     :kind :effect.kind/file-write :confidence 0.98}
    {:match #"^(?:fetch|axios[./](?:get|post|put|patch|delete)|https?[./](?:get|request))$"
     :kind :effect.kind/network :confidence 0.9}
    {:match #"^console[./](?:log|info|warn|error|debug)$"
     :kind :effect.kind/logging :confidence 0.99}
    {:match #"^(?:child_process[./])?(?:exec|execFile|spawn|fork)(?:Sync)?$"
     :kind :effect.kind/process :confidence 0.82}]

   :language/python
   [{:match #"^(?:pathlib[./]Path[./])?read_text$"
     :kind :effect.kind/file-read :confidence 0.96}
    {:match #"^(?:pathlib[./]Path[./])?write_text$"
     :kind :effect.kind/file-write :confidence 0.96}
    {:match #"^(?:requests|httpx)[./](?:get|post|put|patch|delete|request)$"
     :kind :effect.kind/network :confidence 0.94}
    {:match #"^(?:subprocess[./])(?:run|call|Popen|check_output)$"
     :kind :effect.kind/process :confidence 0.98}
    {:match #"^(?:print|logging[./](?:debug|info|warning|error|exception))$"
     :kind :effect.kind/logging :confidence 0.98}]

   :language/clojure
   [{:match #"^(?:clojure\.core/)?slurp$"
     :kind :effect.kind/file-read :confidence 0.99}
    {:match #"^(?:clojure\.core/)?spit$"
     :kind :effect.kind/file-write :confidence 0.99}
    {:match #"^(?:clojure\.core/)?(?:println|print|prn)$"
     :kind :effect.kind/logging :confidence 0.98}
    {:match #"^(?:clojure\.java\.shell/)?sh$"
     :kind :effect.kind/process :confidence 0.97}
    {:match #"^(?:next\.jdbc/)?execute!$"
     :kind :effect.kind/database-write :confidence 0.75}]})

(defn- normalize-target [target]
  (-> target
      str/trim
      (str/replace #"\s+" "")
      (str/replace #"\." "/")))

(defn- matching-pattern [language target]
  (let [normalized (normalize-target target)]
    (some #(when (re-matches (:match %) normalized) %) (get patterns language))))

(defn analyze
  "Convert only high-signal call facts into evidence-backed effect entities.
  Unknown and unqualified names are intentionally omitted rather than guessed."
  [language edges]
  (->> edges
       (keep (fn [edge]
               (when (and (= :edge.kind/calls (:edge/kind edge))
                          (:edge/from edge))
                 (when-let [{:keys [kind confidence]}
                            (matching-pattern language (:edge/target-text edge))]
                   (let [parts {:kind kind
                                :symbol-id (:edge/from edge)
                                :detail (:source/snippet edge)
                                :start-line (:source/start-line edge)
                                :start-column (:source/start-column edge)}]
                     (merge {:entity/type :entity.type/effect
                             :effect/id (ids/effect-id parts)
                             :effect/kind kind
                             :effect/symbol (:edge/from edge)
                             :effect/detail (or (:source/snippet edge)
                                                (:edge/target-text edge))
                             :effect/confidence confidence}
                            (select-keys edge [:source/start-line :source/start-column
                                               :source/end-line :source/end-column
                                               :source/snippet])))))))
       vec))
