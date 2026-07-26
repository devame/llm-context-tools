(ns llm-context.analysis.clojure-topics
  "Extract literal framework and state identities only at clj-kondo-resolved
  call sites. Source forms are read with evaluation disabled; project code is
  never loaded or evaluated."
  (:require [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [llm-context.model.ids :as ids]))

(def framework-apis
  {"re-frame.core/reg-event-db" [:event :edge.kind/topic-registers]
   "re-frame.core/reg-event-fx" [:event :edge.kind/topic-registers]
   "re-frame.core/reg-event-ctx" [:event :edge.kind/topic-registers]
   "re-frame.core/reg-sub" [:subscription :edge.kind/topic-registers]
   "re-frame.core/reg-fx" [:effect :edge.kind/topic-registers]
   "re-frame.core/reg-cofx" [:coeffect :edge.kind/topic-registers]
   "re-frame.core/dispatch" [:event :edge.kind/event-dispatches]
   "re-frame.core/dispatch-sync" [:event :edge.kind/event-dispatches]
   "re-frame.core/subscribe" [:subscription :edge.kind/subscribes]})

(def state-apis
  {"cljs.core/get" [:edge.kind/state-reads 1]
   "clojure.core/get" [:edge.kind/state-reads 1]
   "cljs.core/get-in" [:edge.kind/state-reads 1]
   "clojure.core/get-in" [:edge.kind/state-reads 1]
   "cljs.core/assoc" [:edge.kind/state-writes 1]
   "clojure.core/assoc" [:edge.kind/state-writes 1]
   "cljs.core/assoc-in" [:edge.kind/state-writes 1]
   "clojure.core/assoc-in" [:edge.kind/state-writes 1]
   "cljs.core/update" [:edge.kind/state-writes 1]
   "clojure.core/update" [:edge.kind/state-writes 1]
   "cljs.core/update-in" [:edge.kind/state-writes 1]
   "clojure.core/update-in" [:edge.kind/state-writes 1]
   "cljs.core/dissoc" [:edge.kind/state-writes 1]
   "clojure.core/dissoc" [:edge.kind/state-writes 1]})

(def mutation-apis #{"cljs.core/swap!" "clojure.core/swap!"})
(def atom-read-apis #{"cljs.core/deref" "clojure.core/deref"})
(def atom-write-apis #{"cljs.core/reset!" "clojure.core/reset!"})
(def atom-constructor-apis #{"cljs.core/atom" "clojure.core/atom"})
(def topic-apis
  (into #{} (concat (keys framework-apis)
                    (keys state-apis)
                    mutation-apis atom-read-apis atom-write-apis)))

(defn- offset-at [source row column]
  (when (and (string? source) (pos-int? row) (pos-int? column))
    (let [lines (str/split source #"\n" -1)]
      (when-let [line (nth lines (dec row) nil)]
        (+ (reduce + 0 (map #(inc (count %)) (take (dec row) lines)))
           (min (count line) (dec column)))))))

(defn- opening-list [source offset]
  (loop [index (if (and (< offset (count source))
                        (= \( (.charAt source offset)))
                 offset
                 (dec offset))]
    (when (>= index 0)
      (let [character (.charAt source index)]
        (cond
          (= character \() index
          (Character/isWhitespace character) (recur (dec index))
          :else nil)))))

(defn- read-form [source platform]
  (try
    (binding [reader/*read-eval* false
              reader/*data-readers* {}
              reader/*default-data-reader-fn* nil]
      (reader/read-string {:read-cond :allow
                           :features #{platform}
                           :eof ::eof}
                          source))
    (catch Throwable _
      ::unreadable)))

(defn- call-form [source reference platform]
  (when-let [offset (offset-at source (:source/start-line reference)
                               (:source/start-column reference))]
    (when-let [start (opening-list source offset)]
      (let [form (read-form (subs source start) platform)]
        (when (seq? form) form)))))

(defn- source-form [source reference platform]
  (when-let [offset (offset-at source (:source/start-line reference)
                               (:source/start-column reference))]
    (read-form (subs source offset) platform)))

(defn- static-data? [value]
  (cond
    (or (nil? value) (string? value) (keyword? value) (number? value)
        (char? value) (boolean? value))
    true

    (vector? value) (every? static-data? value)
    (map? value) (every? static-data? (mapcat identity value))
    (set? value) (every? static-data? value)
    :else false))

(defn- owner-namespace [owner]
  (namespace (symbol (:symbol/qualified-name owner))))

(defn- normalize-keyword [owner value]
  ;; tools.reader resolves an unqualified auto-keyword against this analyzer's
  ;; namespace. Replace only that synthetic namespace with the source owner.
  (if (= (namespace value) (str *ns*))
    (keyword (owner-namespace owner) (name value))
    value))

(defn- normalize-literal [owner value]
  (cond
    (keyword? value) (normalize-keyword owner value)
    (vector? value) (mapv #(normalize-literal owner %) value)
    (map? value) (into (empty value)
                       (map (fn [[key item]]
                              [(normalize-literal owner key)
                               (normalize-literal owner item)]))
                       value)
    (set? value) (set (map #(normalize-literal owner %) value))
    :else value))

(defn- topic-key [owner value vector-head?]
  (when (static-data? value)
    (let [value (if (and vector-head? (vector? value))
                  (first value)
                  value)]
      (when (or (keyword? value)
                (and (vector? value) (every? static-data? value)))
        (pr-str (normalize-literal owner value))))))

(defn- atom-key [value]
  (when (symbol? value)
    (str "atom:" value)))

(defn- atom-symbol [value]
  (cond
    (symbol? value) value
    (and (seq? value)
         (= "deref" (some-> value first name))
         (symbol? (second value)))
    (second value)))

(defn- atom-root? [atom-names value]
  (contains? atom-names (atom-symbol value)))

(defn- within-range? [outer inner]
  (let [outer-start [(:source/start-line outer)
                     (:source/start-column outer)]
        outer-end [(:source/end-line outer)
                   (:source/end-column outer)]
        inner-start [(:source/start-line inner)
                     (:source/start-column inner)]]
    (and (every? some? (concat outer-start outer-end inner-start))
         (not (neg? (compare inner-start outer-start)))
         (neg? (compare inner-start outer-end)))))

(defn- handler-db-roots [source symbols-by-id references]
  (keep
   (fn [reference]
     (when (= "re-frame.core/reg-event-db"
              (:reference/qualified-target reference))
       (when-let [owner (get symbols-by-id (:reference/symbol reference))]
         (let [form (call-form source reference (:symbol/platform owner))
               handler (some #(when (and (seq? %)
                                         (= "fn" (some-> % first name)))
                                %)
                             (drop 2 form))
               parameters (some #(when (vector? %) %) (rest handler))
               db-binding (first parameters)]
           (when (symbol? db-binding)
             {:binding db-binding :range reference})))))
   references))

(defn- handler-db-root? [handler-roots reference value]
  (and (symbol? value)
       (some #(and (= value (:binding %))
                   (within-range? (:range %) reference))
             handler-roots)))

(defn- proven-state-root?
  [handler-roots atom-names reference value]
  (or (atom-root? atom-names value)
      (handler-db-root? handler-roots reference value)))

(defn- topic [owner kind key]
  {:entity/type :entity.type/topic
   :topic/id (ids/topic-id {:platform (:symbol/platform owner)
                            :kind kind :key key})
   :topic/kind kind
   :topic/key key
   :topic/platform (:symbol/platform owner)})

(defn- topic-edge [owner topic kind reference]
  (merge
   {:entity/type :entity.type/edge
    :edge/id
    (ids/edge-id {:kind kind :from-id (:symbol/id owner)
                  :to-id (:topic/id topic)
                  :start-line (:source/start-line reference)
                  :start-column (:source/start-column reference)})
    :edge/kind kind
    :edge/from (:symbol/id owner)
    :edge/to (:topic/id topic)
    :edge/target-text (:topic/key topic)
    :edge/resolution :resolution/exact
    :edge/confidence 1.0
    :edge/evidence :literal-clojure-form}
   (into {} (filter (comp some? val))
         (select-keys reference
                      [:source/start-line :source/start-column
                       :source/end-line :source/end-column]))))

(defn- dynamic-topic-reference [owner kind value reference]
  (merge
   {:entity/type :entity.type/reference
    :reference/id
    (ids/reference-id
     {:platform (:symbol/platform owner)
      :symbol-id (:symbol/id owner)
      :kind kind
      :target-text (or value "<computed-topic>")
      :classification :dynamic
      :start-line (:source/start-line reference)
      :start-column (:source/start-column reference)})
    :reference/symbol (:symbol/id owner)
    :reference/kind kind
    :reference/target-text (or value "<computed-topic>")
    :reference/classification :dynamic
    :reference/evidence :computed-clojure-topic}
   (into {} (filter (comp some? val))
         (select-keys reference
                      [:source/start-line :source/start-column
                       :source/end-line :source/end-column]))))

(defn- facts-for-reference
  [{:keys [symbols-by-id source atom-names handler-roots]} reference]
  (let [owner (get symbols-by-id (:reference/symbol reference))
        target (:reference/qualified-target reference)]
    (when (and owner (= :cljs (:symbol/platform owner))
               (contains? topic-apis target)
               (pos-int? (:source/start-line reference))
               (pos-int? (:source/start-column reference)))
      (let [form (call-form source reference (:symbol/platform owner))]
        (cond
          (contains? framework-apis target)
          (let [[topic-kind edge-kind] (get framework-apis target)
                argument (nth form 1 nil)
                key (topic-key owner argument
                               (contains? #{:event :subscription}
                                          topic-kind))]
            (if key
              (let [entity (topic owner topic-kind key)]
                [entity (topic-edge owner entity edge-kind reference)])
              [(dynamic-topic-reference owner edge-kind
                                        (some-> argument pr-str)
                                        reference)]))

          (contains? state-apis target)
          (let [[edge-kind key-index] (get state-apis target)
                collection (nth form 1 nil)
                argument (nth form (inc key-index) nil)
                key (topic-key owner argument false)]
            (when (proven-state-root?
                   handler-roots atom-names reference collection)
              (if key
                (let [entity (topic owner :state-key key)]
                  [entity (topic-edge owner entity edge-kind reference)])
                [(dynamic-topic-reference owner edge-kind
                                          (some-> argument pr-str)
                                          reference)])))

          (contains? mutation-apis target)
          (let [atom-value (nth form 1 nil)
                atom-identity (when (atom-root? atom-names atom-value)
                                (atom-key atom-value))
                path (topic-key owner (nth form 3 nil) false)
                keys (remove nil? [atom-identity path])]
            (when atom-identity
              (mapcat
               (fn [key]
                 (let [entity (topic owner :state-key key)]
                   [entity
                    (topic-edge owner entity :edge.kind/state-writes
                                reference)]))
               keys)))

          (contains? atom-read-apis target)
          (let [atom-value
                (or (nth form 1 nil)
                    (let [value (source-form
                                 source reference
                                 (:symbol/platform owner))]
                      (when (and (seq? value)
                                 (= "deref" (some-> value first name)))
                        (second value))))]
            (when-let [key (when (atom-root? atom-names atom-value)
                             (atom-key atom-value))]
              (let [entity (topic owner :state-key key)]
                [entity
                 (topic-edge owner entity :edge.kind/state-reads reference)])))

          (contains? atom-write-apis target)
          (let [atom-value (nth form 1 nil)]
            (when-let [key (when (atom-root? atom-names atom-value)
                             (atom-key atom-value))]
              (let [entity (topic owner :state-key key)]
                [entity
                 (topic-edge owner entity :edge.kind/state-writes
                             reference)]))))))))

(defn extract
  "Return topic entities and exact owner-to-topic edges for resolved APIs.
  Dynamic or computed keys remain absent rather than becoming false topics."
  [file symbols references]
  (let [symbols-by-id (into {} (map (juxt :symbol/id identity)) symbols)
        source (:content file)
        atom-owner-ids
        (into #{}
              (keep #(when (contains? atom-constructor-apis
                                      (:reference/qualified-target %))
                       (:reference/symbol %)))
              references)
        atom-names
        (into #{}
              (mapcat (fn [symbol]
                        [(clojure.core/symbol (:symbol/name symbol))
                         (clojure.core/symbol
                          (:symbol/qualified-name symbol))]))
              (keep symbols-by-id atom-owner-ids))
        context {:symbols-by-id symbols-by-id
                 :source source
                 :atom-names atom-names
                 :handler-roots
                 (handler-db-roots source symbols-by-id references)}]
    (->> references
         (mapcat #(facts-for-reference context %))
         (remove nil?)
         vec)))
