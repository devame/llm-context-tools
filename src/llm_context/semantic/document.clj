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

(def current-document-version 3)

(def ^:private legacy-excluded-symbol-kinds
  #{:symbol.kind/module :symbol.kind/namespace})

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
  "Extract a canonical source range. Format-3 byte offsets are authoritative;
  line/column conversion remains only for legacy records without byte ranges."
  [source {:source/keys [start-line start-column end-line end-column]
           :as range}]
  (let [bytes (utf8-bytes source)
        start-byte (:source/start-byte range)
        end-byte (:source/end-byte range)]
    (if (and (some? start-byte) (some? end-byte))
      (do
        (when-not (and (nat-int? start-byte) (nat-int? end-byte)
                       (<= start-byte end-byte (alength bytes)))
          (throw (ex-info "Semantic source range exceeds file byte length"
                          {:range range :start-byte start-byte
                           :end-byte end-byte :file-bytes (alength bytes)})))
        (String. bytes start-byte (- end-byte start-byte)
                 StandardCharsets/UTF_8))
      (do
        (when-not (every? pos-int?
                          [start-line start-column end-line end-column])
          (throw (ex-info "Semantic source range is incomplete" {:range range})))
        (let [starts (line-start-offsets bytes)
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
            (String. bytes start (- end start) StandardCharsets/UTF_8)))))))

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
  (when-not (= current-document-version (:document-version lateon))
    (throw
     (ex-info "Configured semantic document version is incompatible with this runtime"
              {:configured-version (:document-version lateon)
               :required-version current-document-version})))
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

(defn- graph-format [db]
  (d/q '[:find ?format .
         :where
         [_ :llm-context/graph-format ?format]]
       db))

(defn indexable-symbol?
  "True only for symbols admitted by the canonical indexability contract.

  Graph format 3 makes :symbol/indexable? authoritative. The kind fallback is
  retained only for legacy graphs and focused fixtures without graph metadata."
  [format symbol]
  (if (contains? symbol :symbol/indexable?)
    (true? (:symbol/indexable? symbol))
    (and (or (nil? format) (< (long format) 3))
         (not (contains? legacy-excluded-symbol-kinds
                         (:symbol/kind symbol))))))

(defn canonical-documents
  "Deterministically deduplicate exact documents and reject conflicting
  documents that claim the same canonical symbol identity."
  [documents]
  (->> documents
       (sort-by (juxt :symbol-id :file-id :document-hash))
       (reduce
        (fn [by-symbol candidate]
          (let [symbol-id (:symbol-id candidate)
                existing (get by-symbol symbol-id)]
            (cond
              (nil? existing)
              (assoc by-symbol symbol-id candidate)

              (= existing candidate)
              by-symbol

              :else
              (throw
               (ex-info
                "Conflicting semantic documents share a canonical symbol ID"
                {:type :semantic/document-conflict
                 :symbol-id symbol-id
                 :documents [existing candidate]})))))
        (sorted-map))
       vals
       vec))

(defn- entity-by [db attribute value]
  (when-let [eid (d/q '[:find ?entity .
                        :in $ ?attribute ?value
                        :where [?entity ?attribute ?value]]
                      db attribute value)]
    (d/pull db '[*] eid)))

(defn- symbols-for-file [db file-eid]
  (let [format (graph-format db)
        eids (if (and format (>= (long format) 3))
               (d/q '[:find [?symbol ...]
                      :in $ ?file
                      :where
                      [?symbol :symbol/file ?file]
                      [?symbol :symbol/indexable? true]]
                    db file-eid)
               (d/q '[:find [?symbol ...]
                      :in $ ?file
                      :where [?symbol :symbol/file ?file]]
                    db file-eid))]
    (->> eids
         (d/pull-many db '[*])
         (filter #(indexable-symbol? format %))
         (sort-by (juxt :source/start-line :source/start-column :symbol/id))
         vec)))

(defn indexable-symbol-ids
  "Return the exact desired semantic symbol identity set for this graph."
  [db]
  (let [format (graph-format db)]
    (if (and format (>= (long format) 3))
      (set (d/q '[:find [?id ...]
                  :where
                  [?symbol :symbol/id ?id]
                  [?symbol :symbol/indexable? true]]
                db))
      (let [eids (d/q '[:find [?symbol ...]
                        :where [?symbol :symbol/id _]]
                      db)]
        (->> (if (seq eids) (d/pull-many db '[*] eids) [])
             (filter #(indexable-symbol? format %))
             (map :symbol/id)
             set)))))

(defn graph-revision
  "Return a deterministic revision of graph inputs that affect semantic
  documents. File semantic hashes cover analyzer-derived facts while content
  hashes cover the exact source ranges embedded in documents."
  [db]
  (let [semantic-hashes
        (into {}
              (d/q '[:find ?id ?semantic
                     :where
                     [?file :file/id ?id]
                     [?file :file/semantic-hash ?semantic]]
                   db))
        rows
        (map (fn [[id content]]
               [id content (get semantic-hashes id "")])
             (d/q '[:find ?id ?content
                    :where
                    [?file :file/id ?id]
                    [?file :file/content-hash ?content]]
                  db))]
    (ids/content-hash (pr-str (sort rows)))))

(defn- relationships-for-symbols [db symbol-ids]
  (if-not (seq symbol-ids)
    {}
    (->> (d/q '[:find ?symbol-id ?kind ?target
                :in $ [?symbol-id ...]
                :where
                [?symbol :symbol/id ?symbol-id]
                [?edge :edge/from ?symbol]
                [?edge :edge/kind ?kind]
                [?edge :edge/target-text ?target]]
              db (vec symbol-ids))
         (reduce (fn [result [symbol-id kind target]]
                   (update result symbol-id (fnil conj [])
                           {:kind kind :target target}))
                 {})
         (into {}
               (map (fn [[symbol-id relationships]]
                      [symbol-id
                       (vec (sort-by (juxt :kind :target) relationships))]))))))

