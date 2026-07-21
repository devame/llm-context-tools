(ns llm-context.analysis.files-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.files :as files]
            [llm-context.config :as config]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(defn- create-directory [path]
  (Files/createDirectories path
                           (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- run-git! [root & args]
  (let [command (into ["git" "-C" (str root)] args)
        process (-> (ProcessBuilder. ^java.util.List command)
                    (.redirectErrorStream true)
                    .start)
        output (slurp (.getInputStream process))]
    (when-not (zero? (.waitFor process))
      (throw (ex-info "git fixture command failed"
                      {:command command :output output})))))

(deftest whole-root-discovery-prunes-generated-content
  (let [root (Files/createTempDirectory
              "llm-context-files-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (project/context (str root))
        settings (assoc-in (config/defaults) [:semantic :providers] [])]
    (create-directory (.resolve root "frontend/src"))
    (create-directory (.resolve root "node_modules/dependency"))
    (spit (str (.resolve root "frontend/src/app.cljs")) "(defn start [] :ok)")
    (spit (str (.resolve root "frontend/src/README.md")) "not source")
    (spit (str (.resolve root "frontend/src/build.janet")) "(def main nil)")
    (spit (str (.resolve root "node_modules/dependency/hidden.js"))
          "export function hidden() {}")
    (let [{:keys [files diagnostics]}
          (files/discover context settings (jtreesitter/available-languages))]
      (is (= ["frontend/src/app.cljs"] (mapv :relative-path files)))
      (is (= [{:level :warning
               :kind :grammar-unavailable
               :file "frontend/src/build.janet"
               :language :language/janet}]
             diagnostics)))))

(deftest git-project-discovery-honors-ignore-rules
  (let [root (Files/createTempDirectory
              "llm-context-git-files-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (project/context (str root))
        settings (config/defaults)]
    (create-directory (.resolve root "src"))
    (create-directory (.resolve root "generated"))
    (spit (str (.resolve root ".gitignore")) "generated/\n")
    (spit (str (.resolve root "src/app.js")) "export function visible() {}")
    (spit (str (.resolve root "generated/hidden.js"))
          "export function hidden() {}")
    (run-git! root "init" "--quiet")
    (let [result (files/discover context settings
                                 (jtreesitter/available-languages))]
      (is (= ["src/app.js"] (mapv :relative-path (:files result))))
      (is (empty? (:diagnostics result))))))

(deftest missing-explicit-includes-are-diagnostics
  (let [context (project/context
                 (str (Files/createTempDirectory
                       "llm-context-missing-"
                       (make-array java.nio.file.attribute.FileAttribute 0))))
        settings (assoc-in (config/defaults) [:analysis :include] ["missing"])
        result (files/discover context settings (jtreesitter/available-languages))]
    (is (empty? (:files result)))
    (is (= :missing-include (get-in result [:diagnostics 0 :kind])))))
