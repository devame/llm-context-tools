(ns llm-context.analysis.clj-kondo
  "Embedded, project-wide clj-kondo analysis for Clojure source.

  The provider never asks a project build tool for a classpath and never
  evaluates project code. Existing .clj-kondo configuration is loaded by
  clj-kondo, while generated cache data is isolated below .llm-context."
  (:require [clj-kondo.core :as kondo]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-context.dependencies :as dependencies]
            [llm-context.model.ids :as ids]
            [llm-context.project :as project])
  (:import [java.io PrintWriter Writer]
           [java.nio.file Files LinkOption Path]))

(def analyzer-version
  (dependencies/value [:jvm :deps 'clj-kondo/clj-kondo :mvn/version]))
(def analyzer-name :clj-kondo)

(def clojure-languages
  #{:language/clojure :language/clojurescript :language/clojure-common})

(def analysis-options
  {:locals true
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

(def ^:private hook-not-found-pattern
  #"(?i)^WARNING:\s+file\s+(.+?)\s+not found while loading hook\s*$")

(def ^:private severity-pattern
  #"(?i)^(WARNING|ERROR):\s*(.*)$")

(defn- runtime-event [line]
  (let [line (str/trim line)]
    (when (seq line)
      (if-let [[_ path] (re-matches hook-not-found-pattern line)]
        {:key [:clj-kondo-hook-not-found]
         :level :warning
         :kind :clj-kondo-hook-not-found
         :path path}
        (let [[_ severity message] (re-matches severity-pattern line)
              level (case (some-> severity str/upper-case)
                      "ERROR" :error
                      "WARNING" :warning
                      :info)
              message (or message line)]
          {:key [:clj-kondo-runtime-message level message]
           :level level
           :kind :clj-kondo-runtime-message
           :message message})))))

(defn- record-runtime-event [state event]
  (if-not event
    state
    (let [key (:key event)]
      (if (contains? (:entries state) key)
        (-> state
            (update-in [:entries key :count] inc)
            (cond-> (:path event)
              (update-in [:entries key :paths] conj (:path event))))
        (-> state
            (update :order conj key)
            (assoc-in [:entries key]
                      (cond-> (assoc (dissoc event :key :path) :count 1)
                        (:path event)
                        (assoc :paths (sorted-set (:path event))))))))))

(defn- captured-diagnostics [{:keys [order entries]}]
  (mapv
   (fn [key]
     (let [{:keys [kind paths] :as event} (get entries key)]
       (cond-> event
         (= :clj-kondo-hook-not-found kind)
         (assoc :message
                (str "hook files not found: " (str/join ", " paths)))
         paths (assoc :paths (vec paths)))))
   order))

(defn- runtime-message-capture []
  (let [state (atom {:order [] :entries {}})
        buffer (StringBuilder.)
        record-line!
        (fn []
          (let [line (str buffer)]
            (.setLength buffer 0)
            (swap! state record-runtime-event (runtime-event line))))
        append-character!
        (fn [character]
          (let [character (char character)]
            (if (= \newline character)
              (record-line!)
              (.append buffer character))))
        append-range!
        (fn [characters offset length]
          (dotimes [index length]
            (let [position (+ offset index)
                  character (if (string? characters)
                              (.charAt ^String characters position)
                              (aget ^chars characters position))]
              (append-character! character))))
        sink
        (proxy [Writer] []
          (write
            ([value]
             (locking buffer
               (cond
                 (number? value) (append-character! value)
                 (string? value) (append-range! value 0 (count value))
                 :else (append-range! value 0 (alength ^chars value)))))
            ([characters offset length]
             (locking buffer
               (append-range! characters offset length))))
          (flush [])
          (close []))
        writer (PrintWriter. sink true)]
    {:writer writer
     :finish!
     (fn []
       (.flush writer)
       (locking buffer
         (when (pos? (.length buffer))
           (record-line!)))
       (captured-diagnostics @state))}))

(defn- run-kondo! [options]
  (let [{:keys [writer finish!]} (runtime-message-capture)
        result (binding [*err* writer]
                 (kondo/run! options))]
    {:result result
     :diagnostics (finish!)}))

(defn- clojure-file? [file]
  (contains? clojure-languages (:language file)))

(defn config-fingerprint [{:keys [^Path root]}]
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
    ;; The pinned clj-kondo cache synchronizer expects a non-nil config-dir.
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
      (let [{:keys [result diagnostics]}
            (run-kondo!
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
         :diagnostics
         (into diagnostics
               (comp
                (filter #(contains? source-integrity-finding-types (:type %)))
                (map #(finding->diagnostic project %)))
               (:findings result))}))))
