(ns quality.names
  (:require [clojure.string :as str]))

(defn normalize [value]
  (str/upper-case value))
