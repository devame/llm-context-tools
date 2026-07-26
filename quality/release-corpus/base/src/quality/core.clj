(ns quality.core
  (:require [quality.names :as names]))

(defn greet [name]
  (str "Hello, " (names/normalize name)))

(defn run []
  (greet "graph"))
