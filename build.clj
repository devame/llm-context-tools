(ns build
  (:require [clojure.tools.build.api :as b])
  (:import [java.nio.file Files Paths]
           [java.security MessageDigest]))

(def lib 'devame/llm-context)
(def version "0.12.11")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def uber-file (format "target/llm-context-%s-standalone.jar" version))
(def dist-file "dist/llm-context.jar")

(defn- sha256 [path]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (Files/readAllBytes (Paths/get path (make-array String 0))))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

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
  (let [result (build-uber dist-file)
        guide "dist/USER-GUIDE.md"]
    (b/copy-file {:src "docs/user-guide.md" :target guide})
    (spit (str guide ".sha256")
          (str (sha256 guide) "  USER-GUIDE.md\n"))
    result))
