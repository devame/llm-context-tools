(ns llm-context.analysis.structural-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.analysis.structural :as structural]
            [llm-context.indexer :as indexer]
            [llm-context.model.schema :as schema]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(defn analyze [language path content]
  (let [root (Files/createTempDirectory "llm-context-structural-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-open [parser (jtreesitter/open (project/context (str root)))]
      (indexer/index-file
       (structural/create parser)
       {:relative-path path :language language :content content
        :size (count (.getBytes content java.nio.charset.StandardCharsets/UTF_8))
        :modified-at 1}))))

(deftest clojurescript-structure-becomes-canonical-facts
  (let [{:keys [file entities diagnostics]}
        (analyze :language/clojurescript "src/app.cljs"
                 "(ns app) (defn greet [n] (println n))")
        symbols (filter :symbol/id entities)
        edges (filter :edge/id entities)]
    (is (empty? diagnostics))
    (is (= "file:src/app.cljs" (:file/id file)))
    (is (some #(= "greet" (:symbol/name %)) symbols))
    (is (some #(and (= :edge.kind/calls (:edge/kind %))
                    (= "println" (:edge/target-text %))) edges))
    (doseq [entity (cons file entities)]
      (is (= entity (schema/validate-entity! entity))))))

(deftest clojure-forms-produce-namespace-qualified-symbols
  (let [{:keys [entities]}
        (analyze :language/clojure "src/app/core.clj"
                 "(ns app.core)\n(defn greet [name]\n  (println name))")]
    (is (some #(= "app.core/greet" (:symbol/qualified-name %)) entities))
    (is (some #(and (= :edge.kind/calls (:edge/kind %))
                    (= "println" (:edge/target-text %))) entities))))

(deftest janet-forms-produce-symbol-call-and-import-facts
  (let [{:keys [entities diagnostics]}
        (analyze :language/janet "src/app.janet"
                 (str "(import path :as p)\n"
                      "(def api-version 1)\n"
                      "(var *runs* 0)\n"
                      "(defmacro traced [body] body)\n"
                      "(defn greet [name]\n"
                      "  (print (p/join \"hello\" name)))\n"))
        symbols (filter :symbol/id entities)]
    (is (empty? diagnostics))
    (is (= #{"api-version" "*runs*" "traced" "greet"}
           (set (map :symbol/name (remove #(= :symbol.kind/module
                                              (:symbol/kind %))
                                         symbols)))))
    (is (some #(and (= :edge.kind/imports (:edge/kind %))
                    (= "path" (:edge/target-text %))) entities))
    (is (some #(and (= :edge.kind/calls (:edge/kind %))
                    (= "print" (:edge/target-text %))) entities))
    (is (some #(and (= :edge.kind/calls (:edge/kind %))
                    (= "p/join" (:edge/target-text %))) entities))
    (is (not-any? #(and (= :edge.kind/calls (:edge/kind %))
                        (contains? #{"def" "var" "defn" "defmacro" "import"}
                                   (:edge/target-text %)))
                  entities))
    (doseq [entity entities]
      (is (= entity (schema/validate-entity! entity))))))
