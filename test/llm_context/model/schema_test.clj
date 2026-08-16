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
  (let [parts {:platform :clj :file-id "file:a.clj"
               :kind :symbol.kind/function :qualified-name "a/run"}]
    (is (= (ids/symbol-id parts) (ids/symbol-id parts)))
    (is (= (ids/symbol-id parts)
           (ids/symbol-id (assoc parts :signature "[x]"
                                 :start-line 30 :start-column 4))))
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
        :symbol/platform :clj
        :symbol/analyzer :clj-kondo
        :symbol/scope :scope/top-level
        :symbol/role :role/definition
        :symbol/indexable? true
        :source/start-line 2 :source/start-column 1
        :source/end-line 2 :source/end-column 20
        :source/start-byte 13 :source/end-byte 32})))

(deftest invalid-entities-fail-before-storage
  (testing "confidence is bounded"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invalid semantic"
         (schema/validate-entity!
          {:entity/type :entity.type/edge
           :edge/id "edge:abc"
           :edge/kind :edge.kind/calls
           :edge/from "symbol:source"
           :edge/to "symbol:target"
           :edge/target-text "target"
           :edge/resolution :resolution/exact
           :edge/confidence 1.5
           :edge/evidence :clj-kondo-var-usage})))))

(deftest symbol-search-text-preserves-and-expands-code-identifiers
  (let [text (schema/symbol-search-text
              {:symbol/name "safeFields"
               :symbol/qualified-name "sample.edn/safe-fields!"
               :symbol/signature "[inputValue]"
               :symbol/doc "Keep supported primitive values."})]
    (is (clojure.string/includes? text "safeFields"))
    (is (clojure.string/includes? text "safe fields"))
    (is (clojure.string/includes? text "sample edn safe fields"))
    (is (clojure.string/includes? text "input value"))))

(deftest symbol-search-grams-cover-short-and-qualified-substrings
  (let [grams (schema/symbol-search-grams
               {:symbol/name "safeFields"
                :symbol/qualified-name "sample.edn/safe-fields!"})]
    (is (contains? grams "s"))
    (is (contains? grams "fi"))
    (is (contains? grams "saf"))
    (is (contains? grams "/sa"))
    (is (not-any? #(> (count %) 3) grams))))

(deftest diagnostic-references-are-not-traversable-edges
  (let [reference {:entity/type :entity.type/reference
                   :reference/id "reference:divide"
                   :reference/kind :edge.kind/calls
                   :reference/symbol "symbol:source"
                   :reference/target-text "/"
                   :reference/classification :external
                   :reference/evidence :clj-kondo-var-usage}]
    (is (= reference (schema/validate-entity! reference)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate-entity!
                  {:entity/type :entity.type/edge
                   :edge/id "edge:divide"
                   :edge/kind :edge.kind/calls
                   :edge/from "symbol:source"
                   :edge/target-text "/"
                   :edge/resolution :resolution/unresolved
                   :edge/confidence 0.0
                   :edge/evidence :tree-sitter-syntax})))))

(deftest graph-format-four-has-explicit-symbol-aggregate-and-range-contracts
  (is (= 4 schema/graph-format-version))
  (testing "semantic symbols cannot silently omit their role or scope"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate-entity!
                  {:entity/type :entity.type/symbol
                   :symbol/id "symbol:incomplete"
                   :symbol/name "run"
                   :symbol/qualified-name "example/run"
                   :symbol/kind :symbol.kind/function
                   :symbol/file "file:src/example.clj"
                   :symbol/platform :clj
                   :symbol/analyzer :clj-kondo}))))
  (testing "incomplete and reversed byte ranges are invalid"
    (doseq [range [{:source/start-line 1 :source/start-column 1
                    :source/end-line 1 :source/end-column 2
                    :source/start-byte 0}
                   {:source/start-line 1 :source/start-column 1
                    :source/end-line 1 :source/end-column 2
                    :source/start-byte 8 :source/end-byte 4}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (schema/validate-entity!
                    (merge
                     {:entity/type :entity.type/symbol
                      :symbol/id "symbol:bad-range"
                      :symbol/name "run"
                      :symbol/qualified-name "example/run"
                      :symbol/kind :symbol.kind/function
                      :symbol/file "file:src/example.clj"
                      :symbol/platform :clj
                      :symbol/analyzer :clj-kondo
                      :symbol/scope :scope/top-level
                      :symbol/role :role/definition
                      :symbol/indexable? true}
                     range)))))))
