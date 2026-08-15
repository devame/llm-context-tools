(ns llm-context.intent
  "Repository-neutral natural-language query planning, structural reranking,
  and bounded traversal-root selection."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def seed-modes #{:auto :single :multi})

(def ^:private stop-terms
  #{"a" "an" "and" "are" "before" "code" "does" "for" "from" "how"
    "in" "is" "it" "metabase" "of" "or" "repository" "the" "to" "what"
    "where" "which" "with"})

(def ^:private set-language
  #"(?i)\b(?:all|list|which|what)\b.*\b(?:apis|classes|components|endpoints|files|functions|handlers|implementations|modules|namespaces|routes|symbols|tests)\b|\b(?:modules|namespaces|components)\s+(?:expose|implement|provide)\b")

(def ^:private flow-language
  #"(?i)\b(?:before|after|flow|pipeline|responsible for|end[- ]to[- ]end|how.+(?:handled|processed|validated|sent|delivered|enforced))\b")

(def ^:private concept-specs
  [{:concept :code-concept/http-endpoint
    :query-pattern #"(?i)\b(?:api|apis|endpoint|endpoints|http route|http routes)\b"
    :expanded-terms #{"api" "endpoint" "handler" "http" "ring" "route" "routes"}
    :lexical-expansions ["routes" "route" "handler" "api"]}
   {:concept :code-concept/test
    :query-pattern #"(?i)\b(?:fixture|fixtures|spec|specs|test|tests|testing)\b"
    :expanded-terms #{"fixture" "spec" "test"}
    :lexical-expansions ["test" "spec" "fixture"]}
   {:concept :code-concept/permission
    :query-pattern #"(?i)\b(?:authorization|authorize|permission|permissions|policy)\b"
    :expanded-terms #{"authorize" "permission" "perms" "policy" "read-check"}
    :lexical-expansions ["permission" "perms" "authorize" "policy"]}
   {:concept :code-concept/validation
    :query-pattern #"(?i)\b(?:coerce|coercion|validate|validated|validation)\b"
    :expanded-terms #{"check" "coerce" "schema" "validate" "validation"}
    :lexical-expansions ["validate" "validation" "coerce" "schema"]}])

(defn normalize-seed-mode [value]
  (let [mode (cond
               (keyword? value) value
               (string? value) (keyword (str/lower-case value))
               (nil? value) :auto
               :else value)]
    (if (contains? seed-modes mode)
      mode
      (throw (ex-info "Seed mode must be auto, single, or multi"
                      {:exit-code 2 :seed-mode value})))))

(defn- split-identifiers [value]
  (some-> value
          str
          (str/replace #"([a-z0-9])([A-Z])" "$1 $2")
          str/lower-case
          (str/replace #"[^a-z0-9]+" " ")
          (str/split #"\s+")
          (->> (remove str/blank?))))

(defn- singular [term]
  (cond
    (and (> (count term) 4) (str/ends-with? term "ies"))
    (str (subs term 0 (- (count term) 3)) "y")
    (and (> (count term) 3) (str/ends-with? term "s")
         (not (str/ends-with? term "ss")))
    (subs term 0 (dec (count term)))
    :else term))

(defn terms [value]
  (->> (split-identifiers value)
       (mapcat (juxt identity singular))
       (remove stop-terms)
       set))

(defn analyze
  "Create an inspectable query plan. Caller seed mode and maximum override
  conservative natural-language shape detection."
  ([query] (analyze query {}))
  ([query {:keys [seed-mode max-seeds default-max-seeds multi-candidate-count
                  default-candidate-count semantic-candidate-count]
           :or {seed-mode :auto default-max-seeds 4
                multi-candidate-count 100 default-candidate-count 50
                semantic-candidate-count 50}}]
   (let [requested-mode (normalize-seed-mode seed-mode)
         shape (cond
                 (= requested-mode :multi) :set
                 (= requested-mode :single) :lookup
                 (re-find set-language (or query "")) :set
                 (re-find flow-language (or query "")) :flow
                 :else :lookup)
         resolved-mode (if (or (= :set shape) (= :flow shape)) :multi :single)
         concepts (->> concept-specs
                       (keep #(when (re-find (:query-pattern %) (or query ""))
                                (:concept %)))
                       set)
         expanded (->> concept-specs
                       (filter #(contains? concepts (:concept %)))
                       (mapcat :expanded-terms)
                       set)
         lexical-expansions (->> concept-specs
                                 (filter #(contains? concepts (:concept %)))
                                 (mapcat :lexical-expansions)
                                 distinct vec)
         default-max-seeds (or default-max-seeds 4)
         default-candidate-count (or default-candidate-count 50)
         multi-candidate-count (or multi-candidate-count 100)
         max-seeds (max 1 (long (or max-seeds default-max-seeds)))
         max-seeds (if (= resolved-mode :single) 1
                       (if (= shape :flow) (min 2 max-seeds) max-seeds))]
     {:shape shape
      :requested-seed-mode requested-mode
      :seed-mode resolved-mode
      :max-seeds max-seeds
      :candidate-count (if (= resolved-mode :multi)
                         multi-candidate-count default-candidate-count)
      ;; Semantic top-k has a direct latency cost. Query breadth comes from
      ;; expanded lexical generation while this budget stays independent.
      :semantic-candidate-count semantic-candidate-count
      :query-terms (terms query)
      :concepts concepts
      :expanded-terms expanded
      :lexical-expansions lexical-expansions
      :reason (cond
                (not= requested-mode :auto) :explicit-seed-mode
                (= shape :set) :explicit-set-language
                (= shape :flow) :flow-language
                :else :default-lookup)})))

(defn- candidate-text [candidate]
  (str/join " " (keep candidate
                       [:name :qualified-name :file :signature :doc])))

(defn- endpoint-evidence [candidate candidate-terms]
  (let [name (str/lower-case (or (:name candidate) ""))
        doc (str/lower-case (or (:doc candidate) ""))]
    (cond-> []
      (re-find #"(?:^|[-_/])(routes?|handler)(?:$|[-_/])" name)
      (conj [:route-like-identifier 3.0])
      (or (str/includes? doc "/api/")
          (str/includes? doc "`/api")
          (contains? candidate-terms "api"))
      (conj [:api-path-or-term 2.0])
      (contains? candidate-terms "endpoint")
      (conj [:endpoint-term 1.0]))))

(defn- concept-evidence [plan candidate candidate-terms]
  (mapcat
   (fn [concept]
     (case concept
       :code-concept/http-endpoint
       (endpoint-evidence candidate candidate-terms)
       :code-concept/test
       (when (= :test (:source-role candidate)) [[:test-source 2.0]])
       :code-concept/permission
       (when (seq (set/intersection
                   candidate-terms #{"authorize" "permission" "perms" "policy" "read"}))
         [[:permission-vocabulary 1.5]])
       :code-concept/validation
       (when (seq (set/intersection
                   candidate-terms #{"check" "coerce" "schema" "validate" "validation"}))
         [[:validation-vocabulary 1.5]])
       []))
   (:concepts plan)))

(defn- qualification-reasons
  "Return structural evidence that makes a candidate a valid traversal root
  for the requested target concept. This uses graph metadata and identifier
  shape, never repository-specific path allowlists."
  [plan evidence]
  (let [reasons (set (map first evidence))
        concepts (:concepts plan)]
    (cond
      ;; A request for endpoint tests targets tests rather than route exports.
      (contains? concepts :code-concept/test)
      (set/intersection reasons #{:test-source})

      (contains? concepts :code-concept/http-endpoint)
      (set/intersection reasons #{:route-like-identifier})

      (contains? concepts :code-concept/permission)
      (set/intersection reasons #{:permission-vocabulary})

      (contains? concepts :code-concept/validation)
      (set/intersection reasons #{:validation-vocabulary})

      :else #{:unconstrained})))

(defn rerank
  "Conservatively rerank candidates using query coverage and supported
  structural concepts. Existing retrieval scores remain untouched."
  [query candidates plan]
  (let [active? (or (seq (:concepts plan)) (= :multi (:seed-mode plan)))
        enriched
        (mapv
         (fn [rank candidate]
           (let [candidate-terms (terms (candidate-text candidate))
                 direct (set/intersection (:query-terms plan) candidate-terms)
                 expanded (set/intersection (:expanded-terms plan) candidate-terms)
                 evidence (vec (concept-evidence plan candidate candidate-terms))
                 qualification (qualification-reasons plan evidence)
                 evidence-score (reduce + 0.0 (map second evidence))
                 score (+ (* 1.0 (count direct))
                          (* 0.5 (count expanded))
                          evidence-score)]
             (assoc candidate
                    :pre-rerank-rank rank
                    :intent-score score
                    :intent-qualified? (boolean (seq qualification))
                    :intent-reasons
                    (vec (concat
                          (map #(keyword (str "query-term-" %)) (sort direct))
                          (map #(keyword (str "expanded-term-" %)) (sort expanded))
                          (map first evidence))))))
         (range 1 (inc (count candidates))) candidates)
        ordered (if active?
                  (sort-by (juxt (comp not :intent-qualified?)
                                 (comp - :intent-score)
                                 :pre-rerank-rank)
                           enriched)
                  enriched)]
    {:results (mapv #(assoc %1 :post-rerank-rank %2)
                    ordered (range 1 (inc (count ordered))))
     :provider :built-in
     :status (if active? :applied :not-needed)
     :reordered? (not= (mapv :id candidates) (mapv :id ordered))}))

(defn- namespace-key [candidate]
  (some-> (:qualified-name candidate)
          (str/split #"/") first))

(defn select-seeds
  "Select bounded traversal roots. Multi-root plans prefer positive intent
  evidence and distinct files/namespaces, then fill from ranked candidates."
  [candidates plan]
  (if (= :single (:seed-mode plan))
    (vec (take 1 candidates))
    (let [limit (:max-seeds plan)
          qualifying (filter #(and (:intent-qualified? %)
                                   (pos? (double (:intent-score % 0.0))))
                             candidates)
          pool (if (seq qualifying) qualifying candidates)]
      (loop [remaining (seq pool) selected [] files #{} namespaces #{}]
        (if (or (nil? remaining) (= limit (count selected)))
          (vec selected)
          (let [candidate (first remaining)
                file (:file candidate)
                namespace (namespace-key candidate)
                diverse? (and (not (contains? files file))
                              (not (contains? namespaces namespace)))]
            (recur (next remaining)
                   (cond-> selected diverse? (conj candidate))
                   (cond-> files diverse? (conj file))
                   (cond-> namespaces diverse? (conj namespace)))))))))
