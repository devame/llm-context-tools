(ns llm-context.analysis.effects-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.analysis.effects :as effects]
            [llm-context.model.schema :as schema]))

(defn call [target]
  {:entity/type :entity.type/edge
   :edge/id (str "edge:" target)
   :edge/kind :edge.kind/calls
   :edge/from "symbol:caller"
   :edge/target-text target
   :edge/resolution :resolution/unresolved
   :edge/confidence 0.0
   :source/start-line 4 :source/start-column 3
   :source/end-line 4 :source/end-column 20
   :source/snippet (str target "(path)")})

(deftest effect-rules-require-high-signal-callees
  (let [found (effects/analyze :language/javascript
                               [(call "fs.readFile")
                                (call "readFile")
                                (call "console.log")])]
    (is (= #{:effect.kind/file-read :effect.kind/logging}
           (set (map :effect/kind found))))
    (is (every? #(>= (:effect/confidence %) 0.9) found))
    (doseq [effect found]
      (is (= effect (schema/validate-entity! effect))))))

(deftest clojure-core-effects-are-explicit
  (let [found (effects/analyze :language/clojure
                               [(call "slurp") (call "spit") (call "println")])]
    (is (= [:effect.kind/file-read :effect.kind/file-write :effect.kind/logging]
           (mapv :effect/kind found)))))

(deftest janet-core-effects-are-explicit
  (let [found (effects/analyze :language/janet
                               [(call "slurp") (call "spit") (call "print")
                                (call "os/spawn") (call "net/connect")])]
    (is (= [:effect.kind/file-read :effect.kind/file-write
            :effect.kind/logging :effect.kind/process :effect.kind/network]
           (mapv :effect/kind found)))))
