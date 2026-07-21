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
