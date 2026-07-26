(ns llm-context.analysis.ir
  "Small constructors for the graph-format-3 analyzer interchange contract.
  Adapters may build ordinary maps directly; these helpers make the mandatory
  scope, role, provenance, and range decisions visible at call sites."
  (:refer-clojure :exclude [symbol])
  (:require [llm-context.analysis.canonical :as canonical]
            [llm-context.model.schema :as schema]))

(defn source-range
  "Build a canonical source range. Byte offsets are zero-based UTF-8 offsets;
  the end byte is exclusive. Lines and columns remain one-based."
  [start-line start-column end-line end-column start-byte end-byte]
  {:source/start-line start-line
   :source/start-column start-column
   :source/end-line end-line
   :source/end-column end-column
   :source/start-byte start-byte
   :source/end-byte end-byte})

(defn provenance
  [analyzer record-kind evidence]
  {:entity/analyzer analyzer
   :entity/record-kind record-kind
   :entity/evidence evidence})

(defn symbol
  "Construct and validate one persistent symbol definition. Lexical locals
  intentionally have no representable scope."
  [attributes]
  (-> attributes
      (assoc :entity/type :entity.type/symbol)
      canonical/normalize-entity
      schema/validate-entity!))

(defn edge [attributes]
  (-> attributes
      (assoc :entity/type :entity.type/edge)
      canonical/normalize-entity
      schema/validate-entity!))

(defn reference [attributes]
  (-> attributes
      (assoc :entity/type :entity.type/reference)
      canonical/normalize-entity
      schema/validate-entity!))

(defn effect [attributes]
  (-> attributes
      (assoc :entity/type :entity.type/effect)
      canonical/normalize-entity
      schema/validate-entity!))

(defn normalize-output
  "Normalize one language-adapter output at the analyzer interchange boundary.
  Adapters may construct ordinary observation maps, but project coordination
  never consumes those maps directly."
  [output]
  (-> output
      (update :file #(some-> % canonical/normalize-entity))
      (update :entities
              (fn [entities]
                (mapv canonical/normalize-entity (or entities []))))))
