(ns llm-context.analysis.staging-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.staging :as staging]
            [llm-context.config :as config]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(defn- fixture []
  (let [root (Files/createTempDirectory
              "llm-context-staging-"
              (make-array java.nio.file.attribute.FileAttribute 0))]
    [(project/context (str root)) (config/defaults)]))

(def output
  {:file {:file/path "src/app.clj" :file/content-hash "sha256:content"}
   :entities [{:entity/type :entity.type/symbol :symbol/id "symbol:a"}]
   :diagnostics []})

(deftest complete-generation-round-trips-and-mismatches-fail-closed
  (let [[project settings] (fixture)
        contract {:analyzer "fixture" :format 4}
        inventory [["src/app.clj" "sha256:content"]]
        snapshot {:outputs [output] :analyzers {:fixture "1"}
                  :analysis-metrics {:files 1} :diagnostics []}
        result (staging/write-generation!
                project settings contract inventory snapshot nil)]
    (is (= 1 (:files result)))
    (is (= [output] (:outputs
                     (staging/load-generation project settings contract inventory))))
    (is (nil? (staging/load-generation
               project settings contract
               [["src/app.clj" "sha256:different"]])))))

(deftest partial-generation-is-never-loadable
  (let [[project settings] (fixture)
        contract {:analyzer "fixture"}
        inventory [["src/app.clj" "sha256:content"]]
        directory (staging/generation-path project settings contract inventory)]
    (Files/createDirectories directory
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString (.resolve directory "orphan.edn.gz") "partial"
                       (make-array java.nio.file.OpenOption 0))
    (is (nil? (staging/load-generation project settings contract inventory)))))

(deftest generation-size-limit-stops-before-publishing-index
  (let [[project settings] (fixture)
        settings (assoc-in settings
                           [:analysis :maximum-staging-generation-bytes] 1)
        contract {:analyzer "fixture"}
        inventory [["src/app.clj" "sha256:content"]]
        snapshot {:outputs [output]}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"configured size limit"
         (staging/write-generation! project settings contract inventory
                                    snapshot nil)))
    (is (nil? (staging/load-generation project settings contract inventory)))))

(deftest staging-refuses-paths-outside-project-state
  (let [[project settings] (fixture)
        settings (assoc-in settings [:analysis :staging-directory] "staging")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"inside the project state directory"
         (staging/generation-path project settings {} [])))))
