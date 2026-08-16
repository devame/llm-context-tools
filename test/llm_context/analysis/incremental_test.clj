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

(deftest aggregate-facts-converge-across-full-and-incremental-analysis
  (let [root (Files/createTempDirectory
              "llm-context-incremental-aggregate-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        path (.resolve src "transport.clj")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])
        values
        (fn []
          (store/with-store [graph project settings]
            (set (store/query
                  graph
                  '[:find [?value ...]
                    :where [?member :membership/value ?value]] []))))]
    (Files/createDirectories src
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path)
          "(ns neutral.transport) (def built-ins #{:rail :river})")
    (full/analyze! project settings)
    (is (= #{":rail" ":river"} (values)))
    (store/with-store [graph project settings]
      (is (= #{[:complete-static 2]}
             (store/query
              graph
              '[:find ?completeness ?count
                :where
                [?aggregate :aggregate/completeness ?completeness]
                [?aggregate :aggregate/member-count ?count]] []))))

    (spit (str path)
          "(ns neutral.transport) (def built-ins #{:road :sea})")
    (is (= 1 (:changed (incremental/analyze! project settings))))
    (is (= #{":road" ":sea"} (values)))

    (Files/delete path)
    (is (= 1 (:deleted (incremental/analyze! project settings))))
    (is (empty? (values)))))

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
    (store/with-store [graph project settings]
      (let [closure (#'incremental/closure-candidate
                     graph project settings nil)
            complete (full/prepare-current project settings nil :incremental)]
        (is (= (:outputs complete) (:outputs closure)))))
    (let [result (incremental/analyze! project settings)]
      ;; Only the new duplicate and the caller whose resolution changed need
      ;; replacement. The original target's canonical facts are unchanged.
      (is (= 2 (:changed result)))
      (is (= {:changed 1 :deleted 0 :affected 2 :reused 1}
             (get-in result [:analysis-metrics :incremental-closure]))))
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

(deftest corrupt-manifest-falls-back-to-whole-project-preparation
  (let [root (Files/createTempDirectory
              "llm-context-incremental-corrupt-manifest-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        first-path (.resolve src "first.clj")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str first-path) "(ns first) (defn value [] 1)")
    (spit (str (.resolve src "second.clj")) "(ns second) (defn stable [] 2)")
    (full/analyze! project settings)
    (spit (str (.resolve (:state-dir project)
                         "cache/analyzer-manifests/index.edn"))
          "{:corrupt")
    (spit (str first-path) "(ns first) (defn value [] 3)")
    (let [result (incremental/analyze! project settings)]
      (is (= 1 (:changed result)))
      (is (nil? (get-in result
                        [:analysis-metrics :incremental-closure]))))))

(deftest namespace-rename-rebuilds-importing-callers
  (let [root (Files/createTempDirectory
              "llm-context-incremental-namespace-rename-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        src (.resolve root "src")
        target (.resolve src "target.clj")
        project (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (Files/createDirectories src
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str target) "(ns old.target) (defn value [] 1)")
    (spit (str (.resolve src "caller.clj"))
          "(ns caller (:require [old.target :as target])) (defn call [] (target/value))")
    (full/analyze! project settings)
    (spit (str target) "(ns new.target) (defn value [] 1)")
    (let [result (incremental/analyze! project settings)]
      (is (= 2 (get-in result
                       [:analysis-metrics :incremental-closure :affected])))
      (is (= 2 (:changed result))))))

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
