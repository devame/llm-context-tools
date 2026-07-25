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
        path (.resolve src "app.clj")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) "(ns app) (defn first [] 1)")
    (full/analyze! project settings)
    (is (= 0 (:changed (incremental/analyze! project settings))))

    (spit (str path) "(ns app) (defn second [] 2)")
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

(deftest incremental-snapshots-repersist-cross-file-resolution-changes
  (let [root (Files/createTempDirectory
              "llm-context-incremental-resolution-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        caller (.resolve src "caller.clj")
        first-target (.resolve src "first.clj")
        duplicate (.resolve src "duplicate.clj")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])
        relationship-state
        (fn []
          (store/with-store [graph project settings]
            (or
             (when (seq (store/query
                         graph
                         '[:find ?edge
                           :where
                           [?edge :edge/target-text "first/target"]
                           [?edge :edge/kind :edge.kind/calls]
                           [?edge :edge/resolution :resolution/exact]]
                         []))
               :exact)
             (ffirst
              (store/query
               graph
               '[:find ?classification
                 :where
                 [?reference :reference/target-text "first/target"]
                 [?reference :reference/classification ?classification]]
               [])))))]
    (Files/createDirectories src
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str caller)
          "(ns caller (:require [first :as first])) (defn caller [] (first/target))")
    (spit (str first-target) "(ns first) (defn target [] 1)")
    (full/analyze! project settings)
    (is (= :exact (relationship-state)))

    (spit (str duplicate) "(ns first) (defn target [] 2)")
    (let [result (incremental/analyze! project settings)]
      (is (= 3 (:changed result))))
    (is (= :ambiguous (relationship-state)))

    (Files/delete duplicate)
    (incremental/analyze! project settings)
    (is (= :exact (relationship-state)))))
