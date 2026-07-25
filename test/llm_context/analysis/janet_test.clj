(ns llm-context.analysis.janet-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.janet :as janet]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(defn- input [root relative content]
  (let [path (.resolve root relative)]
    (Files/createDirectories
     (.getParent path)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) content)
    {:path path :relative-path relative :language :language/janet
     :content content
     :size (count (.getBytes content
                             java.nio.charset.StandardCharsets/UTF_8))
     :modified-at 1}))

(deftest janet-analysis-resolves-modules-core-macros-and-lexical-calls
  (let [root (Files/createTempDirectory
              "llm-context-janet-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        files [(input root "lib/names.janet"
                      "(defn format-name [x] (string/ascii-lower x))")
               (input root "main.janet"
                      (str "(import ./lib/names :as names)\n"
                           "(defmacro traced [body] body)\n"
                           "(defn run [x]\n"
                           " (let [f names/format-name]\n"
                           "  (print (f (names/format-name x)))))"))]
        result (janet/analyze (project/context (str root)) files)
        entities (mapcat :entities (:outputs result))
        edges (filter #(= :entity.type/edge (:entity/type %)) entities)
        refs (filter #(= :entity.type/reference (:entity/type %)) entities)]
    (is (= "1.41.2" (:catalog-version result)))
    (is (some #(and (= :edge.kind/imports (:edge/kind %))
                    (= "./lib/names" (:edge/target-text %))) edges))
    (is (some #(and (= :edge.kind/calls (:edge/kind %))
                    (= "names/format-name" (:edge/target-text %))) edges))
    (is (some #(and (= :dynamic (:reference/classification %))
                    (= "f" (:reference/target-text %))) refs))
    (is (some #(and (= :external (:reference/classification %))
                    (= "print" (:reference/target-text %))) refs))
    (is (not-any? #(contains? #{"defn" "defmacro" "let"}
                              (:reference/target-text %))
                  refs))))

(deftest malformed-janet-output-is-marked-for-preservation
  (let [root (Files/createTempDirectory
              "llm-context-janet-malformed-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        result (janet/analyze
                (project/context (str root))
                [(input root "broken.janet" "(defn broken [x]")])
        output (first (:outputs result))]
    (is (:preserve? output))
    (is (= :parse-error (get-in output [:diagnostics 0 :kind])))))
