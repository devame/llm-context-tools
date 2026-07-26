(ns quality.core
  (:require [quality.names :as names]))

(defn greet [name]
  (str "Hello, " (names/normalize name)))

(defn loud-greeting [name]
  (names/normalize (greet name)))

(defn run []
  (loud-greeting "graph"))
