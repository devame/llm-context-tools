(ns llm-context.parser.provider-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.parser.provider :as provider]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(deftest extension-detection-is-explicit
  (is (nil? (provider/language-for-path "src/app.js")))
  (is (= :language/clojure (provider/language-for-path "src/app.clj")))
  (is (= :language/clojurescript (provider/language-for-path "src/app.cljs")))
  (is (= :language/clojure-common (provider/language-for-path "src/app.cljc")))
  (is (= :language/janet (provider/language-for-path "src/app.janet")))
  (is (= :language/edn-data (provider/language-for-path "deps.edn")))
  (is (= :language/edn-data
         (provider/language-for-path ".clj-kondo/config.edn")))
  (is (nil? (provider/language-for-path "config.edn")))
  (is (nil? (provider/language-for-path "README"))))

(deftest janet-native-libraries-cover-supported-platforms
  (doseq [resource ["lib/x86_64-linux-gnu-tree-sitter-janet.so"
                    "lib/aarch64-linux-gnu-tree-sitter-janet.so"
                    "lib/x86_64-macos-tree-sitter-janet.dylib"
                    "lib/aarch64-macos-tree-sitter-janet.dylib"
                    "lib/x86_64-windows-tree-sitter-janet.dll"]]
    (is (some? (io/resource resource)) resource)))

(deftest packaged-language-matrix
  (let [root (Files/createTempDirectory "llm-context-languages-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        samples {:language/janet
                 "(import path)\n(defn greet [name] (print name))\n``long string``"}]
    (with-open [parser (jtreesitter/open (project/context (str root)))]
      (is (= (set (keys samples)) (provider/supported-languages parser)))
      (doseq [[language source] samples]
        (let [parsed (provider/parse-source parser language source)]
          (is (= language (:language parsed)))
          (is (false? (get-in parsed [:root :error?])) language))))))
