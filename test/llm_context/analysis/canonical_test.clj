(ns llm-context.analysis.canonical-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.analysis.canonical :as canonical]
            [llm-context.analysis.ir :as ir]
            [llm-context.model.ids :as ids]))

(def file
  {:entity/type :entity.type/file
   :file/id "file:src/sample.clj"
   :file/path "src/sample.clj"
   :file/language :language/clojure
   :file/content-hash (ids/content-hash "0123456789")
   :file/size 10
   :file/modified-at 1})

(defn definition [id qualified-name start-byte end-byte]
  {:entity/type :entity.type/symbol
   :symbol/id id
   :symbol/name (last (clojure.string/split qualified-name #"/"))
   :symbol/qualified-name qualified-name
   :symbol/kind :symbol.kind/function
   :symbol/file (:file/id file)
   :symbol/platform :clj
   :symbol/analyzer :clj-kondo
   :symbol/scope :scope/top-level
   :symbol/role :role/definition
   :symbol/indexable? true
   :source/start-line 1 :source/start-column (inc start-byte)
   :source/end-line 1 :source/end-column (inc end-byte)
   :source/start-byte start-byte :source/end-byte end-byte})

(deftest canonicalization-collapses-only-identical-observations
  (let [a (definition "symbol:a" "sample/a" 0 1)
        b (definition "symbol:b" "sample/a" 2 3)]
    (testing "exact duplicates collapse after storage-only attributes are gone"
      (is (= [file (canonical/normalize-entity a)]
             (canonical/canonicalize-entities
              [file a (assoc a :db/id 42)]))))
    (testing "legitimate scoped repetitions retain their distinct identity"
      (is (= 3 (count (canonical/canonicalize-entities [file a b])))))
    (testing "same identity with different facts is rejected deterministically"
      (try
        (canonical/canonicalize-entities
         [file a (assoc a :symbol/qualified-name "sample/other")])
        (is false "expected conflicting identity")
        (catch clojure.lang.ExceptionInfo error
          (is (= [:symbol/id "symbol:a"] (:identity (ex-data error))))
          (is (= 2 (count (:conflicting-facts (ex-data error))))))))))

(deftest compatibility-normalization-is-explicit-and-deterministic
  (let [normalized
        (canonical/normalize-entity
         (dissoc (definition "symbol:a" "sample/a" 0 1)
                 :symbol/scope :symbol/role :symbol/indexable?))]
    (is (= :scope/top-level (:symbol/scope normalized)))
    (is (= :role/definition (:symbol/role normalized)))
    (is (true? (:symbol/indexable? normalized)))
    (is (= {:entity/analyzer :clj-kondo
            :entity/record-kind :entity.type/symbol
            :entity/evidence :analyzer-definition}
           (select-keys normalized
                        [:entity/analyzer :entity/record-kind
                         :entity/evidence])))))

(deftest source-range-and-provenance-constructors
  (is (= {:source/start-line 1 :source/start-column 2
          :source/end-line 3 :source/end-column 4
          :source/start-byte 0 :source/end-byte 8}
         (ir/source-range 1 2 3 4 0 8)))
  (is (= {:entity/analyzer :clj-kondo
          :entity/record-kind :var-definition
          :entity/evidence :clj-kondo-analysis}
         (ir/provenance :clj-kondo :var-definition :clj-kondo-analysis))))

(deftest full-snapshot-audit-checks-all-foreign-keys-and-byte-bounds
  (let [a (canonical/normalize-entity
           (definition "symbol:a" "sample/a" 0 1))
        edge (canonical/normalize-entity
              {:entity/type :entity.type/edge
               :edge/id "edge:self"
               :edge/kind :edge.kind/calls
               :edge/from "symbol:a"
               :edge/to "symbol:a"
               :edge/target-text "sample/a"
               :edge/resolution :resolution/exact
               :edge/confidence 1.0
               :edge/evidence :clj-kondo-var-usage}
              {:analyzer :clj-kondo})]
    (is (= [file a edge]
           (canonical/audit-snapshot! [file a edge])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"invalid foreign key"
         (canonical/audit-snapshot!
          [file (assoc edge :edge/from "symbol:missing")])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"exceeds its owning file"
         (canonical/audit-snapshot!
          [file (assoc a :source/end-byte 11)])))))
