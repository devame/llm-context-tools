(ns llm-context.analysis.clojure-topics
  "Extract literal framework and state identities only at clj-kondo-resolved
  call sites. This reader balances source delimiters but never reads or
  evaluates a project form."
  (:require [clojure.string :as str]
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

(defn- offset-at [source row column]
  (let [lines (str/split source #"\n" -1)]
    (when-let [line (nth lines (dec row) nil)]
      (+ (reduce + 0 (map #(inc (count %)) (take (dec row) lines)))
         (min (count line) (dec column))))))

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

(defn- balanced-end [source start]
  (let [pairs {\( \) \[ \] \{ \}}]
    (loop [index start stack [] string? false escaped? false]
      (when (< index (count source))
        (let [character (.charAt source index)]
          (cond
            string?
            (cond
              escaped? (recur (inc index) stack true false)
              (= character \\) (recur (inc index) stack true true)
              (= character \") (recur (inc index) stack false false)
              :else (recur (inc index) stack true false))

            (= character \") (recur (inc index) stack true false)
            (= character \;) (let [newline (.indexOf source "\n" index)]
                                (recur (if (neg? newline)
                                         (count source) (inc newline))
                                       stack false false))
            (contains? pairs character)
            (recur (inc index) (conj stack (get pairs character)) false false)
            (= character (peek stack))
            (let [remaining (pop stack)]
              (if (empty? remaining)
                (inc index)
                (recur (inc index) remaining false false)))
            :else (recur (inc index) stack false false)))))))

(defn- top-level-tokens [form]
  (loop [index 1 start nil stack [] string? false escaped? false result []]
    (if (>= index (dec (count form)))
      (cond-> result start (conj (subs form start (dec (count form)))))
      (let [character (.charAt form index)
            delimiter? (and (empty? stack)
                            (or (Character/isWhitespace character)
                                (= character \,)))]
        (cond
          string?
          (cond
            escaped? (recur (inc index) start stack true false result)
            (= character \\) (recur (inc index) start stack true true result)
            (= character \") (recur (inc index) start stack false false result)
            :else (recur (inc index) start stack true false result))

          (= character \")
          (recur (inc index) (or start index) stack true false result)

          (contains? #{\( \[ \{} character)
          (recur (inc index) (or start index) (conj stack character)
                 false false result)

          (and (seq stack)
               (= character ({\( \) \[ \] \{ \}} (peek stack))))
          (recur (inc index) start (pop stack) false false result)

          delimiter?
          (recur (inc index) nil stack false false
                 (cond-> result start (conj (subs form start index))))

          :else
          (recur (inc index) (or start index) stack false false result))))))

(defn- call-tokens [source reference]
  (when-let [offset (offset-at source (:source/start-line reference)
                               (:source/start-column reference))]
    (when-let [start (opening-list source offset)]
      (when-let [end (balanced-end source start)]
        (top-level-tokens (subs source start end))))))

(defn- deref-token [source reference]
  (when-let [offset (offset-at source (:source/start-line reference)
                               (:source/start-column reference))]
    (when (= \@ (.charAt source offset))
      (some-> (re-find
               #"^[A-Za-z*+!_?.<>=$%&/#-][A-Za-z0-9*+!_?.<>=$%&/#-]*"
               (subs source (inc offset)))
              first))))

(defn- literal-key? [value]
  (boolean
   (and (string? value)
        (or (re-matches #":{1,2}[A-Za-z0-9*+!_?.<>=$%&/#-]+" value)
            (re-matches #"\[\s*:{1,2}[A-Za-z0-9*+!_?.<>=$%&/#-]+(?:[\s\S]*)\]"
                        value)))))

(defn- topic-key [owner value vector-head?]
  (when (literal-key? value)
    (let [value (str/replace value #"\s+" " ")
          value (if vector-head?
                  (or (second (re-find
                               #"\[\s*(:{1,2}[A-Za-z0-9*+!_?.<>=$%&/#-]+)"
                               value))
                      value)
                  value)]
      (if (str/starts-with? value "::")
        (str ":" (namespace (symbol (:symbol/qualified-name owner)))
             "/" (subs value 2))
        value))))

(defn- atom-key [value]
  (when (and (string? value)
             (re-matches #"[A-Za-z*+!_?.<>=$%&/#-][A-Za-z0-9*+!_?.<>=$%&/#-]*"
                         value))
    (str "atom:" value)))

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

(defn extract
  "Return topic entities and exact owner-to-topic edges for resolved APIs.
  Dynamic or computed keys remain absent rather than becoming false topics."
  [file symbols references]
  (let [symbols-by-id (into {} (map (juxt :symbol/id identity)) symbols)
        source (:content file)]
    (->> references
         (mapcat
          (fn [reference]
            (let [owner (get symbols-by-id (:reference/symbol reference))
                  target (:reference/qualified-target reference)
                  tokens (call-tokens source reference)]
              (when (and owner (= :cljs (:symbol/platform owner))
                         (or tokens (contains? atom-read-apis target)))
                (cond
                  (contains? framework-apis target)
                  (let [[topic-kind edge-kind] (get framework-apis target)
                        key (topic-key owner (nth tokens 1 nil)
                                       (contains? #{:event :subscription}
                                                  topic-kind))]
                    (if key
                      (let [entity (topic owner topic-kind key)]
                        [entity (topic-edge owner entity edge-kind reference)])
                      [(dynamic-topic-reference owner edge-kind
                                                (nth tokens 1 nil)
                                                reference)]))

                  (contains? state-apis target)
                  (let [[edge-kind key-index] (get state-apis target)
                        key (topic-key owner (nth tokens (inc key-index) nil)
                                       false)]
                    (if key
                      (let [entity (topic owner :state-key key)]
                        [entity (topic-edge owner entity edge-kind reference)])
                      [(dynamic-topic-reference owner edge-kind
                                                (nth tokens
                                                     (inc key-index) nil)
                                                reference)]))

                  (contains? mutation-apis target)
                  (let [atom-identity (atom-key (nth tokens 1 nil))
                        path (topic-key owner (nth tokens 3 nil) false)
                        keys (remove nil? [atom-identity path])]
                    (mapcat
                     (fn [key]
                       (let [entity (topic owner :state-key key)]
                         [entity
                          (topic-edge owner entity :edge.kind/state-writes
                                      reference)]))
                     keys))

                  (contains? atom-read-apis target)
                  (when-let [key (atom-key (or (nth tokens 1 nil)
                                              (deref-token source reference)))]
                    (let [entity (topic owner :state-key key)]
                      [entity
                       (topic-edge owner entity :edge.kind/state-reads
                                   reference)]))

                  (contains? atom-write-apis target)
                  (when-let [key (atom-key (nth tokens 1 nil))]
                    (let [entity (topic owner :state-key key)]
                      [entity
                       (topic-edge owner entity :edge.kind/state-writes
                                   reference)])))))))
         (remove nil?)
         vec)))
