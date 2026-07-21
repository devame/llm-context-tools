(ns llm-context.model.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.model.ids :as ids]
            [llm-context.model.schema :as schema]))

(def file-entity
  {:entity/type :entity.type/file
   :file/id "file:src/example.clj"
   :file/path "src/example.clj"
   :file/language :language/clojure
   :file/content-hash (ids/content-hash "(ns example)")
   :file/size 12
   :file/modified-at 100})

(deftest deterministic-identities
  (is (= (ids/content-hash "same") (ids/content-hash "same")))
  (is (not= (ids/content-hash "same") (ids/content-hash "different")))
  (let [parts {:file-id "file:a.clj" :kind :symbol.kind/function
               :qualified-name "a/run" :signature "[]"
               :start-line 3 :start-column 1}]
    (is (= (ids/symbol-id parts) (ids/symbol-id parts)))
    (is (not= (ids/symbol-id parts)
              (ids/symbol-id (assoc parts :qualified-name "a/stop"))))))

(deftest canonical-entities-validate
  (is (= file-entity (schema/validate-entity! file-entity)))
  (is (schema/validate-entity!
       {:entity/type :entity.type/symbol
        :symbol/id "symbol:abc"
        :symbol/name "run"
        :symbol/qualified-name "example/run"
        :symbol/kind :symbol.kind/function
        :symbol/file "file:src/example.clj"
        :source/start-line 2 :source/start-column 1
        :source/end-line 2 :source/end-column 20})))

(deftest invalid-entities-fail-before-storage
  (testing "confidence is bounded"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invalid semantic"
         (schema/validate-entity!
          {:entity/type :entity.type/edge
           :edge/id "edge:abc"
           :edge/kind :edge.kind/calls
           :edge/from "symbol:source"
           :edge/target-text "target"
           :edge/resolution :resolution/unresolved
           :edge/confidence 1.5})))))
