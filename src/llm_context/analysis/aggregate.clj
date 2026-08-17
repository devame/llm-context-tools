(ns llm-context.analysis.aggregate
  "Repository-neutral, non-evaluating extraction of top-level literal
  collections. Producers emit graph-format aggregate facts; dynamic forms are
  retained as partial evidence and never advertised as complete inventories."
  (:require [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [llm-context.model.ids :as ids]
            [llm-context.source :as source]))

(def producer-version 1)

(def ^:private eof (Object.))
(def ^:private definition-heads
  #{"def" "defonce"})

(defn- valid-js-literal-key? [key]
  (or (string? key)
      (and (keyword? key)
           (nil? (namespace key)))))

(defn- read-js-literal [value]
  "Read ClojureScript's #js syntax as safe data for aggregate analysis.

  The compiler wraps this value in a JSValue marker because it needs to emit
  JavaScript. Aggregate analysis only needs the literal shape, so retaining
  the underlying map or vector is the useful non-evaluating representation."
  (when-not (or (map? value) (vector? value))
    (throw (RuntimeException.
            "JavaScript literal must use map or vector notation")))
  (when (and (map? value)
             (not-every? valid-js-literal-key? (keys value)))
    (throw (RuntimeException.
            "JavaScript literal keys must be strings or unqualified keywords")))
  value)

(defn- read-options [language]
  {:eof eof
   :read-cond :allow
   :features (if (= :language/clojurescript language) #{:cljs} #{:clj})})

(defn- top-level-forms [content language]
  (let [input (reader-types/indexing-push-back-reader content)
        options (read-options language)]
    (binding [reader/*read-eval* false
              reader/*data-readers*
              (assoc reader/*data-readers* 'js #'read-js-literal)
              reader/*default-data-reader-fn*
              (fn [tag value] (tagged-literal tag value))]
      (loop [result []]
        (let [form (reader/read options input)]
          (if (identical? eof form)
            result
            (recur (conj result form))))))))

(defn- definition-form? [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? definition-heads (name (first form)))
       (symbol? (second form))))

(defn- definition-value [form]
  (let [tail (drop 2 form)
        tail (if (string? (first tail)) (next tail) tail)
        ;; A metadata map is followed by the actual value. A lone map is the
        ;; value itself and must not be consumed as metadata.
        tail (if (and (map? (first tail)) (next tail)) (next tail) tail)]
    (first tail)))

(defn- literal-collection [value]
  (cond
    (map? value) {:kind :aggregate.kind/literal-map :value value}
    (set? value) {:kind :aggregate.kind/literal-set :value value}
    (vector? value) {:kind :aggregate.kind/literal-vector :value value}
    (and (seq? value)
         (= 'quote (first value))
         (coll? (second value)))
    (let [quoted (second value)]
      {:kind (cond
               (map? quoted) :aggregate.kind/literal-map
               (set? quoted) :aggregate.kind/literal-set
               (vector? quoted) :aggregate.kind/literal-vector
               :else :aggregate.kind/literal-list)
       :value quoted})
    :else nil))

(defn- literal-data? [value]
  (cond
    (or (nil? value) (string? value) (keyword? value) (number? value)
        (boolean? value) (char? value)) true
    (symbol? value) false
    (map? value) (every? (fn [[key item]]
                           (and (literal-data? key) (literal-data? item)))
                         value)
    (set? value) (every? literal-data? value)
    (vector? value) (every? literal-data? value)
    (seq? value) false
    :else false))

(defn- value-kind [value]
  (cond
    (nil? value) :nil
    (string? value) :string
    (keyword? value) :keyword
    (symbol? value) :symbol
    (number? value) :number
    (boolean? value) :boolean
    (char? value) :character
    (map? value) :map
    (set? value) :set
    (vector? value) :vector
    (seq? value) :list
    :else :other))

(defn- stable-members [kind value]
  (case kind
    :aggregate.kind/literal-map
    (->> value
         (map (fn [[key item]] {:key key :value item}))
         (sort-by (comp pr-str :key))
         vec)

    :aggregate.kind/literal-set
    (->> value (sort-by pr-str) (mapv #(hash-map :value %)))

    (mapv #(hash-map :value %) value)))

(defn- source-range [source-index form]
  (let [{:keys [line column end-line end-column]} (meta form)
        start-byte (source/byte-offset source-index line column)
        end-byte (source/byte-offset source-index end-line end-column)]
    (when (every? some? [line column end-line end-column
                         start-byte end-byte])
      {:source/start-line line
       :source/start-column column
       :source/end-line end-line
       :source/end-column end-column
       :source/start-byte start-byte
       :source/end-byte end-byte})))

(defn- member-entity [aggregate-id ordinal source-range-data
                      {:keys [key value]}]
  (let [key-text (when (some? key) (pr-str key))
        value-text (pr-str value)
        literal? (and (or (nil? key) (literal-data? key))
                      (literal-data? value))]
    (merge
     (cond-> {:entity/type :entity.type/membership
              :membership/id
              (ids/membership-id {:aggregate-id aggregate-id
                                  :ordinal ordinal
                                  :key key-text
                                  :value value-text})
              :membership/aggregate aggregate-id
              :membership/value value-text
              :membership/value-kind (value-kind value)
              :membership/ordinal ordinal
              :membership/evidence
              (if literal? :literal :dynamic-expression)}
       key-text (assoc :membership/key key-text))
     source-range-data)))

(defn- aggregate-entities [file owner form {:keys [kind value]}]
  (let [source-index (::source-index file)
        source-range-data (source-range source-index form)
        members (stable-members kind value)
        aggregate-id (ids/aggregate-id {:owner-id (:symbol/id owner)
                                        :kind kind})
        member-entities
        (mapv #(member-entity aggregate-id %1 source-range-data %2)
              (clojure.core/range) members)
        complete? (every? #(= :literal (:membership/evidence %))
                          member-entities)
        member-kinds (set (map :membership/value-kind member-entities))
        aggregate
        (merge
         {:entity/type :entity.type/aggregate
          :aggregate/id aggregate-id
          :aggregate/name (:symbol/name owner)
          :aggregate/kind kind
          :aggregate/owner (:symbol/id owner)
          :aggregate/file (:file/id file)
          :aggregate/completeness
          (if complete? :complete-static :partial-static)
          :aggregate/member-count (count members)
          :aggregate/member-kind
          (if (= 1 (count member-kinds)) (first member-kinds) :mixed)
          :aggregate/analyzer :clojure-literal-aggregate
          :aggregate/search-text
          (str/join "\n"
                    (concat
                     [(:symbol/name owner)
                      (:symbol/qualified-name owner)
                      (or (:symbol/doc owner) "")
                      "statically declared collection registry inventory"
                      (name kind)
                      (name (if complete?
                              :complete-static :partial-static))]
                     (mapcat (fn [member]
                               (cond-> []
                                 (:membership/key member)
                                 (conj (:membership/key member))
                                 true
                                 (conj (:membership/value member))))
                             member-entities)))}
         source-range-data)]
    (into [aggregate] member-entities)))

(defn- eligible-symbols [entities name]
  (filter #(and (= :entity.type/symbol (:entity/type %))
                (contains? #{:symbol.kind/variable :symbol.kind/constant}
                           (:symbol/kind %))
                (= name (:symbol/name %)))
          entities))

(defn enrich-output
  "Add safe aggregate facts to one Clojure-family analyzer output. Reader or
  unsupported-form failures preserve the original output and add an
  inspectable diagnostic; project code is never evaluated."
  [file-source output]
  (if-not (contains? #{:language/clojure :language/clojurescript
                       :language/clojure-common}
                     (:language file-source))
    output
    (try
      (let [content (:content file-source)
            source-index (source/index content)
            file (assoc (:file output) ::source-index source-index)
            facts
            (mapcat
             (fn [form]
               (when (definition-form? form)
                 (when-let [collection (literal-collection
                                        (definition-value form))]
                   (mapcat #(aggregate-entities file % form collection)
                           (eligible-symbols (:entities output)
                                             (str (second form)))))))
             (top-level-forms content (:language file-source)))]
        (update output :entities into facts))
      (catch Throwable error
        (update output :diagnostics conj
                {:level :info
                 :kind :aggregate-analysis-skipped
                 :file (:relative-path file-source)
                 :message (.getMessage error)})))))

(defn enrich-outputs [files outputs]
  (let [files-by-path (into {} (map (juxt :relative-path identity)) files)]
    (mapv (fn [output]
            (if-let [file-source
                     (get files-by-path (get-in output [:file :file/path]))]
              (enrich-output file-source output)
              output))
          outputs)))
