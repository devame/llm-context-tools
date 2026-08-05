(ns llm-context.analysis.incremental-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.config :as config]
            [llm-context.model.canonical-hash :as canonical-hash]
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
    (with-redefs [clj-kondo/analyze!
                  (fn [& _]
                    (throw (ex-info "unchanged path invoked clj-kondo" {})))]
      (let [result (incremental/analyze! project settings)]
        (is (= 0 (:changed result)))
        (is (true? (get-in result [:analysis-metrics :short-circuit])))))

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
      ;; Only the new duplicate and the caller whose resolution changed need
      ;; replacement. The original target's canonical facts are unchanged.
      (is (= 2 (:changed result))))
    (is (= :ambiguous (relationship-state)))

    (Files/delete duplicate)
    (incremental/analyze! project settings)
    (is (= :exact (relationship-state)))))

(deftest incremental-analysis-refuses-an-interrupted-full-baseline
  (let [root (Files/createTempDirectory
              "llm-context-incremental-unavailable-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (store/with-store [graph project settings]
      (store/begin-full-replacement! graph)
      (let [error
            (try
              (incremental/analyze! graph project settings)
              nil
              (catch clojure.lang.ExceptionInfo error error))]
        (is (= :graph/update-incomplete (:type (ex-data error))))))))

(deftest fingerprint-contract-change-forces-safe-recomputation
  (let [root (Files/createTempDirectory
              "llm-context-incremental-fingerprint-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str (.resolve src "app.clj")) "(ns app) (defn stable [] 1)")
    (full/analyze! project settings)
    (store/with-store [graph project settings]
      (let [metadata (store/graph-metadata graph)]
        (store/write-graph-metadata!
         graph
         {:analyzer-name (:llm-context/analyzer-name metadata)
          :analyzer-version (:llm-context/analyzer-version metadata)
          :semantic-fingerprint-version 0
          :janet-catalog-version (:llm-context/janet-catalog-version metadata)
          :semantic-document-version
          (:llm-context/semantic-document-version metadata)
          :semantic-index-name (:llm-context/semantic-index-name metadata)})))
    (is (= 1 (:changed (incremental/analyze! project settings))))
    (store/with-store [graph project settings]
      (is (= canonical-hash/contract-version
             (:llm-context/semantic-fingerprint-version
              (store/graph-metadata graph)))))))

(deftest clj-kondo-configuration-change-forces-recomputation
  (let [root (Files/createTempDirectory
              "llm-context-incremental-config-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        kondo (.resolve root ".clj-kondo")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str (.resolve src "app.clj")) "(ns app) (defn stable [] 1)")
    (full/analyze! project settings)
    (Files/createDirectories kondo
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str (.resolve kondo "config.edn")) "{:linters {:unused-binding {:level :off}}}")
    (is (= 1 (:changed (incremental/analyze! project settings))))
    (store/with-store [graph project settings]
      (is (= (clj-kondo/config-fingerprint project)
             (:llm-context/analyzer-configuration-fingerprint
              (store/graph-metadata graph)))))))

(deftest malformed-incremental-source-preserves-the-complete-active-graph
  (let [root (Files/createTempDirectory
              "llm-context-incremental-malformed-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        path (.resolve src "app.janet")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) "(defn stable [] :ok)\n")
    (full/analyze! project settings)
    (spit (str path) "(defn stable [")
    (let [error
          (try
            (incremental/analyze! project settings)
            nil
            (catch clojure.lang.ExceptionInfo error error))]
      (is (= :analysis/incomplete-snapshot (:type (ex-data error)))))
    (store/with-store [graph project settings]
      (is (= :ready (store/graph-state graph)))
      (is (= ["stable"]
             (store/query
              graph
              '[:find [?name ...]
                :where
                [?symbol :symbol/name ?name]
                [?symbol :symbol/qualified-name "src/app/stable"]]
              []))))))

(deftest interrupted-cross-file-incremental-update-is-not-advertised-ready
  (let [root (Files/createTempDirectory
              "llm-context-incremental-interrupted-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        path (.resolve src "app.clj")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) "(ns app) (defn value [] 1)")
    (full/analyze! project settings)
    (spit (str path) "(ns app) (defn value [] 2)")
    (let [begin! store/begin-full-replacement!]
      (with-redefs [store/begin-full-replacement!
                    (fn [graph]
                      (begin! graph)
                      (throw
                       (ex-info "simulated replacement interruption" {})))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"simulated replacement interruption"
           (incremental/analyze! project settings)))))
    (store/with-store [graph project settings]
      (is (= :unavailable (store/graph-state graph))))))
