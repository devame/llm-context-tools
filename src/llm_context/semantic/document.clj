(ns llm-context.semantic.document
  "Deterministic, versioned LateOn documents derived from committed graph
  symbols and their exact source ranges."
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [llm-context.model.ids :as ids]
            [llm-context.source :as source]
            [llm-context.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path]))

(def ^:private relationship-labels
  {:edge.kind/calls "Calls"
   :edge.kind/imports "Imports"
   :edge.kind/references "References"
   :edge.kind/extends "Extends"
   :edge.kind/implements "Implements"})

(defn- utf8-bytes [value]
  (.getBytes ^String value StandardCharsets/UTF_8))

(defn- utf8-size [value]
  (alength (utf8-bytes value)))

(defn- line-start-offsets [^bytes bytes]
  (persistent!
   (loop [index 0
          starts (transient [0])]
     (if (< index (alength bytes))
       (recur (inc index)
              (if (= 10 (bit-and 0xff (aget bytes index)))
                (conj! starts (inc index))
                starts))
       starts))))

(defn extract-range
  "Extract a Tree-sitter range from source. Lines and columns are one-based;
  columns and the exclusive end point are UTF-8 byte offsets."
  [source {:source/keys [start-line start-column end-line end-column]
           :as range}]
  (when-not (every? pos-int? [start-line start-column end-line end-column])
    (throw (ex-info "Semantic source range is incomplete" {:range range})))
  (let [bytes (utf8-bytes source)
        starts (line-start-offsets bytes)
        line-count (count starts)]
    (when-not (and (<= start-line line-count) (<= end-line line-count))
      (throw (ex-info "Semantic source range exceeds file line count"
                      {:range range :line-count line-count})))
    (let [start (+ (nth starts (dec start-line)) (dec start-column))
          end (+ (nth starts (dec end-line)) (dec end-column))]
      (when-not (<= 0 start end (alength bytes))
        (throw (ex-info "Semantic source range exceeds file byte length"
                        {:range range :start-byte start :end-byte end
                         :file-bytes (alength bytes)})))
      (String. bytes start (- end start) StandardCharsets/UTF_8))))

(defn- label-value [label value]
  (when (and value (not (str/blank? (str value))))
    (str label ": " value)))

