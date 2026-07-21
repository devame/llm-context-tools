(ns llm-context.project
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files LinkOption Path Paths]))

(defn- absolute-path ^Path [path]
  (.normalize (.toAbsolutePath (Paths/get (str path) (make-array String 0)))))

(defn context
  "Resolve a project root once at the CLI boundary. All internal paths are
  derived from this context, which prevents commands from disagreeing about
  the current working directory."
  [root]
  (let [path (absolute-path (or root "."))]
    (when-not (Files/exists path (make-array LinkOption 0))
      (throw (ex-info (str "Project root does not exist: " path)
                      {:exit-code 2 :path (str path)})))
    (when-not (Files/isDirectory path (make-array LinkOption 0))
      (throw (ex-info (str "Project root is not a directory: " path)
                      {:exit-code 2 :path (str path)})))
    (let [canonical (.toRealPath path (make-array LinkOption 0))
          state-dir (.resolve canonical ".llm-context")]
      {:root canonical
       :root-str (str canonical)
       :state-dir state-dir
       :db-dir (.resolve state-dir "db")
       :config-file (.resolve canonical "llm-context.edn")})))

(defn relative-path
  "Return a stable, slash-separated path relative to the project root."
  [{:keys [^Path root]} path]
  (-> (.relativize root (absolute-path path))
      str
      (.replace (char 92) (char 47))))
