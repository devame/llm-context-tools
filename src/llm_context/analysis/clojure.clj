(ns llm-context.analysis.clojure
  "Materialize exact Clojure graph facts from normalized clj-kondo output."
  (:require [clojure.string :as str]
            [llm-context.analysis.clojure-topics :as topics]
            [llm-context.analysis.effects :as effects]
            [llm-context.model.ids :as ids]))

(def definition-forms
  {'clojure.core/defn :symbol.kind/function
   'cljs.core/defn :symbol.kind/function
   'clojure.core/defn- :symbol.kind/function
   'cljs.core/defn- :symbol.kind/function
   'clojure.core/defmacro :symbol.kind/macro
   'cljs.core/defmacro :symbol.kind/macro
   'clojure.core/defprotocol :symbol.kind/protocol
   'cljs.core/defprotocol :symbol.kind/protocol
   'clojure.core/defmulti :symbol.kind/multimethod
   'cljs.core/defmulti :symbol.kind/multimethod
   'clojure.core/defrecord :symbol.kind/type
   'cljs.core/defrecord :symbol.kind/type
   'clojure.core/deftype :symbol.kind/type
   'cljs.core/deftype :symbol.kind/type})

(def suppressed-core-usages
  '#{clojure.core/def cljs.core/def
     clojure.core/declare cljs.core/declare
     clojure.core/defn cljs.core/defn
     clojure.core/defn- cljs.core/defn-
     clojure.core/defmacro cljs.core/defmacro
     clojure.core/defprotocol cljs.core/defprotocol
     clojure.core/defrecord cljs.core/defrecord
     clojure.core/deftype cljs.core/deftype
     clojure.core/let cljs.core/let
     clojure.core/when cljs.core/when
     clojure.core/if cljs.core/if
     clojure.core/when-let cljs.core/when-let
     clojure.core/if-let cljs.core/if-let
     clojure.core/and cljs.core/and
     clojure.core/or cljs.core/or
     clojure.core/-> cljs.core/->
     clojure.core/->> cljs.core/->>
     clojure.core/cond cljs.core/cond
     clojure.core/cond-> cljs.core/cond->
     clojure.core/cond->> cljs.core/cond->>
     clojure.core/case cljs.core/case
     clojure.core/do cljs.core/do
     clojure.core/comment cljs.core/comment
     clojure.core/fn cljs.core/fn
     clojure.core/loop cljs.core/loop
     clojure.core/for cljs.core/for
     clojure.core/doseq cljs.core/doseq})

(def declaration-forms
  '#{clojure.core/declare cljs.core/declare})

(defn- file-entity
  [{:keys [relative-path language content modified-at]}]
  {:entity/type :entity.type/file
   :file/id (ids/file-id relative-path)
   :file/path relative-path
   :file/language language
   :file/content-hash (ids/content-hash content)
   ;; Source ranges address the normalized UTF-8 text supplied to analyzers.
   ;; Invalid input bytes may expand to the replacement character, so the raw
   ;; filesystem size is not a valid bound for canonical offsets.
   :file/size (alength
               (.getBytes ^String content
                          java.nio.charset.StandardCharsets/UTF_8))
   :file/modified-at modified-at})

(defn- range-data [record]
  (cond-> (select-keys record
                       [:source/start-line :source/start-column
                        :source/end-line :source/end-column
                        :source/start-byte :source/end-byte])
    (:row record) (assoc :source/start-line (:row record))
    (:col record) (assoc :source/start-column (:col record))
    (:end-row record) (assoc :source/end-line (:end-row record))
    (:end-col record) (assoc :source/end-column (:end-col record))))

(defn- line-start-offsets [source]
  (loop [index 0 result [0]]
    (let [newline (.indexOf ^String source "\n" index)]
      (if (neg? newline)
        result
        (recur (inc newline) (conj result (inc newline)))))))

(defn- source-byte-offset
  [source line-starts row column]
  (when-let [line-start (and (pos-int? row)
                             (nth line-starts (dec row) nil))]
    (let [line-end (let [newline (.indexOf ^String source "\n" line-start)]
                     (if (neg? newline) (count source) newline))
          line-length (- line-end line-start)]
      ;; clj-kondo columns follow JVM string (UTF-16 code-unit) offsets, while
      ;; the canonical IR requires UTF-8 byte offsets.
      (when (<= (dec column) line-length)
        (let [character-offset (+ line-start (dec column))]
          (alength (.getBytes (.substring ^String source
                                          0 character-offset)
                              java.nio.charset.StandardCharsets/UTF_8)))))))

(defn- with-byte-range [source line-starts entity]
  (let [{:source/keys [start-line start-column end-line end-column]} entity]
    (if (every? some? [start-line start-column end-line end-column])
      (let [start-byte (source-byte-offset source line-starts
                                           start-line start-column)
            end-byte (source-byte-offset source line-starts
                                         end-line end-column)]
        (cond-> entity
          (and (some? start-byte) (some? end-byte))
          (assoc :source/start-byte start-byte
                 :source/end-byte end-byte)))
      entity)))

(defn- qualified [ns-name name]
  (str ns-name "/" name))

(defn- definition-kind
  [{:keys [defined-by defined-by->lint-as macro protocol-name name
           arglist-strs]}]
  (or (when (and protocol-name (not= protocol-name name))
        :symbol.kind/method)
      (when (and (seq arglist-strs)
                 (contains? '#{clojure.core/defrecord cljs.core/defrecord
                              clojure.core/deftype cljs.core/deftype}
                            (or defined-by->lint-as defined-by))
                 (or (str/starts-with? (str name) "->")
                     (str/starts-with? (str name) "map->")))
        :symbol.kind/function)
      (get definition-forms defined-by->lint-as)
      (get definition-forms defined-by)
      (when macro :symbol.kind/macro)
      :symbol.kind/variable))

(defn- symbol-role [kind]
  (case kind
    :symbol.kind/macro :role/macro
    :symbol.kind/protocol :role/protocol
    :symbol.kind/method :role/method
    :symbol.kind/variable :role/variable
    :symbol.kind/constant :role/variable
    :role/definition))

(defn- declaration? [{:keys [defined-by defined-by->lint-as]}]
  (boolean
   (some declaration-forms [defined-by->lint-as defined-by])))

(defn- source-order [record]
  [(or (:row record) 0)
   (or (:col record) 0)
   (or (:end-row record) 0)
   (or (:end-col record) 0)
   (pr-str (dissoc record :platforms))])

(defn- effective-definitions
  "Collapse repeated analyzer observations for one file-owned var. A concrete
  definition supersedes declarations, while declaration-only groups produce no
  persistent symbol. Repeated concrete definitions retain Clojure's
  deterministic last-definition semantics. Cross-file definitions remain
  distinct and are therefore still ambiguous at lookup time."
  [records]
  (->> records
       distinct
       (group-by (juxt :platform :filename :ns :name :protocol-name))
       (sort-by key)
       (keep
        (fn [[_ observations]]
          (let [ordered (sort-by source-order observations)
                definitions (remove declaration? ordered)
                effective (last definitions)]
            (when effective
              (assoc effective
                     ::observation-count (count ordered)
                     ::declaration-count
                     (count (filter declaration? ordered))
                     ::effective-definition? true)))))
       vec))

(defn- namespace-symbol [file platform definition]
  (let [name (str (:name definition))
        parts {:platform platform
               :file-id (:file/id file)
               :kind :symbol.kind/namespace
               :qualified-name name}]
    (merge
     {:entity/type :entity.type/symbol
      :symbol/id (ids/symbol-id parts)
      :symbol/name name
      :symbol/qualified-name name
      :symbol/kind :symbol.kind/namespace
      :symbol/file (:file/id file)
      :symbol/platform platform
      :symbol/analyzer :clj-kondo
      :symbol/scope :scope/namespace
      :symbol/role :role/namespace
      :symbol/indexable? false}
     (range-data definition))))

(defn- var-symbol [file platform definition]
  (let [kind (definition-kind definition)
        name (str (:name definition))
        protocol-name (some-> (:protocol-name definition) str)
        qname (if (and (= :symbol.kind/method kind) protocol-name)
                (qualified (:ns definition)
                           (str protocol-name "." (:name definition)))
                (qualified (:ns definition) (:name definition)))
        parts {:platform platform :file-id (:file/id file)
               :kind kind :qualified-name qname}
        arglists (when-let [values (seq (:arglist-strs definition))]
                   (str/join " " values))]
    (merge
     (cond-> {:entity/type :entity.type/symbol
              :symbol/id (ids/symbol-id parts)
              :symbol/name name
              :symbol/qualified-name qname
              :symbol/kind kind
              :symbol/file (:file/id file)
              :symbol/platform platform
              :symbol/analyzer :clj-kondo
              :symbol/scope (if (= :symbol.kind/method kind)
                              :scope/method :scope/top-level)
              :symbol/role (symbol-role kind)
              :symbol/indexable? true
              :symbol/private? (boolean (:private definition))
              :symbol/macro? (= :symbol.kind/macro kind)}
       protocol-name (assoc :symbol/protocol-name protocol-name)
       arglists (assoc :symbol/arglists arglists
                       :symbol/signature arglists)
       (seq (:doc definition)) (assoc :symbol/doc (:doc definition)))
     (range-data definition))))

(defn- exact-edge [kind from to target evidence record]
  (let [parts {:kind kind :from-id (:symbol/id from)
               :to-id (:symbol/id to) :target-text target
               :start-line (:row record) :start-column (:col record)}]
    (merge
     {:entity/type :entity.type/edge
      :edge/id (ids/edge-id parts)
      :edge/kind kind
      :edge/from (:symbol/id from)
      :edge/to (:symbol/id to)
      :edge/target-text target
      :edge/resolution :resolution/exact
      :edge/confidence 1.0
      :edge/evidence evidence}
     (range-data record))))

(defn- diagnostic-reference
  [kind owner record target classification evidence qualified-target]
  (let [parts {:platform (:symbol/platform owner)
               :symbol-id (:symbol/id owner)
               :kind kind :target-text target
               :classification classification
               :start-line (:row record) :start-column (:col record)}]
    (merge
     (cond-> {:entity/type :entity.type/reference
              :reference/id (ids/reference-id parts)
              :reference/symbol (:symbol/id owner)
              :reference/kind kind
              :reference/target-text target
              :reference/classification classification
              :reference/evidence evidence}
       qualified-target
       (assoc :reference/qualified-target qualified-target))
     (range-data record))))

(defn- containment [namespace symbol]
  (exact-edge :edge.kind/contains namespace symbol
              (:symbol/qualified-name symbol)
              :clj-kondo-definition symbol))

(defn- expand-platforms [records]
  (mapcat (fn [record]
            (map #(assoc record :platform %) (:platforms record)))
          records))

(declare contains-position? range-size owner-order)

(defn- same-record-file? [entity record]
  (= (str "file:" (:filename record)) (:symbol/file entity)))

(defn- owner-for
  [namespace-by-key definitions-by-key platform record]
  (or (when-let [from-var (:from-var record)]
        (let [candidates (get definitions-by-key
                              [platform (:from record) from-var])
              in-file (filter #(same-record-file? % record) candidates)
              containing (filter #(and
                                       (contains-position?
                                        % (:row record) (:col record)))
                                 in-file)]
          (or (first (sort-by owner-order containing))
              (when (= 1 (count in-file))
                (first in-file)))))
      (let [candidates (get namespace-by-key [platform (:from record)])]
        (or (some #(when (same-record-file? % record) %) candidates)
            (when (= 1 (count candidates)) (first candidates))))))

(defn- target-candidates [definitions-by-key platform record]
  (get definitions-by-key [platform (:to record) (:name record)]))

(defn- qualified-target [record]
  (when (and (:to record) (:name record))
    (qualified (:to record) (:name record))))

(defn- located? [record]
  (and (pos-int? (:row record))
       (pos-int? (:col record))))

(defn- var-relationship
  [namespace-by-key definitions-by-key platform record]
  (when (located? record)
   (when-let [owner (owner-for namespace-by-key definitions-by-key
                               platform record)]
    (let [qualified-target (qualified-target record)
          target (or qualified-target (str (:name record)))
          candidates (target-candidates definitions-by-key platform record)
          call? (integer? (:arity record))
          kind (if call?
                 (if (:macro record)
                   :edge.kind/macro-invokes :edge.kind/calls)
                 :edge.kind/references)]
      (cond
        (contains? suppressed-core-usages
                   (some-> qualified-target clojure.core/symbol))
        nil

        (= 1 (count candidates))
        (exact-edge kind owner (first candidates) target
                    :clj-kondo-var-usage record)

        (> (count candidates) 1)
        (diagnostic-reference kind owner record target :ambiguous
                              :clj-kondo-var-usage qualified-target)

        (:to record)
        (diagnostic-reference kind owner record target :external
                              :clj-kondo-var-usage qualified-target)

        :else
        (diagnostic-reference kind owner record target :unresolved
                              :clj-kondo-var-usage nil))))))

(defn- namespace-relationship
  [namespace-by-key platform record]
  (when (located? record)
   (when-let [owner (owner-for namespace-by-key {} platform record)]
    (let [target-name (str (:to record))]
      (let [candidates (get namespace-by-key [platform (:to record)])]
        (cond
          (= 1 (count candidates))
          (exact-edge :edge.kind/imports owner (first candidates) target-name
                      :clj-kondo-namespace-usage record)

          (> (count candidates) 1)
          (diagnostic-reference
           :edge.kind/imports owner record target-name :ambiguous
           :clj-kondo-namespace-usage target-name)

          :else
          (diagnostic-reference
           :edge.kind/imports owner record target-name :external
           :clj-kondo-namespace-usage target-name)))))))

(defn- local-call?
  [content {:keys [row name-col col]}]
  (let [column (or name-col col)]
   (when (and row column)
    (let [lines (str/split-lines content)
          line (nth lines (dec row) nil)
          before (when (and line (> column 1))
                   (subs line 0 (min (count line) (dec column))))]
      (boolean (and before (re-find #"\(\s*$" before)))))))

(defn- local-name
  "Prefer clj-kondo's normalized local name, but recover it from the precise
  name range when analysis-data omits :name. clj-kondo can emit this shape for
  ClojureScript macro-bound locals such as cljs.test/async's `done` callback."
  [content record]
  (let [reported (some-> (:name record) str)]
    (or (when-not (str/blank? reported) reported)
        (let [row (or (:name-row record) (:row record))
              end-row (or (:name-end-row record) row)
              start-column (or (:name-col record) (:col record))
              end-column (or (:name-end-col record) (:end-col record))
              line (when (and content
                              (pos-int? row)
                              (= row end-row))
                     (nth (str/split-lines content) (dec row) nil))
              start (when (pos-int? start-column) (dec start-column))
              end (when (pos-int? end-column) (dec end-column))]
          (when (and line start end
                     (<= 0 start)
                     (< start end)
                     (<= end (count line)))
            (let [recovered (subs line start end)]
              (when-not (str/blank? recovered)
                recovered)))))))

(defn- contains-position? [entity row col]
  (let [start [(:source/start-line entity) (:source/start-column entity)]
        end [(:source/end-line entity) (:source/end-column entity)]]
    (and (every? some? (concat start end [row col]))
         (not (neg? (compare [row col] start)))
         (neg? (compare [row col] end)))))

(defn- range-size [entity]
  (let [line-span (- (:source/end-line entity)
                     (:source/start-line entity))
        column-span (if (zero? line-span)
                      (- (:source/end-column entity)
                         (:source/start-column entity))
                      (:source/end-column entity))]
    [line-span column-span
     (- (:source/start-line entity))
     (- (:source/start-column entity))]))

(defn- owner-order [entity]
  [(range-size entity)
   (if (= :symbol.kind/type (:symbol/kind entity)) 0 1)
   (:symbol/id entity)])

(defn- local-reference
  [symbols namespaces files-by-path record]
  (let [platform (:platform record)
        owner (->> symbols
                   (filter #(and (= platform (:symbol/platform %))
                                 (= (str "file:" (:filename record))
                                    (:symbol/file %))
                                 (contains-position?
                                  % (:row record) (:col record))))
                   (sort-by owner-order)
                   first)
        owner (or owner
                  (some #(when (and (= platform (:symbol/platform %))
                                    (= (str "file:" (:filename record))
                                       (:symbol/file %)))
                           %)
                        namespaces))
        content (:content (get files-by-path (:filename record)))]
    (when (and owner (local-call? content record))
      (when-let [target (local-name content record)]
        (diagnostic-reference
         :edge.kind/calls owner record target :dynamic
         :clj-kondo-local-usage nil)))))

(defn- positional-owner [symbols namespaces platform record]
  (or (->> symbols
           (filter #(and (= platform (:symbol/platform %))
                         (same-record-file? % record)
                         (contains-position? % (:row record) (:col record))))
           (sort-by owner-order)
           first)
      (some #(when (and (= platform (:symbol/platform %))
                        (same-record-file? % record))
               %)
            namespaces)))

(defn- protocol-relationship
  [symbols namespaces definitions-by-key platform record]
  (when (located? record)
    (when-let [owner (positional-owner symbols namespaces platform record)]
      (let [target (qualified (:protocol-ns record) (:method-name record))
            candidates
            (filter #(= (str (:protocol-name record))
                        (:symbol/protocol-name %))
                    (get definitions-by-key
                         [platform (:protocol-ns record)
                          (:method-name record)]))]
        (cond
          (= 1 (count candidates))
          (exact-edge :edge.kind/protocol-implements owner
                      (first candidates) target
                      :clj-kondo-protocol-impl record)

          (> (count candidates) 1)
          (diagnostic-reference :edge.kind/protocol-implements owner record
                                target :ambiguous
                                :clj-kondo-protocol-impl target)

          :else
          (diagnostic-reference :edge.kind/protocol-implements owner record
                                target :external
                                :clj-kondo-protocol-impl target))))))

(defn- java-relationship
  [symbols namespaces platform record evidence]
  (when (located? record)
    (when-let [owner (positional-owner symbols namespaces platform record)]
      (let [class-name (some-> (:class record) str)
            member-name (some-> (or (:method-name record)
                                    (:member-name record)
                                    (:name record))
                                str)
            target (cond
                     (and class-name member-name)
                     (str class-name "/" member-name)

                     member-name (str "." member-name)
                     class-name class-name)
            kind (if member-name :edge.kind/calls :edge.kind/references)]
        (when (seq target)
          (diagnostic-reference kind owner record target
                                (if member-name :dynamic :external)
                                evidence
                                (when class-name target)))))))

(defn materialize
  "Convert a clj-kondo snapshot into file-owned canonical entities."
  [files snapshot]
  (let [files (vec files)
        files-by-path (into {} (map (juxt :relative-path identity) files))
        file-entities (into {} (map (fn [file]
                                     [(:relative-path file)
                                      (file-entity file)]))
                                 files)
        analysis (:analysis snapshot)
        namespace-definitions
        (vec (distinct
              (expand-platforms (:namespace-definitions analysis))))
        var-definitions
        (effective-definitions
         (vec (expand-platforms (:var-definitions analysis))))
        namespaces
        (keep (fn [record]
                (when-let [file (get file-entities (:filename record))]
                  (namespace-symbol file (:platform record) record)))
              namespace-definitions)
        symbols
        (keep (fn [record]
                (when-let [file (get file-entities (:filename record))]
                  (var-symbol file (:platform record) record)))
              var-definitions)
        namespace-by-key
        (reduce (fn [result entity]
                  (update result
                          [(:symbol/platform entity)
                           (clojure.core/symbol
                            (:symbol/qualified-name entity))]
                          (fnil conj []) entity))
                {} namespaces)
        definitions-by-key
        (reduce (fn [result entity]
                  (update result
                          [(:symbol/platform entity)
                           (clojure.core/symbol
                            (namespace
                             (clojure.core/symbol
                              (:symbol/qualified-name entity))))
                           (clojure.core/symbol (:symbol/name entity))]
                          (fnil conj []) entity))
                {} symbols)
        var-links
        (keep (fn [record]
                (var-relationship namespace-by-key definitions-by-key
                                  (:platform record) record))
              (distinct (expand-platforms (:var-usages analysis))))
        namespace-links
        (keep (fn [record]
                (namespace-relationship namespace-by-key
                                        (:platform record) record))
              (distinct (expand-platforms (:namespace-usages analysis))))
        local-links
        (keep #(local-reference symbols namespaces files-by-path %)
              (distinct (expand-platforms (:local-usages analysis))))
        protocol-links
        (keep (fn [record]
                (protocol-relationship
                 symbols namespaces definitions-by-key
                 (:platform record) record))
              (distinct (expand-platforms (:protocol-impls analysis))))
        java-class-links
        (keep (fn [record]
                (java-relationship symbols namespaces (:platform record)
                                   record :clj-kondo-java-class-usage))
              (distinct (expand-platforms (:java-class-usages analysis))))
        java-member-links
        (keep (fn [record]
                (java-relationship symbols namespaces (:platform record)
                                   record :clj-kondo-java-member-usage))
              (distinct (expand-platforms (:java-member-usages analysis))))
        instance-links
        (keep (fn [record]
                (java-relationship symbols namespaces (:platform record)
                                   record :clj-kondo-instance-invocation))
              (distinct (expand-platforms (:instance-invocations analysis))))
        containment-links
        (keep (fn [entity]
                (when-let [namespace
                           (some
                            #(when (= (:symbol/file entity)
                                      (:symbol/file %))
                               %)
                            (get namespace-by-key
                                 [(:symbol/platform entity)
                                  (clojure.core/symbol
                                   (namespace
                                    (clojure.core/symbol
                                     (:symbol/qualified-name entity))))]))]
                  (containment namespace entity)))
              symbols)
        facts (concat namespaces symbols containment-links
                      namespace-links var-links local-links protocol-links
                      java-class-links java-member-links instance-links)
        facts-by-file
        (group-by
         (fn [entity]
           (or (some-> (:symbol/file entity) (subs 5))
               (let [owner-id (or (:edge/from entity)
                                  (:reference/symbol entity))
                     owner (some #(when (= owner-id (:symbol/id %)) %) 
                                 (concat namespaces symbols))]
                 (some-> (:symbol/file owner) (subs 5)))))
         facts)
        diagnostics-by-file (group-by :file (:diagnostics snapshot))]
    (mapv
     (fn [file]
       (let [path (:relative-path file)
             file-diagnostics (vec (get diagnostics-by-file path []))
             malformed? (boolean (seq file-diagnostics))
             entities (vec (get facts-by-file path []))
             framework-facts
             (topics/extract
              file
              (filter #(= :entity.type/symbol (:entity/type %)) entities)
              (filter #(= :entity.type/reference (:entity/type %)) entities))
             external-effects
             (->> entities
                  (filter #(and (= :entity.type/reference (:entity/type %))
                                (= :external (:reference/classification %))))
                  (map (fn [reference]
                         {:edge/kind (:reference/kind reference)
                          :edge/from (:reference/symbol reference)
                          :edge/target-text
                          (or (:reference/qualified-target reference)
                              (:reference/target-text reference))
                          :source/snippet (:reference/target-text reference)
                          :source/start-line (:source/start-line reference)
                          :source/start-column (:source/start-column reference)
                          :source/end-line (:source/end-line reference)
                          :source/end-column (:source/end-column reference)}))
                  (effects/analyze (:language file)))
             line-starts (line-start-offsets (:content file))
             entities (mapv #(with-byte-range (:content file) line-starts %)
                            (concat entities framework-facts
                                    external-effects))]
         {:file (get file-entities path)
          ;; clj-kondo can emit partial rows after reader failures. Those rows
          ;; must never be mistaken for a complete semantic snapshot.
          :entities (if malformed? [] entities)
          :diagnostics file-diagnostics
          :status (if malformed? :malformed :ok)
          :preserve? malformed?}))
     files)))
