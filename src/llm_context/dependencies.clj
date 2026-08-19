(ns llm-context.dependencies
  "Load the packaged, authoritative dependency and artifact contract."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-resource "llm_context/dependencies.edn")

(def manifest
  (delay
    (edn/read-string (slurp (io/resource default-resource)))))

(defn all
  "Return the complete dependency manifest."
  []
  @manifest)

(defn value
  "Return one value from the dependency manifest by its keyword path."
  [path]
  (get-in (all) path))
