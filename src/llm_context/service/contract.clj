(ns llm-context.service.contract
  "Versioned resident-service descriptor and RPC contract."
  (:require [llm-context.version :as version]))

(def protocol-version 1)

(defn runtime-identity []
  {:application-version version/value
   :protocol-version protocol-version})

(defn compatibility [descriptor]
  (cond
    (nil? descriptor) :absent
    (not= protocol-version (:protocol-version descriptor)) :protocol-mismatch
    (not= version/value (:application-version descriptor)) :version-mismatch
    :else :compatible))
