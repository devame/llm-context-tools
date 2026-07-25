(ns llm-context.analysis.clojure-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.clojure :as clojure-analysis]
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
