(ns llm-context.parser.provider-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.parser.provider :as provider]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(deftest extension-detection-is-explicit
  (is (= :language/javascript (provider/language-for-path "src/app.js")))
  (is (= :language/clojure (provider/language-for-path "src/app.cljc")))
  (is (nil? (provider/language-for-path "README"))))

(deftest javascript-parses-through-jtreesitter
  (let [root (Files/createTempDirectory "llm-context-parser-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (with-open [parser (jtreesitter/open (project/context (str root)))]
      (let [parsed (provider/parse-source parser :language/javascript
                                          "export function greet(name) { return name; }")]
        (is (= :language/javascript (:language parsed)))
        (is (= "program" (get-in parsed [:root :type])))
        (is (false? (get-in parsed [:root :error?])))
        (is (some #(= "export_statement" (:type %))
                  (get-in parsed [:root :children])))))))

(deftest packaged-language-matrix
  (let [root (Files/createTempDirectory "llm-context-languages-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        samples {:language/typescript "function greet(name: string): string { return name; }"
                 :language/python "def greet(name):\n    return name\n"
                 :language/java "class App { String greet(String name) { return name; } }"
                 :language/go "package main\nfunc greet(name string) string { return name }"
                 :language/rust "fn greet(name: &str) -> &str { name }"
                 :language/c "int greet(int value) { return value; }"
                 :language/cpp "class App { public: int greet(int value) { return value; } };"
                 :language/ruby "def greet(name)\n  name\nend\n"
                 :language/php "<?php function greet($name) { return $name; }"
                 :language/bash "greet() { echo \"$1\"; }"
                 :language/clojure "(ns app) (defn greet [name] name)"}]
    (with-open [parser (jtreesitter/open (project/context (str root)))]
      (is (= (set (keys samples))
             (disj (provider/supported-languages parser) :language/javascript)))
      (doseq [[language source] samples]
        (let [parsed (provider/parse-source parser language source)]
          (is (= language (:language parsed)))
          (is (false? (get-in parsed [:root :error?])) language))))))
