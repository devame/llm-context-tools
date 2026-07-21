(ns llm-context.analysis.incremental-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(deftest incremental-analysis-skips-unchanged-and-cascades-deletion
  (let [root (Files/createTempDirectory "llm-context-incremental-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        path (.resolve src "app.js")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) "export function first() { return 1; }")
    (full/analyze! project settings)
    (is (= 0 (:changed (incremental/analyze! project settings))))

    (spit (str path) "export function second() { return 2; }")
    (let [result (incremental/analyze! project settings)]
      (is (= 1 (:changed result)))
      (is (= 0 (:deleted result))))
    (store/with-store [graph project settings]
      (is (= #{"second"}
             (set (store/query graph
                               '[:find [?name ...]
                                 :where [?symbol :symbol/name ?name]
                                        [?symbol :symbol/kind :symbol.kind/function]]
                               [])))))

    (Files/delete path)
    (let [result (incremental/analyze! project settings)]
      (is (= 0 (:changed result)))
      (is (= 1 (:deleted result))))
    (store/with-store [graph project settings]
      (is (empty? (store/query graph
                               '[:find [?id ...] :where [_ :file/id ?id]] []))))))
