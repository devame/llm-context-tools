(ns llm-context.semantic.scip-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.scip :as scip])
  (:import [java.nio.file Files Path]))

(defn- temp-project []
  (let [root (Files/createTempDirectory "llm-context-scip-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (spit (str (.resolve root "package.json"))
          "{\"name\":\"scip-fixture\",\"version\":\"1.0.0\"}")
    (spit (str (.resolve root "tsconfig.json"))
          "{\"compilerOptions\":{\"target\":\"ES2022\"},\"include\":[\"src/**/*.ts\"]}")
    (Files/createDirectories (.resolve root "src")
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str (.resolve root "src/a.ts"))
          "export function greet(name: string): string { return name; }")
    (project/context (str root))))

(deftest scip-typescript-process-and-wire-decoder
  (let [project (temp-project)
        executable (.toAbsolutePath
                    (Path/of "node_modules/.bin/scip-typescript"
                             (make-array String 0)))
        settings (assoc-in (config/defaults)
                           [:semantic :scip-typescript-command]
                           [(str executable) "index"])
        result (scip/run! project settings)
        document (first (:documents result))]
    (is (= "src/a.ts" (:relative-path document)))
    (is (some #(= "greet" (scip/symbol-display-name %)) (:symbols document)))
    (is (some #(pos? (bit-and 1 (:roles %))) (:occurrences document)))
    (is (Files/exists ^Path (:path result)
                      (make-array java.nio.file.LinkOption 0)))))
