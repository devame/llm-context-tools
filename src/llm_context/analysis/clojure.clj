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

(defn- file-entity
  [{:keys [relative-path language content size modified-at]}]
  {:entity/type :entity.type/file
   :file/id (ids/file-id relative-path)
   :file/path relative-path
   :file/language language
   :file/content-hash (ids/content-hash content)
   :file/size size
   :file/modified-at modified-at})

(defn- range-data [record]
  (cond-> {}
    (:row record) (assoc :source/start-line (:row record))
    (:col record) (assoc :source/start-column (:col record))
    (:end-row record) (assoc :source/end-line (:end-row record))
    (:end-col record) (assoc :source/end-column (:end-col record))))

(defn- qualified [ns-name name]
  (str ns-name "/" name))

(defn- definition-kind [{:keys [defined-by defined-by->lint-as macro]}]
  (or (get definition-forms defined-by->lint-as)
      (get definition-forms defined-by)
      (when macro :symbol.kind/macro)
      :symbol.kind/variable))

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
      :symbol/analyzer :clj-kondo}
     (range-data definition))))

(defn- var-symbol [file platform definition]
  (let [kind (definition-kind definition)
        name (str (:name definition))
        qname (qualified (:ns definition) (:name definition))
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
              :symbol/private? (boolean (:private definition))
              :symbol/macro? (= :symbol.kind/macro kind)}
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

(defn- owner-for
  [namespace-by-key definitions-by-key platform record]
  (or (when-let [from-var (:from-var record)]
        (first (get definitions-by-key
                    [platform (:from record) from-var])))
      (get namespace-by-key [platform (:from record)])))

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
   (when-let [owner (get namespace-by-key [platform (:from record)])]
    (let [target-name (str (:to record))]
      (if-let [target (get namespace-by-key [platform (:to record)])]
        (exact-edge :edge.kind/imports owner target target-name
                    :clj-kondo-namespace-usage record)
        (diagnostic-reference
         :edge.kind/imports owner record target-name :external
         :clj-kondo-namespace-usage target-name))))))

(defn- local-call?
  [content {:keys [row name-col col]}]
  (let [column (or name-col col)]
   (when (and row column)
    (let [lines (str/split-lines content)
          line (nth lines (dec row) nil)
          before (when (and line (> column 1))
                   (subs line 0 (min (count line) (dec column))))]
      (boolean (and before (re-find #"\(\s*$" before)))))))

(defn- contains-position? [entity row col]
  (let [start [(:source/start-line entity) (:source/start-column entity)]
        end [(:source/end-line entity) (:source/end-column entity)]]
    (and (every? some? (concat start end [row col]))
         (not (neg? (compare [row col] start)))
         (not (pos? (compare [row col] end))))))

(defn- local-reference
  [symbols namespaces files-by-path record]
  (let [platform (:platform record)
        owner (->> symbols
                   (filter #(and (= platform (:symbol/platform %))
                                 (= (str "file:" (:filename record))
                                    (:symbol/file %))
                                 (contains-position?
                                  % (:row record) (:col record))))
                   (sort-by #(- (:source/end-line %)
                                (:source/start-line %)))
                   first)
        owner (or owner
                  (some #(when (and (= platform (:symbol/platform %))
                                    (= (str "file:" (:filename record))
                                       (:symbol/file %)))
                           %)
                        namespaces))
        content (:content (get files-by-path (:filename record)))]
    (when (and owner (local-call? content record))
      (diagnostic-reference
       :edge.kind/calls owner record (str (:name record)) :dynamic
       :clj-kondo-local-usage nil))))

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
        (vec (expand-platforms (:namespace-definitions analysis)))
        var-definitions (vec (expand-platforms (:var-definitions analysis)))
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
        (into {} (map (fn [entity]
                        [[(:symbol/platform entity)
                          (clojure.core/symbol
                           (:symbol/qualified-name entity))]
                         entity]))
              namespaces)
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
              (expand-platforms (:var-usages analysis)))
        namespace-links
        (keep (fn [record]
                (namespace-relationship namespace-by-key
                                        (:platform record) record))
              (expand-platforms (:namespace-usages analysis)))
        local-links
        (keep #(local-reference symbols namespaces files-by-path %)
              (expand-platforms (:local-usages analysis)))
        containment-links
        (keep (fn [entity]
                (when-let [namespace
                           (get namespace-by-key
                                [(:symbol/platform entity)
                                 (clojure.core/symbol
                                  (namespace
                                   (clojure.core/symbol
                                    (:symbol/qualified-name entity))))])]
                  (containment namespace entity)))
              symbols)
        facts (concat namespaces symbols containment-links
                      namespace-links var-links local-links)
        facts-by-file
        (group-by
         (fn [entity]
           (or (some-> (:symbol/file entity) (subs 5))
               (let [owner-id (or (:edge/from entity)
                                  (:reference/symbol entity))
                     owner (some #(when (= owner-id (:symbol/id %)) %) 
                                 (concat namespaces symbols))]
                 (some-> (:symbol/file owner) (subs 5)))))
         facts)]
    (mapv
     (fn [file]
       (let [path (:relative-path file)
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
                  (effects/analyze (:language file)))]
         {:file (get file-entities path)
          :entities (vec (concat entities framework-facts external-effects))
          :diagnostics []}))
     files)))
