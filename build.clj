(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'devame/llm-context)
(def version "0.7.1")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def uber-file (format "target/llm-context-%s-standalone.jar" version))
(def dist-file "dist/llm-context.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn- build-uber [output]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/javac {:src-dirs ["src-java"]
            :class-dir class-dir
            :basis @basis
            :javac-opts ["--release" "23"]})
  (b/uber {:class-dir class-dir
           :uber-file output
           :basis @basis
           :main 'llm_context.Launcher})
  {:jar output})

(defn uber [_]
  (build-uber uber-file))

(defn dist [_]
  (b/delete {:path "dist"})
  (build-uber dist-file))