(defn- project-file ^Path [project relative]
  (let [root ^Path (:root project)
        path (.normalize (.resolve root relative))]
    (when-not (.startsWith path root)
      (throw (ex-info "Semantic graph file escapes the project root"
                      {:file relative :project (:root-str project)})))
    path))

(defn- validated-source [project file-id file]
  (let [path (project-file project (:file/path file))]
    (if-not (Files/isRegularFile path (make-array LinkOption 0))
      {:status :source-changed
       :file-id file-id
       :expected-hash (:file/content-hash file)
       :actual-hash nil
       :diagnostics [{:level :warning :kind :semantic-source-missing
                      :file (:file/path file)}]}
      (let [source-text (:content (source/read-utf8 path))
            actual-hash (ids/content-hash source-text)]
        (if (= actual-hash (:file/content-hash file))
          {:status :ready :file-id file-id :file-hash actual-hash
           :source-text source-text :diagnostics []}
          {:status :source-changed
           :file-id file-id
           :expected-hash (:file/content-hash file)
           :actual-hash actual-hash
           :diagnostics [{:level :info :kind :semantic-source-changed
                          :file (:file/path file)}]})))))

(defn- build-selected [db lateon file source-state symbols]
  (let [relationships
        (relationships-for-symbols db (mapv :symbol/id symbols))
        results
        (mapv
         (fn [symbol]
           (try
              {:document
              (build lateon symbol file (:source-text source-state)
                     (get relationships (:symbol/id symbol) []))}
             (catch clojure.lang.ExceptionInfo error
               {:diagnostic
                {:level :warning
                 :kind :semantic-range-invalid
                 :file (:file/path file)
                 :symbol-id (:symbol/id symbol)
                 :message (.getMessage error)}})))
         symbols)]
    (-> source-state
        (dissoc :source-text)
        (assoc :documents (canonical-documents
                           (mapv :document (filter :document results)))
               :diagnostics (mapv :diagnostic
                                  (filter :diagnostic results))))))

(defn build-file
  "Build all indexable symbol documents for a committed file."
  [graph project lateon file-id]
  (let [db (store/database graph)
        file (entity-by db :file/id file-id)]
    (if-not file
      {:status :deleted :file-id file-id :documents [] :diagnostics []}
      (let [source-state (validated-source project file-id file)]
        (if-not (= :ready (:status source-state))
          (assoc source-state :documents [])
          (build-selected db lateon file source-state
                          (symbols-for-file db (:db/id file))))))))

(defn build-symbol
  "Build one exact symbol document without materializing its sibling symbols."
  [graph project lateon file-id symbol-id]
  (let [db (store/database graph)
        file (entity-by db :file/id file-id)
        symbol (entity-by db :symbol/id symbol-id)
        symbol-file (some-> symbol :symbol/file)
        symbol-file-eid (if (map? symbol-file)
                          (:db/id symbol-file)
                          symbol-file)]
    (cond
      (nil? file)
      {:status :deleted :file-id file-id :documents [] :diagnostics []}

      (or (nil? symbol) (not= (:db/id file) symbol-file-eid))
      {:status :symbol-missing :file-id file-id :symbol-id symbol-id
       :documents [] :diagnostics []}

      (not (indexable-symbol? (graph-format db) symbol))
      {:status :not-indexable :file-id file-id :symbol-id symbol-id
       :documents [] :diagnostics []}

      :else
      (let [source-state (validated-source project file-id file)]
        (if-not (= :ready (:status source-state))
          (assoc source-state :documents [])
          (build-selected db lateon file source-state [symbol]))))))
