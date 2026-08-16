(ns llm-context.analysis.full-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.files :as analysis-files]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.project-analyzer :as project-analyzer]
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

(deftest full-replacement-preserves-only-compatible-semantic-state
  (let [settings (config/defaults)
        analyzers {:clj-kondo {:configuration-fingerprint "config:a"}}
        active {:llm-context/graph-format 4
                :llm-context/analyzer-version "2025.10.23"
                :llm-context/analyzer-configuration-fingerprint "config:a"
                :llm-context/semantic-fingerprint-version 1
                :llm-context/janet-catalog-version 1
                :llm-context/semantic-document-version 4
                :llm-context/semantic-index-name "llm-context-v4"}
        reset-count (atom 0)
        persist! (fn [metadata]
                   (with-redefs [store/graph-metadata (constantly metadata)
                                 store/graph-state (constantly :ready)
                                 store/validate-replacement! (fn [& _])
                                 store/begin-full-replacement! (fn [& _])
                                 store/replace-all! (fn [& _])
                                 store/reset-semantic-state!
                                 (fn [& _] (swap! reset-count inc))
                                 llm-context.semantic.reconcile/mark-full!
                                 (fn [& _])
                                 store/write-graph-metadata! (fn [& _])]
                     (#'full/persist! :graph settings [] analyzers nil)))]
    (with-redefs [llm-context.analysis.clj-kondo/analyzer-version "2025.10.23"
                  llm-context.analysis.janet/catalog-version 1
                  llm-context.model.canonical-hash/contract-version 1]
      (persist! active)
      (is (zero? @reset-count))
      (persist! (assoc active :llm-context/semantic-document-version 2))
      (is (= 1 @reset-count)))))

(deftest failed-graph-replacement-keeps-semantic-state-and-unavailable-marker
  (let [project (project/context
                 (str (Files/createTempDirectory
                       "llm-context-full-failure-"
                       (make-array java.nio.file.attribute.FileAttribute 0))))
        settings (assoc-in (config/defaults) [:semantic :providers] [])
        file {:entity/type :entity.type/file
              :file/id "file:src/a.clj"
              :file/path "src/a.clj"
              :file/language :language/clojure
              :file/content-hash (str "sha256:" (apply str (repeat 64 "0")))
              :file/size 0
              :file/modified-at 1}
        reset-called? (atom false)]
    (store/with-store [graph project settings]
      (with-redefs [store/reset-semantic-state!
                    (fn [_] (reset! reset-called? true))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"interrupted write"
             (#'full/persist!
              graph settings [file]
              (fn [_] (throw (ex-info "interrupted write" {})))))))
      (is (false? @reset-called?))
      (is (= :unavailable (store/graph-state graph))))))

(deftest preserved-analyzer-output-aborts-before-persistence
  (let [project (project/context
                 (str (Files/createTempDirectory
                       "llm-context-full-preserve-"
                       (make-array java.nio.file.attribute.FileAttribute 0))))
        settings (assoc-in (config/defaults) [:semantic :providers] [])
        file {:entity/type :entity.type/file
              :file/id "file:src/broken.janet"
              :file/path "src/broken.janet"
              :file/language :language/janet
              :file/content-hash (str "sha256:" (apply str (repeat 64 "0")))
              :file/size 1
              :file/modified-at 1}
        output {:file file :entities []
                :preserve? true
                :diagnostics [{:level :warning :kind :parse-error
                               :file "src/broken.janet"}]}
        persisted? (atom false)]
    (with-redefs [analysis-files/discover
                  (fn [& _] {:files [{:relative-path "src/broken.janet"}]
                             :diagnostics []})
                  project-analyzer/analyze
                  (fn [& _] {:outputs [output] :diagnostics []})
                  store/validate-replacement!
                  (fn [& _] (reset! persisted? true))]
      (let [error
            (try
              (full/analyze! project settings)
              nil
              (catch clojure.lang.ExceptionInfo error error))]
        (is (= :analysis/incomplete-snapshot (:type (ex-data error))))
        (is (= ["src/broken.janet"] (:files (ex-data error))))
        (is (false? @persisted?))))))

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
      (is (= 1 (get-in result [:manifest :files])))
      (is (= [:discover-start :discover-complete :parse-progress
              :analyzer-phase-start :analyzer-phase-complete
              :analyzer-phase-start :analyzer-phase-complete
              :analyzer-phase-start :analyzer-phase-complete
              :analyzer-phase-start :analyzer-phase-complete
              :analyzer-phase-start :analyzer-phase-complete
              :parse-complete :persist-start :persist-progress
              :analyzer-finalize-start :analyzer-finalize-complete
              :semantic-reconcile-start :semantic-reconcile-complete
              :complete]
             (mapv :stage @events)))
      (is (= 1000 full/persistence-batch-size))
      (is (= full/persistence-batch-size
             (:batch-size (first (filter #(= :persist-start (:stage %))
                                        @events)))))
      (is (= [:clj-kondo :janet-analysis :relationship-materialization
              :canonicalization :fingerprinting]
             (mapv :phase
                   (filter #(= :analyzer-phase-complete (:stage %))
                           @events))))
      (is (every? #(and (number? (:elapsed-ms %))
                        (not (neg? (:elapsed-ms %))))
                  (filter #(= :analyzer-phase-complete (:stage %))
                          @events)))
      (is (pos? (get-in result [:analysis-metrics :output :entities] 0)))
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

(deftest stale-source-preparation-retries-once
  (let [calls (atom 0)]
    (with-redefs [full/prepare
                  (fn [& _] {:attempt (swap! calls inc)})
                  full/stale-candidate?
                  (fn [_ _ candidate] (= 1 (:attempt candidate)))]
      (is (= 2 (:attempt (full/prepare-current nil nil))))
      (is (= 2 @calls)))))

(deftest repeatedly-stale-source-returns-a-structured-result
  (let [calls (atom 0)]
    (with-redefs [full/prepare
                  (fn [& _]
                    (swap! calls inc)
                    {:candidate true})
                  full/stale-candidate? (constantly true)]
      (is (= {:stale? true
              :mode :stale-source
              :type :analysis/stale-source
              :attempts 2}
             (full/prepare-current nil nil)))
      (is (= 2 @calls)))))
