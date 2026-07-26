(ns llm-context.analysis.clojure-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.clojure :as clojure-analysis]
            [llm-context.analysis.clojure-topics :as clojure-topics]
            [llm-context.analysis.project-analyzer :as project-analyzer]
            [llm-context.project :as project])
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
    (is (empty? (clojure-topics/extract file [owner] [reference])))
    (is (nil? (#'clojure-topics/offset-at (:content file) nil nil)))))

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
         [owner] [] {"src/sample.cljs" {:content content}} record)]
    (is (= "done" (:reference/target-text reference)))
    (is (= :dynamic (:reference/classification reference)))
    (is (nil?
         (#'clojure-analysis/local-reference
          [owner] [] {"src/sample.cljs" {:content ""}} record)))))

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
             "  (rf/reg-event-db :save (fn [db _] db))\n"
             "  (rf/reg-sub :saved (fn [db _] (:saved db))))\n"
             "(defn render! []\n"
             "  (rf/dispatch [:save 1])\n"
             "  (rf/subscribe [:saved])\n"
             "  (swap! app-db assoc-in [:saved-programs] [])\n"
             "  (get-in @app-db [:saved-programs]))\n"
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
