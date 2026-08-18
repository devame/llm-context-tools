(ns llm-context.analysis.clojure-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.clojure :as clojure-analysis]
            [llm-context.analysis.clojure-topics :as clojure-topics]
            [llm-context.analysis.project-analyzer :as project-analyzer]
            [llm-context.project :as project]
            [llm-context.source :as source])
  (:import [java.nio.file Files]))

(defn- input [root relative language content]
  (let [path (.resolve root relative)]
    (Files/createDirectories
     (.getParent path)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) content)
    {:path path :relative-path relative :language language :content content
     :size (count (.getBytes content
                             java.nio.charset.StandardCharsets/UTF_8))
     :modified-at 1}))

(deftest focused-topic-reader-ignores-observations-without-source-ranges
  (let [file {:content "(ns sample.ui)"}
        owner {:symbol/id "symbol:owner"
               :symbol/platform :cljs
               :symbol/qualified-name "sample.ui/render"}
        reference {:reference/symbol "symbol:owner"
                   :reference/qualified-target "cljs.core/get-in"}]
    (is (empty? (clojure-topics/extract file [owner] [reference])))))

(deftest canonical-file-size-bounds-the-normalized-analyzer-source
  (let [file (#'clojure-analysis/file-entity
              {:relative-path "src/invalid.clj"
               :language :language/clojure
               :content "\uFFFD"
               :size 1
               :modified-at 1})]
    (is (= 3 (:file/size file)))))

