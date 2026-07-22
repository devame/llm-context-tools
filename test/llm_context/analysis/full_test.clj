(ns llm-context.analysis.full-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.full :as full]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(deftest complete-analysis-persists-and-removes-files
  (let [root (Files/createTempDirectory "llm-context-full-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        path (.resolve src "app.js")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) "export function greet(name) { console.log(name); }")
    (let [events (atom [])
          result (full/analyze! project settings #(swap! events conj %))]
      (is (= 1 (:files result)))
      (is (pos? (:entities result)))
      (is (= [:discover-start :discover-complete :parse-progress
              :parse-complete :semantic-start :semantic-complete
              :resolve-start :persist-start :persist-progress :complete]
             (mapv :stage @events)))
      (is (= full/persistence-batch-size
             (:batch-size (first (filter #(= :persist-start (:stage %))
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
      (is (= #{:effect.kind/logging :effect.kind/file-read}
             (set (store/query graph
                               '[:find [?kind ...]
                                 :where [_ :effect/kind ?kind]]
                               []))))
      (is (some #{"./names"}
                (store/query graph
                             '[:find [?target ...]
                               :where [?edge :edge/kind :edge.kind/imports]
                                      [?edge :edge/target-text ?target]]
                             []))))))
