(ns llm-context.analysis.effects
  (:require [clojure.string :as str]
            [llm-context.model.ids :as ids]))

(def patterns
  {:language/clojure
   [{:match #"^(?:clojure\.core/)?slurp$"
     :kind :effect.kind/file-read :confidence 0.99}
    {:match #"^(?:clojure\.core/)?spit$"
     :kind :effect.kind/file-write :confidence 0.99}
    {:match #"^(?:clojure\.core/)?(?:println|print|prn)$"
     :kind :effect.kind/logging :confidence 0.98}
    {:match #"^(?:clojure\.java\.shell/)?sh$"
     :kind :effect.kind/process :confidence 0.97}
    {:match #"^(?:next\.jdbc/)?execute!$"
     :kind :effect.kind/database-write :confidence 0.75}]

   :language/clojurescript
   [{:match #"^(?:cljs\.core/)?(?:swap!|reset!)$"
     :kind :effect.kind/global-mutation :confidence 0.99}
    {:match #"^(?:cljs\.core/)?(?:println|print|prn)$"
     :kind :effect.kind/logging :confidence 0.98}
    {:match #"^(?:js/)?fetch$"
     :kind :effect.kind/network :confidence 0.9}]

   :language/clojure-common
   [{:match #"^(?:(?:clojure|cljs)\.core/)?(?:swap!|reset!)$"
     :kind :effect.kind/global-mutation :confidence 0.99}
    {:match #"^(?:(?:clojure|cljs)\.core/)?(?:println|print|prn)$"
     :kind :effect.kind/logging :confidence 0.98}]

   :language/janet
   [{:match #"^slurp$"
     :kind :effect.kind/file-read :confidence 0.99}
    {:match #"^(?:spit|file/write)$"
     :kind :effect.kind/file-write :confidence 0.99}
    {:match #"^file/read$"
     :kind :effect.kind/file-read :confidence 0.99}
    {:match #"^(?:print|printf|prin|prinf|pp|eprint|eprintf)$"
     :kind :effect.kind/logging :confidence 0.96}
    {:match #"^(?:os/spawn|os/execute|file/popen)$"
     :kind :effect.kind/process :confidence 0.98}
    {:match #"^net/(?:connect|listen|accept|read|write)$"
     :kind :effect.kind/network :confidence 0.9}]})

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
