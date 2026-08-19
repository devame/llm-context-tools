(ns llm-context.analysis.project-analyzer-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.project-analyzer :as project-analyzer]))

(defn- discovered [path]
  {:relative-path path})

(defn- output [path]
  {:file {:file/path path}})

(deftest analyzer-output-must-be-a-source-inventory-bijection
  (doseq [[files outputs expected]
          [[[ (discovered "src/a.clj")]
            [(output "src/a.clj") (output "src/a.clj")]
            :duplicate-paths]
           [[(discovered "src/a.clj") (discovered "src/b.clj")]
            [(output "src/a.clj")]
            :missing-paths]
           [[(discovered "src/a.clj")]
            [(output "src/a.clj") (output "src/extra.clj")]
            :extra-paths]]]
    (let [error
          (try
            (#'project-analyzer/outputs-by-path! files outputs)
            nil
            (catch clojure.lang.ExceptionInfo error error))]
      (is (some? error))
      (is (seq (get (ex-data error) expected))))))

(deftest analyzer-output-bijection-retains-one-output-per-path
  (is (= #{"src/a.clj" "src/b.janet"}
         (set
          (keys
           (#'project-analyzer/outputs-by-path!
            [(discovered "src/a.clj") (discovered "src/b.janet")]
            [(output "src/b.janet") (output "src/a.clj")]))))))

(deftest provider-diagnostics-retain-only-stage-scoped-messages
  (let [stage-warning {:kind :clj-kondo-hook-not-found :count 25}
        file-warning {:kind :clj-kondo :file "src/a.clj"}]
    (is (= [stage-warning]
           (#'project-analyzer/provider-diagnostics
            {:diagnostics [file-warning stage-warning]})))))

(deftest semantic-fingerprint-ignores-order-and-derived-search-text
  (let [symbol-a {:entity/type :entity.type/symbol
                  :symbol/id "symbol:a" :symbol/name "a"
                  :symbol/search-text "derived-a"}
        symbol-b {:entity/type :entity.type/symbol
                  :symbol/id "symbol:b" :symbol/name "b"}]
    (is (= (project-analyzer/semantic-fingerprint
            {:entities [symbol-a symbol-b]})
           (project-analyzer/semantic-fingerprint
            {:entities [(assoc symbol-b :db/id 42)
                        (assoc symbol-a :symbol/search-text "different")]})))
    (is (not= (project-analyzer/semantic-fingerprint
               {:entities [symbol-a symbol-b]})
              (project-analyzer/semantic-fingerprint
               {:entities [symbol-a (assoc symbol-b :symbol/name "changed")]})))))
