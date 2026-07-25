(ns llm-context.analysis.clj-kondo-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(defn- file-input [path language content]
  {:path path
   :relative-path (str (.getFileName path))
   :language language
   :content content
   :size (count (.getBytes content
                           java.nio.charset.StandardCharsets/UTF_8))
   :modified-at 1})

(deftest embedded-analysis-resolves-aliases-refers-locals-and-macros
  (let [root (Files/createTempDirectory
              "llm-context-kondo-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        api (.resolve root "api.clj")
        caller (.resolve root "caller.clj")
        api-source "(ns sample.api)\n(defn greet [x] x)\n"
        caller-source
        (str "(ns sample.caller (:require [sample.api :as api]"
             " [clojure.string :refer [upper-case]]))\n"
             "(defn run [x] (let [f api/greet] (upper-case (f x))))\n")]
    (spit (str api) api-source)
    (spit (str caller) caller-source)
    (let [snapshot
          (clj-kondo/analyze!
           (project/context (str root))
           [(file-input api :language/clojure api-source)
            (file-input caller :language/clojure caller-source)])
          usages (get-in snapshot [:analysis :var-usages])
          locals (get-in snapshot [:analysis :locals])
          local-usages (get-in snapshot [:analysis :local-usages])]
      (is (= :clj-kondo (:analyzer snapshot)))
      (is (= "2026.07.24" (:analyzer-version snapshot)))
      (is (some #(and (= 'greet (:name %))
                      (= 'sample.api (:to %))
                      (= 'sample.caller (:from %)))
                usages))
      (is (some #(and (= 'upper-case (:name %))
                      (= 'clojure.string (:to %)))
                usages))
      (is (some #(= 'f (:name %)) locals))
      (is (some #(= 'f (:name %)) local-usages))
      (is (Files/isDirectory
           (.resolve root ".llm-context/cache/clj-kondo")
           (make-array java.nio.file.LinkOption 0))))))

(deftest unsupported-files-never-enter-the-provider
  (let [root (Files/createTempDirectory
              "llm-context-kondo-scope-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve root "ignored.js")
        source "function ignored() {}"]
    (spit (str path) source)
    (let [snapshot
          (clj-kondo/analyze!
           (project/context (str root))
           [(file-input path :language/javascript source)])]
      (is (empty? (:analysis snapshot)))
      (is (empty? (:diagnostics snapshot))))))

(deftest only-source-integrity-findings-become-analysis-diagnostics
  (let [root (Files/createTempDirectory
              "llm-context-kondo-malformed-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve root "broken.clj")
        source "(ns broken)\n(defn unfinished ["]
    (spit (str path) source)
    (let [diagnostics
          (:diagnostics
           (clj-kondo/analyze!
            (project/context (str root))
            [(file-input path :language/clojure source)]))]
      (is (seq diagnostics))
      (is (every? #(contains? clj-kondo/source-integrity-finding-types
                              (:type %))
                  diagnostics)))))
