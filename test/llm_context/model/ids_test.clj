(ns llm-context.model.ids-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.model.ids :as ids]))

(deftest sha256-and-canonical-identities-retain-their-byte-contract
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (ids/sha256 "abc")))
  (is (= "symbol:be81202980f1edb0e0138a9c68e11cf3"
         (ids/symbol-id
          {:platform :clj
           :file-id "file:src/sample.clj"
           :kind :symbol.kind/function
           :qualified-name "sample/run"
           :discriminator ""}))))
