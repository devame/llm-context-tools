(ns llm-context.context-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.context :as context]
            [llm-context.model.ids :as ids]
            [llm-context.project :as project]
            [llm-context.store :as store])
  (:import [java.nio.file Files]))

(deftest context-packets-are-focused-depth-bounded-and-renderable
  (let [root (Files/createTempDirectory "llm-context-packet-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        file {:entity/type :entity.type/file :file/id "file:chain.clj"
              :file/path "chain.clj" :file/language :language/clojure
              :file/content-hash (ids/content-hash "chain")
              :file/size 5 :file/modified-at 1}
        make-symbol (fn [id name line]
                      {:entity/type :entity.type/symbol :symbol/id id :symbol/name name
                       :symbol/qualified-name (str "chain/" name)
                       :symbol/kind :symbol.kind/function :symbol/file (:file/id file)
                       :symbol/signature (str "(defn " name " [])")
                       :source/start-line line :source/start-column 1
                       :source/end-line line :source/end-column 12})
        symbols [(make-symbol "symbol:a" "a" 1) (make-symbol "symbol:b" "b" 2)
                 (make-symbol "symbol:c" "c" 3)]
        edge (fn [id from to target line]
               {:entity/type :entity.type/edge :edge/id id :edge/kind :edge.kind/calls
                :edge/from from :edge/to to :edge/target-text target
                :edge/resolution :resolution/exact :edge/confidence 1.0
                :source/start-line line :source/start-column 1
                :source/end-line line :source/end-column 5})
        entities (concat symbols [(edge "edge:ab" "symbol:a" "symbol:b" "b" 1)
                                  (edge "edge:bc" "symbol:b" "symbol:c" "c" 2)])]
    (store/with-store [graph project (config/defaults)]
      (store/replace-file! graph file entities)
      (let [packet (context/build graph {:focus "a" :depth 1 :max-tokens 1000})]
        (is (= #{"symbol:a" "symbol:b"} (set (map :id (:symbols packet)))))
        (is (not-any? #(= "symbol:c" (:id %)) (:symbols packet)))
        (is (re-find #"Code context: a" (context/markdown packet)))
        (is (<= (get-in packet [:budget :estimated-tokens]) 1000))))))
