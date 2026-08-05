(ns llm-context.analysis.manifest-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.manifest :as manifest]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(defn- candidate []
  {:analyzers {:clj-kondo {:version "1" :configuration-fingerprint "cfg"}
               :janet {:catalog-version "1"}
               :semantic-fingerprint {:version 1}}
   :outputs
   [{:file {:file/id "file:src/a.clj" :file/path "src/a.clj"
            :file/content-hash "sha256:content"
            :file/semantic-hash "sha256:semantic"}
     :entities
     [{:entity/type :entity.type/symbol :symbol/id "symbol:a"
       :symbol/file "file:src/a.clj" :symbol/platform :clj
       :symbol/kind :symbol.kind/function
       :symbol/qualified-name "sample/a"}
      {:entity/type :entity.type/edge :edge/id "edge:a"
       :edge/kind :edge.kind/imports :edge/target-text "sample.dep"}]}]})

(deftest active-manifest-is-atomic-versioned-and-fail-closed
  (let [root (Files/createTempDirectory
              "llm-context-manifest-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        candidate (candidate)
        revision "sha256:revision"
        index (manifest/write! project candidate revision)
        loaded (manifest/load-active project revision (:analyzers candidate))]
    (is (= manifest/version (:version index)))
    (is (= #{[:clj "sample/a"]}
           (get-in loaded [:files "src/a.clj" :exported-keys])))
    (is (= #{"sample.dep"}
           (get-in loaded [:files "src/a.clj" :imported-namespaces])))
    (is (nil? (manifest/load-active project "sha256:stale"
                                    (:analyzers candidate))))
    (let [directory (.resolve (:state-dir project)
                              "cache/analyzer-manifests")
          shard (get-in index [:files "src/a.clj" :shard])]
      (spit (str (.resolve directory shard)) "{:corrupt")
      (is (nil? (manifest/load-active project revision
                                      (:analyzers candidate)))))))
