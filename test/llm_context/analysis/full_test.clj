(ns llm-context.analysis.full-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.full :as full]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(deftest invalid-snapshots-do-not-reset-semantic-state
  (let [reset-called? (atom false)
        invalid [{:entity/type :entity.type/reference
                  :reference/id "reference:invalid"
                  :reference/symbol "symbol:owner"
                  :reference/kind :edge.kind/calls
                  :reference/target-text ""
                  :reference/classification :dynamic
                  :reference/evidence :clj-kondo-local-usage}]]
    (with-redefs [store/validate-replacement!
                  (fn [_ entities]
                    (is (= invalid entities))
                    (throw (ex-info "invalid snapshot" {})))
                  store/reset-semantic-state!
                  (fn [_] (reset! reset-called? true))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"invalid snapshot"
           (#'full/persist! :graph {} invalid nil)))
      (is (false? @reset-called?)))))

(deftest complete-analysis-persists-and-removes-files
  (let [root (Files/createTempDirectory "llm-context-full-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        path (.resolve src "app.clj")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) "(ns app) (defn greet [name] (println name))")
    (let [events (atom [])
          result (full/analyze! project settings #(swap! events conj %))]
      (is (= 1 (:files result)))
      (is (pos? (:entities result)))
      (is (= [:discover-start :discover-complete :parse-progress
              :parse-complete :persist-start :persist-progress
              :analyzer-finalize-start :analyzer-finalize-complete
              :semantic-reconcile-start :semantic-reconcile-complete
              :complete]
             (mapv :stage @events)))
      (is (= full/persistence-batch-size
             (:batch-size (first (filter #(= :persist-start (:stage %))
                                        @events)))))
      (is (number?
           (:exact-edges
            (first (filter #(= :analyzer-finalize-complete (:stage %))
                           @events))))))
    (store/with-store [graph project settings]
      (is (= #{"greet"}
             (set (store/query graph
                               '[:find [?name ...]
                                 :where [?s :symbol/name ?name]
                                        [?s :symbol/kind :symbol.kind/function]]
                               [])))))
    (Files/delete path)
    (full/analyze! project settings)
    (store/with-store [graph project settings]
      (is (empty? (store/query graph
                               '[:find [?file ...] :where [?file :file/id _]]
                               []))))))

(deftest complete-analysis-persists-janet-graph
  (let [root (Files/createTempDirectory "llm-context-full-janet-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str (.resolve src "names.janet"))
          "(defn format-name [name] (string/ascii-lower name))")
    (spit (str (.resolve src "main.janet"))
          (str "(import ./names)\n"
               "(defn greet [name] (print (names/format-name name)))\n"
               "(defn load-config [path] (slurp path))\n"))
    (let [result (full/analyze! project settings)]
      (is (= 2 (:files result)))
      (is (empty? (:diagnostics result))))
    (store/with-store [graph project settings]
      (is (= #{"format-name" "greet" "load-config"}
             (set (store/query graph
                               '[:find [?name ...]
                                 :where [?symbol :symbol/name ?name]
                                        [?symbol :symbol/kind :symbol.kind/function]]
                               []))))
      (is (some #{"./names"}
                (store/query graph
                             '[:find [?target ...]
                               :where
                               [?edge :edge/kind :edge.kind/imports]
                               [?edge :edge/target-text ?target]]
                             []))))))