(deftest synthetic-kondo-usages-without-locations-are-not-graph-facts
  (let [owner {:symbol/id "symbol:owner"
               :symbol/platform :clj
               :symbol/qualified-name "sample.core/run"}
        namespaces {[:clj 'sample.core]
                    {:symbol/id "symbol:namespace"
                     :symbol/platform :clj
                     :symbol/qualified-name "sample.core"}}
        definitions {[:clj 'sample.core 'run] [owner]}
        relationship
        (#'clojure-analysis/var-relationship
         namespaces definitions :clj
         {:platform :clj :from 'sample.core :from-var 'run
          :to 'clojure.core :name 'some? :arity 1})]
    (is (nil? relationship))))

(deftest unnamed-local-calls-recover-their-source-token
  (let [content "(defn run [done]\n  (done))\n"
        owner {:entity/type :entity.type/symbol
               :symbol/id "symbol:owner"
               :symbol/file "file:src/sample.cljs"
               :symbol/platform :cljs
               :symbol/qualified-name "sample/run"
               :source/start-line 1 :source/start-column 1
               :source/end-line 2 :source/end-column 10}
        record {:filename "src/sample.cljs" :platform :cljs
                :row 2 :col 3 :end-row 2 :end-col 9
                :name-row 2 :name-col 4
                :name-end-row 2 :name-end-col 8}
        reference
        (#'clojure-analysis/local-reference
         (#'clojure-analysis/positional-index [owner]) {}
         (source/index content) record nil)]
    (is (= "done" (:reference/target-text reference)))
    (is (= :dynamic (:reference/classification reference)))
    (is (nil?
         (#'clojure-analysis/local-reference
          (#'clojure-analysis/positional-index [owner]) {}
          (source/index "") record nil)))))

(deftest cljs-async-callback-with-omitted-kondo-name-remains-valid
  (let [root (Files/createTempDirectory
              "llm-context-cljs-async-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source
        (str "(ns sample.async\n"
             "  (:require [cljs.test :refer-macros [deftest async]]))\n"
             "(deftest completes\n"
             "  (async done\n"
             "    (done)))\n")
        files [(input root "src/async.cljs" :language/clojurescript source)]
        project (project/context (str root))
        snapshot (clj-kondo/analyze! project files)
        entities (mapcat :entities
                         (clojure-analysis/materialize files snapshot))
        dynamic-references
        (filter #(and (= :entity.type/reference (:entity/type %))
                      (= :dynamic (:reference/classification %)))
                entities)]
    (is (every? #(seq (:reference/target-text %)) dynamic-references))
    (is (some #(= "done" (:reference/target-text %)) dynamic-references))))

(deftest malformed-clojure-output-never-exposes-partial-kondo-facts
  (let [root (Files/createTempDirectory
              "llm-context-clojure-malformed-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        files [(input root "src/broken.clj" :language/clojure
                      "(ns broken) (defn incomplete [x]")]
        snapshot (clj-kondo/analyze! (project/context (str root)) files)
        output (first (clojure-analysis/materialize files snapshot))]
    (is (:preserve? output))
    (is (= :malformed (:status output)))
    (is (empty? (:entities output)))
    (is (some #(contains? clj-kondo/source-integrity-finding-types
                          (:type %))
              (:diagnostics output)))))

(deftest kondo-facts-separate-exact-external-and-dynamic-relationships
  (let [root (Files/createTempDirectory
              "llm-context-clojure-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        files
        [(input root "src/api.clj" :language/clojure
                "(ns sample.api)\n(defn greet [x] x)\n")
         (input root "src/caller.clj" :language/clojure
                (str "(ns sample.caller (:require [sample.api :as api]))\n"
                     "(defmacro wrap [x] x)\n"
                     "(defn run [x]\n"
                     " (let [f api/greet]\n"
                     "  (when (and x true)\n"
                     "   (wrap (println (f (api/greet x)))))))\n"))]
        project (project/context (str root))
        snapshot (clj-kondo/analyze! project files)
        outputs (clojure-analysis/materialize files snapshot)
        entities (mapcat :entities outputs)
        edges (filter #(= :entity.type/edge (:entity/type %)) entities)
        references
        (filter #(= :entity.type/reference (:entity/type %)) entities)]
    (is (some #(and (= :edge.kind/calls (:edge/kind %))
                    (= "sample.api/greet" (:edge/target-text %)))
              edges))
    (is (some #(and (= :edge.kind/macro-invokes (:edge/kind %))
                    (= "sample.caller/wrap" (:edge/target-text %)))
              edges))
    (is (some #(and (= :external (:reference/classification %))
                    (= "clojure.core/println"
                       (:reference/qualified-target %)))
              references))
    (is (some #(and (= :dynamic (:reference/classification %))
                    (= "f" (:reference/target-text %)))
              references))
    (is (not-any? #(contains? #{"let" "when" "and" "or"}
                              (:reference/target-text %))
                  references))
    (is (every? #(and (= :resolution/exact (:edge/resolution %))
                      (= 1.0 (:edge/confidence %))
                      (:edge/to %)
                      (:edge/evidence %))
                edges))))

(deftest cljs-framework-and-state-literals-become-shared-topics
  (let [root (Files/createTempDirectory
              "llm-context-cljs-topics-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source
        (str "(ns sample.ui (:require [re-frame.core :as rf]))\n"
             "(defonce app-db (atom {}))\n"
             "(defn register! []\n"
             "  (rf/reg-event-db :save (fn [db _] (assoc db :handled true)))\n"
             "  (rf/reg-sub :saved (fn [db _] (:saved db))))\n"
             "(defn render! []\n"
             "  (rf/dispatch [:save 1])\n"
             "  (rf/subscribe [:saved])\n"
             "  (swap! app-db assoc-in [:saved-programs] [])\n"
             "  (get-in @app-db [:saved-programs]))\n"
             "(defn local-only [m] (get m :local-only))\n"
             "(defn dynamic-dispatch! [event] (rf/dispatch event))\n")
        files [(input root "src/ui.cljs" :language/clojurescript source)]
        project (project/context (str root))
        snapshot (clj-kondo/analyze! project files)
        entities (mapcat :entities
                         (clojure-analysis/materialize files snapshot))
        topics (filter #(= :entity.type/topic (:entity/type %)) entities)
        edges (filter #(= :entity.type/edge (:entity/type %)) entities)
        references
        (filter #(= :entity.type/reference (:entity/type %)) entities)]
    (is (some #(and (= :event (:topic/kind %))
                    (= ":save" (:topic/key %)))
              topics))
    (is (some #(and (= :subscription (:topic/kind %))
                    (= ":saved" (:topic/key %)))
              topics))
    (is (some #(and (= :state-key (:topic/kind %))
                    (= "[:saved-programs]" (:topic/key %)))
              topics))
    (is (some #(and (= :state-key (:topic/kind %))
                    (= ":handled" (:topic/key %)))
              topics))
    (is (not-any? #(= ":local-only" (:topic/key %)) topics))
    (is (some #(= :edge.kind/event-dispatches (:edge/kind %)) edges))
    (is (some #(= :edge.kind/subscribes (:edge/kind %)) edges))
    (is (some #(= :edge.kind/topic-registers (:edge/kind %)) edges))
    (is (some #(= :edge.kind/state-writes (:edge/kind %)) edges))
    (is (some #(= :edge.kind/state-reads (:edge/kind %)) edges))
    (is (some #(and (= :dynamic (:reference/classification %))
                    (= :computed-clojure-topic (:reference/evidence %))
                    (= "event" (:reference/target-text %)))
              references))
    (is (every? #(= :literal-clojure-form (:edge/evidence %))
                (filter #(contains?
                          #{:edge.kind/event-dispatches
                            :edge.kind/subscribes
                            :edge.kind/topic-registers
                            :edge.kind/state-writes
                            :edge.kind/state-reads}
                          (:edge/kind %))
                        edges)))))

(deftest shared-topic-is-asserted-once-across-project-files
  (let [root (Files/createTempDirectory
              "llm-context-shared-topic-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        files
        [(input root "src/register.cljs" :language/clojurescript
                (str "(ns sample.register (:require [re-frame.core :as rf]))\n"
                     "(defn register! [] (rf/reg-event-db :save identity))\n"))
         (input root "src/dispatch.cljs" :language/clojurescript
                (str "(ns sample.dispatch (:require [re-frame.core :as rf]))\n"
                     "(defn dispatch! [] (rf/dispatch [:save]))\n"))]
        snapshot (project-analyzer/analyze (project/context (str root)) files)
        entities (mapcat :entities (:outputs snapshot))
        topics (filter #(= :entity.type/topic (:entity/type %)) entities)
        topic-edges
        (filter #(contains? #{:edge.kind/topic-registers
                              :edge.kind/event-dispatches}
                            (:edge/kind %))
                entities)]
    (is (= 1 (count (filter #(= ":save" (:topic/key %)) topics))))
    (is (= 2 (count topic-edges)))
    (is (= 1 (count (set (map :edge/to topic-edges)))))))

(deftest declarations-collapse-into-one-effective-symbol
  (let [root (Files/createTempDirectory
              "llm-context-declarations-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source (str "(ns sample.declarations)\n"
                    "(declare later)\n"
                    "(declare later)\n"
                    "(defn later [] (later))\n")
        files [(input root "src/declarations.clj" :language/clojure source)]
        entities (->> files
                      (clj-kondo/analyze! (project/context (str root)))
                      (clojure-analysis/materialize files)
                      (mapcat :entities))
        later (filter #(= "sample.declarations/later"
                          (:symbol/qualified-name %))
                      entities)]
    (is (= 1 (count later)))
    (is (= :symbol.kind/function (:symbol/kind (first later))))
    (is (= 4 (:source/start-line (first later))))
    (is (true? (:symbol/indexable? (first later))))
    (is (= 1
           (count
            (filter #(and (= :edge.kind/calls (:edge/kind %))
                          (= "sample.declarations/later"
                             (:edge/target-text %)))
                    entities))))))

(deftest repeated-declarations-without-definition-do-not-persist-a-symbol
  (let [root (Files/createTempDirectory
              "llm-context-declaration-only-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source (str "(ns sample.pending)\n"
                    "(declare later)\n"
                    "(declare later)\n"
                    "(defn run [] (later))\n")
        files [(input root "src/pending.clj" :language/clojure source)]
        entities (->> files
                      (clj-kondo/analyze! (project/context (str root)))
                      (clojure-analysis/materialize files)
                      (mapcat :entities))
        later (filter #(= "sample.pending/later"
                          (:symbol/qualified-name %))
                      entities)]
    (is (empty? later))
    (is (some #(and (= :entity.type/reference (:entity/type %))
                    (= "sample.pending/later"
                       (:reference/qualified-target %))
                    (contains? #{:external :unresolved}
                               (:reference/classification %)))
              entities))))

(deftest cljc-definitions-stay-separated-by-platform
  (let [root (Files/createTempDirectory
              "llm-context-cljc-platforms-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source (str "(ns sample.common)\n"
                    "#?(:clj (defn platform [] :clj)\n"
                    "   :cljs (defn platform [] :cljs))\n")
        files [(input root "src/common.cljc" :language/clojure-common source)]
        entities (->> files
                      (clj-kondo/analyze! (project/context (str root)))
                      (clojure-analysis/materialize files)
                      (mapcat :entities))
        definitions
        (filter #(= "sample.common/platform" (:symbol/qualified-name %))
                entities)]
    (is (= #{:clj :cljs} (set (map :symbol/platform definitions))))
    (is (= 2 (count definitions)))
    (is (= 2 (count (set (map :symbol/id definitions)))))))

(deftest duplicate-analysis-observations-do-not-duplicate-symbols-or-calls
  (let [file {:relative-path "src/duplicate.clj"
              :language :language/clojure
              :content (str "(ns duplicate)\n"
                            "(defn target [] 1)\n"
                            "(defn run [] (target) (target))")
              :size 71 :modified-at 1}
        namespace {:filename "src/duplicate.clj" :platforms [:clj]
                   :row 1 :col 1 :end-row 1 :end-col 15
                   :name 'duplicate}
        target {:filename "src/duplicate.clj" :platforms [:clj]
                :row 2 :col 1 :end-row 2 :end-col 19
                :ns 'duplicate :name 'target
                :defined-by 'clojure.core/defn}
        run {:filename "src/duplicate.clj" :platforms [:clj]
             :row 3 :col 1 :end-row 3 :end-col 32
             :ns 'duplicate :name 'run
             :defined-by 'clojure.core/defn}
        usage {:filename "src/duplicate.clj" :platforms [:clj]
               :row 3 :col 14 :end-row 3 :end-col 22
               :from 'duplicate :from-var 'run
               :to 'duplicate :name 'target :arity 0}
        second-usage (assoc usage :col 23 :end-col 31)
        entities
        (->> {:analysis {:namespace-definitions [namespace]
                         :var-definitions [target target run]
                         :var-usages [usage usage second-usage]}}
             (clojure-analysis/materialize [file])
             (mapcat :entities))]
    (is (= 1 (count (filter #(= "duplicate/target"
                                (:symbol/qualified-name %))
                            entities))))
    ;; Analyzer duplicates collapse because edge identity includes its precise
    ;; call location; distinct source locations still remain distinct edges.
    (is (= 2 (count (filter #(and (= :edge.kind/calls (:edge/kind %))
                                  (= "duplicate/target"
                                     (:edge/target-text %)))
                            entities))))))

(deftest repeated-namespace-declarations-have-one-canonical-fact
  (let [file {:relative-path "src/repeated.clj"
              :language :language/clojure
              :content "(ns repeated)\n(ns repeated)\n(defn value [] 1)\n"
              :size 49 :modified-at 1}
        namespace {:filename "src/repeated.clj" :platforms [:clj]
                   :row 1 :col 1 :end-row 1 :end-col 14
                   :name 'repeated}
        repeated (assoc namespace :row 2 :end-row 2)
        entities
        (->> {:analysis {:namespace-definitions [namespace repeated]}}
             (clojure-analysis/materialize [file])
             (mapcat :entities))
        namespaces
        (filter #(= :symbol.kind/namespace (:symbol/kind %)) entities)]
    (is (= 1 (count namespaces)))
    (is (= 1 (:source/start-line (first namespaces))))))

(deftest smallest-enclosing-symbol-owns-local-call
  (let [outer {:symbol/id "symbol:outer" :symbol/file "file:src/nested.clj"
               :symbol/platform :clj
               :source/start-line 1 :source/start-column 1
               :source/end-line 8 :source/end-column 2}
        inner {:symbol/id "symbol:inner" :symbol/file "file:src/nested.clj"
               :symbol/platform :clj
               :source/start-line 3 :source/start-column 3
               :source/end-line 5 :source/end-column 20}
        reference
        (#'clojure-analysis/local-reference
         (#'clojure-analysis/positional-index [outer inner]) {}
         (source/index "\n\n\n    (callback)\n")
         {:filename "src/nested.clj" :platform :clj
          :row 4 :col 6 :end-row 4 :end-col 14 :name 'callback}
         nil)]
    (is (= "symbol:inner" (:reference/symbol reference)))))

(deftest positional-owner-index-does-not-scan-other-files
  (let [target {:symbol/id "symbol:target"
                :symbol/file "file:src/target.clj"
                :symbol/platform :clj
                :source/start-line 1 :source/start-column 1
                :source/end-line 10 :source/end-column 1}
        unrelated
        (mapv (fn [index]
                {:symbol/id (str "symbol:unrelated-" index)
                 :symbol/file (str "file:src/unrelated-" index ".clj")
                 :symbol/platform :clj
                 :source/start-line 1 :source/start-column 1
                 :source/end-line 10 :source/end-column 1})
              (range 1000))
        index (#'clojure-analysis/positional-index
               (into [target] unrelated))
        stats (atom {:positional-candidates-examined 0})
        matches (#'clojure-analysis/interval-matches
                 (get index ["file:src/target.clj" :clj]) [5 1] stats)]
    (is (= ["symbol:target"] (mapv :symbol/id matches)))
    (is (= 1 (:positional-candidates-examined @stats)))))

(deftest dense-var-usages-build-one-source-index-and-one-fact-per-usage
  (let [usage-count 500
        file {:relative-path "src/dense.clj"
              :language :language/clojure
              :content (apply str (repeat (+ usage-count 10) "\n"))
              :size (+ usage-count 10) :modified-at 1}
        namespace {:filename "src/dense.clj" :platforms [:clj]
                   :row 1 :col 1 :end-row 1 :end-col 11 :name 'dense}
        owner {:filename "src/dense.clj" :platforms [:clj]
               :row 2 :col 1 :end-row (+ usage-count 5) :end-col 2
               :ns 'dense :name 'owner :defined-by 'clojure.core/defn}
        target {:filename "src/dense.clj" :platforms [:clj]
                :row 3 :col 1 :end-row 3 :end-col 20
                :ns 'dense :name 'target :defined-by 'clojure.core/defn}
        usages (mapv (fn [index]
                       {:filename "src/dense.clj" :platforms [:clj]
                        :row (+ index 4) :col 3
                        :end-row (+ index 4) :end-col 11
                        :from 'dense :from-var 'owner
                        :to 'dense :name 'target :arity 0})
                     (range usage-count))
        result (clojure-analysis/materialize-with-metrics
                [file]
                {:analysis {:namespace-definitions [namespace]
                            :var-definitions [owner target]
                            :var-usages usages}})]
    (is (= 1 (get-in result [:metrics :source-indexes-built])))
    (is (= usage-count (get-in result [:metrics :var-usages])))
    (is (= (+ usage-count 5)
           (get-in result [:metrics :generated-facts])))))

(deftest positional-candidate-count-scales-with-nesting-not-repository-size
  (let [depth 40
        usages 100
        nested
        (mapv (fn [index]
                {:symbol/id (str "symbol:nested-" index)
                 :symbol/file "file:src/nested.clj" :symbol/platform :clj
                 :source/start-line (inc index) :source/start-column 1
                 :source/end-line (- 100 index) :source/end-column 1})
              (range depth))
        unrelated
        (mapv (fn [index]
                {:symbol/id (str "symbol:other-" index)
                 :symbol/file (str "file:src/other-" index ".clj")
                 :symbol/platform :clj
                 :source/start-line 1 :source/start-column 1
                 :source/end-line 100 :source/end-column 1})
              (range 1000))
        index (#'clojure-analysis/positional-index (into nested unrelated))
        stats (atom {:positional-candidates-examined 0})]
    (dotimes [_ usages]
      (#'clojure-analysis/interval-matches
       (get index ["file:src/nested.clj" :clj]) [50 1] stats))
    (is (= (* depth usages)
           (:positional-candidates-examined @stats)))))

(deftest ambiguous-cross-file-definition-is-not-resolved-by-order
  (let [owner {:symbol/id "symbol:owner" :symbol/platform :clj}
        candidate-a {:symbol/id "symbol:a"}
        candidate-b {:symbol/id "symbol:b"}
        relationship
        (#'clojure-analysis/var-relationship
         {[:clj 'caller] [owner]}
         {[:clj 'shared 'value] [candidate-a candidate-b]}
         :clj
         {:platform :clj :filename "src/caller.clj"
          :row 1 :col 2 :end-row 1 :end-col 7
          :from 'caller :to 'shared :name 'value})]
    (is (= :entity.type/reference (:entity/type relationship)))
    (is (= :ambiguous (:reference/classification relationship)))))

(deftest protocol-and-instance-analysis-shapes-are-materialized
  (let [root (Files/createTempDirectory
              "llm-context-protocol-shapes-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source (str "(ns sample.protocol)\n"
                    "(defprotocol Renderable (render [x]))\n"
                    "(defrecord View [] Renderable (render [x] 1))\n"
                    "(defn text [x] (.toString x))\n")
        files [(input root "src/protocol.clj" :language/clojure source)]
        entities (->> files
                      (clj-kondo/analyze! (project/context (str root)))
                      (clojure-analysis/materialize files)
                      (mapcat :entities))
        view-id (:symbol/id
                 (some #(when (= "sample.protocol/View"
                                 (:symbol/qualified-name %))
                          %)
                       entities))]
    (is (some #(and (= :edge.kind/protocol-implements (:edge/kind %))
                    (= view-id (:edge/from %)))
              entities))
    (is (some #(= :clj-kondo-instance-invocation
                  (:reference/evidence %))
              entities))))

(deftest same-named-methods-in-different-protocols-have-distinct-identities
  (let [root (Files/createTempDirectory
              "llm-context-protocol-method-identities-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source (str "(ns sample.protocols)\n"
                    "(defprotocol Alpha (render [x]))\n"
                    "(defprotocol Beta (render [x]))\n")
        files [(input root "src/protocols.clj" :language/clojure source)]
        methods
        (->> files
             (clj-kondo/analyze! (project/context (str root)))
             (clojure-analysis/materialize files)
             (mapcat :entities)
             (filter #(= :symbol.kind/method (:symbol/kind %))))]
    (is (= 2 (count methods)))
    (is (= #{"sample.protocols/Alpha.render"
             "sample.protocols/Beta.render"}
           (set (map :symbol/qualified-name methods))))
    (is (= #{"Alpha" "Beta"}
           (set (map :symbol/protocol-name methods))))
    (is (= 2 (count (set (map :symbol/id methods)))))))

(deftest topic-reader-never-evaluates-and-requires-static-data
  (let [executed? (atom false)
        unsafe (str "#=(reset! " (pr-str executed?) " true)")
        owner {:symbol/id "symbol:owner"
               :symbol/platform :cljs
               :symbol/qualified-name "sample.ui/render"}
        file {:content "(rf/dispatch [:save dynamic-value])"}
        reference {:reference/symbol "symbol:owner"
                   :reference/qualified-target "re-frame.core/dispatch"
                   :source/start-line 1 :source/start-column 1
                   :source/end-line 1 :source/end-column 36}
        facts (clojure-topics/extract file [owner] [reference])]
    (let [indexed (#'clojure-topics/form-index unsafe)]
      (is (empty? (:calls indexed)))
      (is (string? (:diagnostic indexed))))
    (is (false? @executed?))
    (is (empty? (filter #(= :entity.type/topic (:entity/type %)) facts)))
    (is (= :dynamic (:reference/classification (first facts))))))

(deftest framework-form-index-preserves-platforms-nesting-and-tags
  (let [indexed (#'clojure-topics/form-index
                 (str "#?(:clj (clj-only) :cljs (cljs-only (nested)))\n"
                      "(shared #custom/tag {:value 1})\n"))
        heads
        (fn [platform]
          (->> (:calls indexed)
               (keep (fn [[[candidate _ _] {:keys [form]}]]
                       (when (= platform candidate) (some-> form first str))))
               set))]
    (is (contains? (heads :clj) "clj-only"))
    (is (not (contains? (heads :cljs) "clj-only")))
    (is (contains? (heads :cljs) "cljs-only"))
    (is (contains? (heads :cljs) "nested"))
    (is (contains? (heads :clj) "shared"))
    (is (contains? (heads :cljs) "shared"))))

(deftest framework-form-heads-fail-closed-for-list-valued-heads
  (is (nil? (#'clojure-topics/form-head-name '((fn [] :value)))))
  (is (= "deref" (#'clojure-topics/form-head-name '(deref state)))))

(deftest source-byte-ranges-follow-utf8-not-character-columns
  (let [root (Files/createTempDirectory
              "llm-context-clojure-utf8-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source (str "(ns café.core)\n"
                    "(defn résumé [] \"😀\" (println :ok))\n")
        files [(input root "src/unicode.clj" :language/clojure source)]
        entities (->> files
                      (clj-kondo/analyze! (project/context (str root)))
                      (clojure-analysis/materialize files)
                      (mapcat :entities))
        println-reference
        (some #(when (= "clojure.core/println"
                        (:reference/qualified-target %))
                 %)
              entities)
        character-offset (.indexOf source "(println")
        expected-byte-offset
        (alength
         (.getBytes (subs source 0 character-offset)
                    java.nio.charset.StandardCharsets/UTF_8))]
    (is (some? println-reference))
    (is (= expected-byte-offset (:source/start-byte println-reference)))
    (is (< (:source/start-column println-reference)
           (:source/start-byte println-reference)))
    (is (every? #(and (contains? % :source/start-byte)
                      (contains? % :source/end-byte))
                (filter #(contains? % :source/start-line) entities)))))
