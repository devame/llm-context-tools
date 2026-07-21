(ns llm-context.export-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.export :as export]
            [llm-context.model.ids :as ids]
            [llm-context.project :as project]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(deftest exports-are-projections-not-storage
  (let [root (Files/createTempDirectory "llm-context-export-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        file {:entity/type :entity.type/file :file/id "file:src/a.clj"
              :file/path "src/a.clj" :file/language :language/clojure
              :file/content-hash (ids/content-hash "a")
              :file/size 1 :file/modified-at 1}
        symbol {:entity/type :entity.type/symbol :symbol/id "symbol:a"
                :symbol/name "a" :symbol/qualified-name "sample/a"
                :symbol/kind :symbol.kind/function :symbol/file (:file/id file)
                :symbol/signature "(defn a [])"
                :source/start-line 1 :source/start-column 1
                :source/end-line 1 :source/end-column 12}]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file [symbol])
      (is (= 2 (count (export/entities graph))))
      (let [decoded (json/read-str (export/render graph :json))]
        (is (= 1 (get decoded "schema/version")))
        (is (= 2 (count (get decoded "entities")))))
      (is (= 2 (count (str/split-lines (export/render graph :jsonl)))))
      (is (re-find #"Files: 1" (export/render graph :markdown))))))
