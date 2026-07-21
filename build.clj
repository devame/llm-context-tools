(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'devame/llm-context)
(def version "0.4.0-SNAPSHOT")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def uber-file (format "target/llm-context-%s-standalone.jar" version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'llm-context.main})
  {:jar uber-file})
