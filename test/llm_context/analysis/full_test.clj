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
    (let [result (full/analyze! project settings)]
      (is (= 1 (:files result)))
      (is (pos? (:entities result))))
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
