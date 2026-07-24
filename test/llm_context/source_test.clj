(ns llm-context.source-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.source :as source]))

(deftest malformed-utf8-is-replaced-at-a-stable-byte-offset
  (let [bytes (byte-array [(byte 0x61) (unchecked-byte 0xc4) (byte 0x62)])
        decoded (source/decode-utf8 bytes)]
    (is (:malformed? decoded))
    (is (= 1 (:malformed-offset decoded)))
    (is (= "a\uFFFDb" (:content decoded)))))

(deftest valid-utf8-is-preserved
  (let [bytes (.getBytes "café" java.nio.charset.StandardCharsets/UTF_8)]
    (is (= {:content "café" :malformed? false}
           (source/decode-utf8 bytes)))))
