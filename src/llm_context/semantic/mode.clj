(ns llm-context.semantic.mode
  "Supported retrieval ablations and their command-line representation."
  (:require [clojure.string :as str]))

(def default :hybrid)

(def modes #{:fts-only :lateon-only :hybrid})

(def ^:private names
  {"fts" :fts-only
   "fts-only" :fts-only
   "lateon" :lateon-only
   "lateon-only" :lateon-only
   "hybrid" :hybrid})

(defn normalize
  "Normalize a retrieval mode or throw a user-facing option error."
  [value]
  (let [mode (cond
               (nil? value) default
               (keyword? value) value
               (string? value) (get names (str/lower-case value))
               :else nil)]
    (if (contains? modes mode)
      mode
      (throw
       (ex-info
        (str "Retrieval mode must be one of fts-only, lateon-only, or hybrid"
             (when value (str "; got " value)))
        {:exit-code 2 :mode value :allowed modes})))))

(defn name-of [mode]
  (name (normalize mode)))
