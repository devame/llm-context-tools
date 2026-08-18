(ns llm-context.analysis.clojure
  "Materialize exact Clojure graph facts from normalized clj-kondo output."
  (:require [clojure.string :as str]
            [llm-context.analysis.clojure-topics :as topics]
            [llm-context.analysis.effects :as effects]
            [llm-context.model.ids :as ids]
            [llm-context.source :as source]))

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

(defn- with-byte-range [source-index entity]
  (let [{:source/keys [start-line start-column end-line end-column]} entity]
    (if (every? some? [start-line start-column end-line end-column])
      (let [start-byte (source/byte-offset source-index
                                           start-line start-column)
            end-byte (source/byte-offset source-index end-line end-column)]
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

(defn- effective-namespaces
  "Collapse repeated declarations of one namespace in a file/platform to its
  first source declaration. A namespace has one canonical identity; retaining
  later declaration ranges under that identity creates contradictory facts."
  [records]
  (->> records
       distinct
       (group-by (juxt :platform :filename :name))
       (sort-by key)
       (mapv (fn [[_ observations]]
               (first (sort-by source-order observations))))))

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
      :symbol/indexable? true}
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
  [source-index {:keys [row name-col col]}]
  (let [column (or name-col col)]
   (when (and row column)
    (let [line (source/line-text source-index row)
          before (when (and line (> column 1))
                   (subs line 0 (min (count line) (dec column))))]
      (boolean (and before (re-find #"\(\s*$" before)))))))

(defn- local-name
  "Prefer clj-kondo's normalized local name, but recover it from the precise
  name range when analysis-data omits :name. clj-kondo can emit this shape for
  ClojureScript macro-bound locals such as cljs.test/async's `done` callback."
  [source-index record]
  (let [reported (some-> (:name record) str)]
    (or (when-not (str/blank? reported) reported)
        (let [row (or (:name-row record) (:row record))
              end-row (or (:name-end-row record) row)
              start-column (or (:name-col record) (:col record))
              end-column (or (:name-end-col record) (:end-col record))
              line (when (and (pos-int? row)
                              (= row end-row))
                     (source/line-text source-index row))
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

(defn- source-start [entity]
  [(:source/start-line entity) (:source/start-column entity)])

(defn- source-end [entity]
  [(:source/end-line entity) (:source/end-column entity)])

(defn- complete-range? [entity]
  (every? some? (concat (source-start entity) (source-end entity))))

(defn- later-position [left right]
  (cond
    (nil? left) right
    (nil? right) left
    (pos? (compare left right)) left
    :else right))

(defn- build-interval-tree
  "Build a balanced positional index augmented with the latest end position in
  each subtree. Point lookup visits only branches that can still contain the
  requested source position."
  [entities]
  (let [ordered (vec (sort-by (juxt source-start source-end :symbol/id)
                              (filter complete-range? entities)))]
    (letfn [(build [start end]
              (when (< start end)
                (let [middle (quot (+ start end) 2)
                      entity (nth ordered middle)
                      left (build start middle)
                      right (build (inc middle) end)]
                  {:entity entity
                   :left left
                   :right right
                   :max-end (reduce later-position
                                    (source-end entity)
                                    (keep :max-end [left right]))})))]
      (build 0 (count ordered)))))

(defn- interval-matches [tree position stats]
  (letfn [(visit [node result]
            (if-not node
              result
              (let [_ (when stats
                        (swap! stats update :positional-candidates-examined
                               (fnil inc 0)))
                    {:keys [entity left right]} node
                    start (source-start entity)
                    end (source-end entity)
                    result
                    (if (and left (pos? (compare (:max-end left) position)))
                      (visit left result)
                      result)
                    result (if (and (not (pos? (compare start position)))
                                    (pos? (compare end position)))
                             (conj result entity)
                             result)]
                (if (and right (not (pos? (compare start position))))
                  (visit right result)
                  result))))]
    (visit tree [])))

(defn- positional-index [symbols]
  (into {}
        (map (fn [[key entities]] [key (build-interval-tree entities)]))
        (group-by (juxt :symbol/file :symbol/platform) symbols)))

(defn- local-reference
  [owners namespaces-by-file-platform source-index record stats]
  (let [platform (:platform record)
        file-id (str "file:" (:filename record))
        _ (when stats (swap! stats update :ownership-lookups (fnil inc 0)))
        owner (->> (interval-matches (get owners [file-id platform])
                                     [(:row record) (:col record)] stats)
                   (sort-by owner-order)
                   first)
        owner (or owner
                  (get namespaces-by-file-platform [file-id platform]))]
    (when (and owner (local-call? source-index record))
      (when-let [target (local-name source-index record)]
        (diagnostic-reference
         :edge.kind/calls owner record target :dynamic
         :clj-kondo-local-usage nil)))))

(defn- positional-owner
  [owners namespaces-by-file-platform platform record stats]
  (let [file-id (str "file:" (:filename record))
        _ (when stats (swap! stats update :ownership-lookups (fnil inc 0)))]
    (or (->> (interval-matches (get owners [file-id platform])
                               [(:row record) (:col record)] stats)
             (sort-by owner-order)
             first)
        (get namespaces-by-file-platform [file-id platform]))))

(defn- protocol-relationship
  [owners namespaces-by-file-platform definitions-by-key platform record stats]
  (when (located? record)
    (when-let [owner (positional-owner owners namespaces-by-file-platform
                                      platform record stats)]
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
  [owners namespaces-by-file-platform platform record evidence stats]
  (when (located? record)
    (when-let [owner (positional-owner owners namespaces-by-file-platform
                                      platform record stats)]
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

(defn- materialize*
  [files snapshot stats external-symbols]
  (let [files (vec files)
        file-entities (into {} (map (fn [file]
                                     [(:relative-path file)
                                      (file-entity file)]))
                                 files)
        analysis (:analysis snapshot)
        namespace-definitions
        (effective-namespaces
         (vec (expand-platforms (:namespace-definitions analysis))))
        var-definitions
        (effective-definitions
         (vec (expand-platforms (:var-definitions analysis))))
        namespaces
        (into []
              (keep (fn [record]
                      (when-let [file (get file-entities (:filename record))]
                        (namespace-symbol file (:platform record) record))))
              namespace-definitions)
        symbols
        (into []
              (keep (fn [record]
                      (when-let [file (get file-entities (:filename record))]
                        (var-symbol file (:platform record) record))))
              var-definitions)
        external-namespaces
        (filterv #(= :symbol.kind/namespace (:symbol/kind %)) external-symbols)
        external-definitions
        (filterv #(not= :symbol.kind/namespace (:symbol/kind %)) external-symbols)
        all-namespaces (into namespaces external-namespaces)
        all-symbols (into symbols external-definitions)
        symbols-by-position (positional-index symbols)
        namespaces-by-file-platform
        (into {}
              (map (fn [entity]
                     [[(:symbol/file entity) (:symbol/platform entity)] entity]))
              all-namespaces)
        symbol-by-id
        (into {} (map (juxt :symbol/id identity))
              (concat all-namespaces all-symbols))
        namespace-by-key
        (reduce (fn [result entity]
                  (update result
                          [(:symbol/platform entity)
                           (clojure.core/symbol
                            (:symbol/qualified-name entity))]
                          (fnil conj []) entity))
                {} all-namespaces)
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
                {} all-symbols)
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
        local-records-by-file
        (group-by :filename
                  (distinct (expand-platforms (:local-usages analysis))))
        protocol-links
        (keep (fn [record]
                (protocol-relationship
                 symbols-by-position namespaces-by-file-platform
                 definitions-by-key (:platform record) record stats))
              (distinct (expand-platforms (:protocol-impls analysis))))
        java-class-links
        (keep (fn [record]
                (java-relationship symbols-by-position
                                   namespaces-by-file-platform
                                   (:platform record) record
                                   :clj-kondo-java-class-usage stats))
              (distinct (expand-platforms (:java-class-usages analysis))))
        java-member-links
        (keep (fn [record]
                (java-relationship symbols-by-position
                                   namespaces-by-file-platform
                                   (:platform record) record
                                   :clj-kondo-java-member-usage stats))
              (distinct (expand-platforms (:java-member-usages analysis))))
        instance-links
        (keep (fn [record]
                (java-relationship symbols-by-position
                                   namespaces-by-file-platform
                                   (:platform record) record
                                   :clj-kondo-instance-invocation stats))
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
                      namespace-links var-links protocol-links
                      java-class-links java-member-links instance-links)
        facts-by-file
        (group-by
         (fn [entity]
           (or (some-> (:symbol/file entity) (subs 5))
               (let [owner-id (or (:edge/from entity)
                                  (:reference/symbol entity))
                     _ (when stats
                         (swap! stats update :fact-owner-lookups (fnil inc 0)))
                     owner (get symbol-by-id owner-id)]
                 (some-> (:symbol/file owner) (subs 5)))))
         facts)
        diagnostics-by-file (group-by :file (:diagnostics snapshot))]
    (mapv
     (fn [file]
       (let [path (:relative-path file)
             source-index (source/index (:content file))
             _ (when stats (swap! stats update :source-indexes-built
                                  (fnil inc 0)))
             file-diagnostics (vec (get diagnostics-by-file path []))
             malformed? (boolean (seq file-diagnostics))
             local-links
             (into []
                   (keep #(local-reference symbols-by-position
                                           namespaces-by-file-platform
                                           source-index % stats))
                   (get local-records-by-file path []))
             entities (into (vec (get facts-by-file path [])) local-links)
             framework-facts
             (topics/extract
              file
              (filter #(= :entity.type/symbol (:entity/type %)) entities)
              (filter #(= :entity.type/reference (:entity/type %)) entities))
             topic-diagnostic
             (when-let [message (:diagnostic (meta framework-facts))]
               {:level :info
                :kind :topic-analysis-skipped
                :file path
                :message message})
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
             entities (mapv #(with-byte-range source-index %)
                            (concat entities framework-facts
                                    external-effects))]
         {:file (get file-entities path)
          ;; clj-kondo can emit partial rows after reader failures. Those rows
          ;; must never be mistaken for a complete semantic snapshot.
          :entities (if malformed? [] entities)
          :diagnostics (cond-> file-diagnostics
                         topic-diagnostic (conj topic-diagnostic))
          :status (if malformed? :malformed :ok)
          :preserve? malformed?}))
     files)))

(defn materialize-with-metrics
  "Convert a clj-kondo snapshot into file-owned canonical entities and return
  deterministic operation counts for scale regression checks."
  ([files snapshot]
   (materialize-with-metrics files snapshot []))
  ([files snapshot external-symbols]
   (let [stats (atom {:ownership-lookups 0
                     :positional-candidates-examined 0
                     :fact-owner-lookups 0
                     :source-indexes-built 0})
         outputs (materialize* files snapshot stats external-symbols)]
     {:outputs outputs
      :metrics (assoc @stats
                     :namespace-definitions
                     (count (get-in snapshot [:analysis :namespace-definitions]))
                     :var-definitions
                     (count (get-in snapshot [:analysis :var-definitions]))
                     :local-usages
                     (count (get-in snapshot [:analysis :local-usages]))
                     :var-usages
                     (count (get-in snapshot [:analysis :var-usages]))
                     :generated-facts
                     (reduce + 0 (map (comp count :entities) outputs)))})))

(defn materialize
  "Convert a clj-kondo snapshot into file-owned canonical entities."
  [files snapshot]
  (:outputs (materialize-with-metrics files snapshot)))
