(ns llm-context.analysis.clj-kondo
  "Embedded, project-wide clj-kondo analysis for Clojure source.

  The provider never asks a project build tool for a classpath and never
  evaluates project code. Existing .clj-kondo configuration is loaded by
  clj-kondo, while generated cache data is isolated below .llm-context."
  (:require [clj-kondo.core :as kondo]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-context.model.ids :as ids]
            [llm-context.project :as project])
  (:import [java.nio.file Files LinkOption Path]))

(def analyzer-version "2026.07.24")
(def analyzer-name :clj-kondo)

(def clojure-languages
  #{:language/clojure :language/clojurescript :language/clojure-common})

(def analysis-options
  {:keywords true
   :locals true
   :arglists true
   :protocol-impls true
   :java-class-usages true
   :java-member-usages true
   :instance-invocations true})

;; This provider supplies graph facts, not a second lint UI. Because dependency
;; build commands and project macros are deliberately not executed, ordinary
;; lint output contains expected false positives such as macro-introduced
;; bindings. Only findings that mean source text could not be read reliably
;; affect the analysis result.
(def source-integrity-finding-types
  #{:syntax :reader-error :invalid-token :unclosed-delimiter})

(defn- clojure-file? [file]
  (contains? clojure-languages (:language file)))

(defn- config-fingerprint [{:keys [^Path root]}]
  (let [path (.resolve root ".clj-kondo/config.edn")
        content (if (Files/isRegularFile path (make-array LinkOption 0))
                  (slurp (.toFile path))
                  "")]
    (ids/content-hash
     (str analyzer-version "\u001f" (pr-str analysis-options)
          "\u001f" content))))

(defn- platforms-for [file-language lang]
  (case lang
    :clj [:clj]
    :cljs [:cljs]
    (case file-language
      :language/clojure [:clj]
      :language/clojurescript [:cljs]
      :language/clojure-common [:clj :cljs])))

(defn- relative-filename [project filename]
  (let [path (.normalize (.toAbsolutePath (.toPath (io/file filename))))]
    (try
      (project/relative-path project path)
      (catch IllegalArgumentException _
        (str/replace (str filename) "\\" "/")))))

(defn- normalize-record [project language-by-path record]
  (let [relative (relative-filename project (:filename record))
        file-language (get language-by-path relative)]
    (-> record
        (assoc :filename relative
               :platforms (platforms-for file-language (:lang record))
               :row (or (:row record) (:name-row record))
               :col (or (:col record) (:name-col record))
               :end-row (or (:end-row record) (:name-end-row record))
               :end-col (or (:end-col record) (:name-end-col record)))
        (dissoc :uri))))

(defn- finding->diagnostic [project finding]
  {:level (or (:level finding) :warning)
   :kind :clj-kondo
   :file (relative-filename project (:filename finding))
   :row (:row finding)
   :column (:col finding)
   :message (:message finding)
   :type (:type finding)})

(defn analyze!
  "Run one embedded clj-kondo invocation over the discovered Clojure family.
  Return normalized analysis output grouped only by clj-kondo record kind."
  [project files]
  (let [files (vec (filter clojure-file? files))
        language-by-path (into {} (map (juxt :relative-path :language) files))
        ^Path cache-dir (.resolve ^Path (:state-dir project) "cache/clj-kondo")
        ^Path project-config-dir (.resolve ^Path (:root project) ".clj-kondo")
        ^Path config-dir
        (if (Files/isDirectory project-config-dir (make-array LinkOption 0))
          project-config-dir
          (.resolve ^Path (:state-dir project) "cache/clj-kondo-config"))]
    (Files/createDirectories
     cache-dir (make-array java.nio.file.attribute.FileAttribute 0))
    ;; clj-kondo 2026.07.24's cache synchronizer expects a non-nil config-dir
    ;; even when no project configuration exists. The isolated empty directory
    ;; preserves source-first behavior and never writes into the repository.
    (Files/createDirectories
     config-dir (make-array java.nio.file.attribute.FileAttribute 0))
    (if (empty? files)
      {:analyzer analyzer-name
       :analyzer-version analyzer-version
       :configuration-fingerprint (config-fingerprint project)
       :analysis {}
       :diagnostics []}
      (let [result
            (kondo/run!
             {:lint (mapv (comp str :path) files)
              :cache true
              :cache-dir (str cache-dir)
              :config-dir (str config-dir)
              :config {:output {:format :edn
                                :summary true
                                :progress false
                                :analysis analysis-options}}})
            normalized
            (into (sorted-map)
                  (map (fn [[kind records]]
                         [kind (->> records
                                    (map #(normalize-record
                                           project language-by-path %))
                                    (sort-by (juxt :filename :row :col
                                                   #(str (:name %))))
                                    vec)]))
                  (:analysis result))]
        {:analyzer analyzer-name
         :analyzer-version analyzer-version
         :configuration-fingerprint (config-fingerprint project)
         :summary (:summary result)
         :analysis normalized
         :diagnostics (->> (:findings result)
                           (filter #(contains? source-integrity-finding-types
                                               (:type %)))
                           (mapv #(finding->diagnostic project %)))}))))