(defn- language-name [language]
  (some-> language name (str/replace #"^language/" "")))

(defn- relationships-by-kind [relationships]
  (->> relationships
       (group-by :kind)
       (keep (fn [[kind values]]
               (when-let [label (relationship-labels kind)]
                 (let [targets (->> values (map :target) (remove str/blank?)
                                    distinct sort)]
                   (when (seq targets)
                     (str label ": " (str/join ", " targets)))))))
       sort))

(defn- document-header [symbol file relationships]
  (->> (concat
        [(label-value "Language" (language-name (:file/language file)))
         (label-value "Kind" (some-> (:symbol/kind symbol) name
                                     (str/replace #"^symbol.kind/" "")))
         (label-value "Name" (:symbol/name symbol))
         (label-value "Qualified name" (:symbol/qualified-name symbol))
         (label-value "File" (:file/path file))
         (label-value "Signature" (:symbol/signature symbol))
         (label-value "Documentation" (:symbol/doc symbol))]
        (relationships-by-kind relationships))
       (remove nil?)
       (str/join "\n")))

(defn- split-long-piece [piece limit]
  (loop [remaining piece
         result []]
    (if (<= (utf8-size remaining) limit)
      (cond-> result (seq remaining) (conj remaining))
      (let [end
            (loop [offset 0
                   last-good 0]
              (if (< offset (count remaining))
                (let [code-point (.codePointAt ^String remaining offset)
                      next-offset (+ offset (Character/charCount code-point))
                      candidate (subs remaining 0 next-offset)]
                  (if (<= (utf8-size candidate) limit)
                    (recur next-offset next-offset)
                    last-good))
                last-good))
            ;; A positive byte limit always accommodates at least one UTF-8
            ;; code point for the configured production limits. Retain a
            ;; defensive fallback so malformed custom settings cannot loop.
            end (if (pos? end) end (Character/charCount
                                    (.codePointAt ^String remaining 0)))]
        (recur (subs remaining end)
               (conj result (subs remaining 0 end)))))))

(defn- source-pieces [source limit]
  (->> (re-seq #"[^\n]*\n|[^\n]+$" source)
       (mapcat #(split-long-piece % limit))
       vec))

(defn- chunk-pieces [pieces limit overlap]
  (loop [start 0
         chunks []]
    (if (>= start (count pieces))
      chunks
      (let [end (loop [index start
                       size 0]
                  (if (< index (count pieces))
                    (let [piece-size (utf8-size (nth pieces index))]
                      (if (or (= index start) (<= (+ size piece-size) limit))
                        (recur (inc index) (+ size piece-size))
                        index))
                    index))
            chunk (apply str (subvec pieces start end))
            next-start (if (= end (count pieces))
                         end
                         (max (inc start) (- end overlap)))]
        (recur next-start (conj chunks chunk))))))

(defn- render-chunks [header source {:keys [max-document-bytes
                                             chunk-overlap-lines]}]
  (let [prefix (str header "\n\nSource:\n")
        ;; Reserve enough space for a stable chunk annotation even when the
        ;; total chunk count has several digits.
        source-limit (- max-document-bytes (utf8-size prefix) 64)]
    (when-not (pos? source-limit)
      (throw (ex-info "Semantic document metadata exceeds configured byte limit"
                      {:header-bytes (utf8-size prefix)
                       :max-document-bytes max-document-bytes})))
    (let [pieces (source-pieces source source-limit)
          bodies (if (seq pieces)
                   (chunk-pieces pieces source-limit chunk-overlap-lines)
                   [""])
          total (count bodies)]
      (mapv (fn [index body]
              (let [annotation (when (> total 1)
                                 (str "Chunk: " (inc index) "/" total "\n"))]
                (str header "\n"
                     annotation
                     "\nSource:\n"
                     body)))
            (range total)
            bodies))))

(defn document-hash [lateon chunk-texts]
  (ids/content-hash
   (str (:document-version lateon) "\u0000"
        (:model lateon) "\u0000"
        (:model-revision lateon) "\u0000"
        (str/join "\u0000" chunk-texts))))

(defn build
  "Build one versioned semantic document from canonical graph data."
  [lateon symbol file source relationships]
  (let [body (extract-range source symbol)
        header (document-header symbol file relationships)
        chunks (render-chunks header body lateon)
        hash (document-hash lateon chunks)
        total (count chunks)]
    {:symbol-id (:symbol/id symbol)
     :file-id (:file/id file)
     :file-path (:file/path file)
     :file-hash (:file/content-hash file)
     :document-hash hash
     :model (:model lateon)
     :model-revision (:model-revision lateon)
     :document-version (:document-version lateon)
     :chunks
     (mapv (fn [index text]
             {:id (format "%s#chunk-%03d" (:symbol/id symbol) index)
              :symbol-id (:symbol/id symbol)
              :file-id (:file/id file)
              :document-hash hash
              :model-revision (:model-revision lateon)
              :document-version (:document-version lateon)
              :chunk-index index
              :chunk-count total
              :text text})
           (range total)
           chunks)}))

(defn- entity-by [db attribute value]
  (when-let [eid (d/q '[:find ?entity .
                        :in $ ?attribute ?value
                        :where [?entity ?attribute ?value]]
                      db attribute value)]
    (d/pull db '[*] eid)))

(defn- symbols-for-file [db file-eid]
  (let [eids (d/q '[:find [?symbol ...]
                    :in $ ?file
                    :where [?symbol :symbol/file ?file]]
                  db file-eid)]
    (->> eids
         (map #(d/pull db '[*] %))
         ;; Synthetic module symbols span whole files and duplicate every
         ;; contained code unit. They remain in the graph but not in LateOn.
         (remove #(= :symbol.kind/module (:symbol/kind %)))
         (sort-by (juxt :source/start-line :source/start-column :symbol/id))
         vec)))

(defn- relationships-for [db symbol-id]
  (->> (d/q '[:find ?kind ?target
              :in $ ?symbol-id
              :where
              [?symbol :symbol/id ?symbol-id]
              [?edge :edge/from ?symbol]
              [?edge :edge/kind ?kind]
              [?edge :edge/target-text ?target]]
            db symbol-id)
       (map (fn [[kind target]] {:kind kind :target target}))
       (sort-by (juxt :kind :target))
       vec))

(defn- project-file ^Path [project relative]
  (let [root ^Path (:root project)
        path (.normalize (.resolve root relative))]
    (when-not (.startsWith path root)
      (throw (ex-info "Semantic graph file escapes the project root"
                      {:file relative :project (:root-str project)})))
    path))

(defn build-file
  "Build all indexable symbol documents for a committed file.

  Returns :source-changed instead of mixing current source with stale graph
  ranges. Missing ranges are reported as diagnostics without preventing other
  symbols in the file from being indexed."
  [graph project lateon file-id]
  (let [db (store/database graph)
        file (entity-by db :file/id file-id)]
    (if-not file
      {:status :deleted :file-id file-id :documents [] :diagnostics []}
      (let [path (project-file project (:file/path file))]
        (if-not (Files/isRegularFile path (make-array LinkOption 0))
          {:status :source-changed
           :file-id file-id
           :expected-hash (:file/content-hash file)
           :actual-hash nil
           :documents []
           :diagnostics [{:level :warning :kind :semantic-source-missing
                          :file (:file/path file)}]}
          (let [source-text (:content (source/read-utf8 path))
                actual-hash (ids/content-hash source-text)]
            (if-not (= actual-hash (:file/content-hash file))
              {:status :source-changed
               :file-id file-id
               :expected-hash (:file/content-hash file)
               :actual-hash actual-hash
               :documents []
               :diagnostics [{:level :info :kind :semantic-source-changed
                              :file (:file/path file)}]}
              (let [symbols (symbols-for-file db (:db/id file))
                    results
                    (mapv
                     (fn [symbol]
                       (try
                         {:document
                          (build lateon symbol file source-text
                                 (relationships-for db (:symbol/id symbol)))}
                         (catch clojure.lang.ExceptionInfo error
                           {:diagnostic
                            {:level :warning
                             :kind :semantic-range-invalid
                             :file (:file/path file)
                             :symbol-id (:symbol/id symbol)
                             :message (.getMessage error)}})))
                     symbols)]
                {:status :ready
                 :file-id file-id
                 :file-hash actual-hash
                 :documents (mapv :document (filter :document results))
                 :diagnostics (mapv :diagnostic
                                    (filter :diagnostic results))}))))))))
