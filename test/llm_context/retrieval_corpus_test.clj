(ns llm-context.retrieval-corpus-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.retrieval-corpus :as corpus]))

(def symbols
  [{:id "symbol:clj" :qualified-name "sample.core/shared"
    :name "shared" :platform :clj :file "src/sample/core.cljc"
    :kind :symbol.kind/function}
   {:id "symbol:cljs" :qualified-name "sample.core/shared"
    :name "shared" :platform :cljs :file "src/sample/core.cljc"
    :kind :symbol.kind/function}
   {:id "symbol:other" :qualified-name "sample.core/other"
    :name "other" :platform :cljs :file "src/sample/core.cljc"
    :kind :symbol.kind/function}])

(deftest format-two-resolution-rejects-ambiguous-selectors
  (let [query {:id :ambiguous
               :evaluation/corpus-version 2
               :relevance [{:qualified-name "sample.core/shared" :grade 3}]
               :hard-negatives []}
        errors (#'corpus/query-resolution-errors symbols query)]
    (is (empty? (:missing errors)))
    (is (= 1 (count (:ambiguous errors))))))

(deftest platform-qualification-resolves-one-symbol
  (let [query {:id :specific
               :evaluation/corpus-version 2
               :relevance [{:qualified-name "sample.core/shared"
                            :platform :cljs :grade 3}]
               :hard-negatives [{:qualified-name "sample.core/shared"
                                 :platform :clj}]}
        errors (#'corpus/query-resolution-errors symbols query)]
    (is (= {:missing [] :ambiguous [] :overlap nil} errors))))

(deftest canonical-overlap-is-rejected-even-for-different-selectors
  (let [query {:id :overlap
               :evaluation/corpus-version 2
               :relevance [{:id "symbol:cljs" :grade 3}]
               :hard-negatives [{:qualified-name "sample.core/shared"
                                 :platform :cljs}]}
        errors (#'corpus/query-resolution-errors symbols query)]
    (is (= {:query-id :overlap :symbol-ids ["symbol:cljs"]}
           (:overlap errors)))))
