(ns llm-context.analysis.structural
  (:require [clojure.string :as str]
            [llm-context.indexer :as indexer]
            [llm-context.model.ids :as ids]
            [llm-context.parser.provider :as parser])
  (:import [java.io Closeable]
           [java.nio.charset StandardCharsets]))

(def language-profiles
  {:language/clojure
   {:symbols {}
    :calls #{"list_lit"}
    :imports #{}}
   :language/clojurescript
   {:symbols {}
    :calls #{"list_lit"}
    :imports #{}}
   :language/clojure-common
   {:symbols {}
    :calls #{"list_lit"}
    :imports #{}}
   :language/edn-data
   {:symbols {}
    :calls #{}
    :imports #{}}
   :language/janet
   {:symbols {}
    :calls #{"par_tup_lit"}
    :imports #{}}})

(def identifier-types
  #{"identifier" "property_identifier" "field_identifier" "type_identifier"
    "constant" "simple_symbol" "sym_name" "sym_lit" "namespace_name"})

(defn walk-nodes [node]
  (tree-seq #(seq (:children %)) :children node))

(defn source-text [source node]
  (let [bytes (.getBytes ^String source StandardCharsets/UTF_8)
        start (min (alength bytes) (:start-byte node))
        end (min (alength bytes) (:end-byte node))]
    (String. bytes start (max 0 (- end start)) StandardCharsets/UTF_8)))

(defn- field-node [node field]
  (first (filter #(= field (:field %)) (:children node))))

(defn- identifier-node [node]
  (first (filter #(identifier-types (:type %)) (walk-nodes node))))

(defn- named-node [node]
  (or (field-node node "name")
      (some-> (field-node node "declarator") identifier-node)
      (identifier-node node)))

(defn- range-data [node]
  (select-keys node [:source/start-line :source/start-column
                     :source/end-line :source/end-column]))

(defn- module-name [path]
  (-> path
      (str/replace #"\.[^.]+$" "")
      (str/replace #"[/\\]" ".")))

(defn- language-platform [language]
  (case language
    :language/clojure :clj
    :language/clojurescript :cljs
    :language/clojure-common :clj
    :language/janet :janet
    :language/edn-data :data))

(defn- lisp-form-head [source node]
  (when-let [head (first (:children node))]
    (source-text source head)))

(def clojure-definitions
  {"defn" :symbol.kind/function
   "defn-" :symbol.kind/function
   "defmacro" :symbol.kind/function
   "def" :symbol.kind/variable
   "defonce" :symbol.kind/variable
   "defprotocol" :symbol.kind/interface
   "defrecord" :symbol.kind/type
   "deftype" :symbol.kind/type})

(def janet-definitions
  {"def" :symbol.kind/variable
   "def-" :symbol.kind/variable
   "defglobal" :symbol.kind/variable
   "defdyn" :symbol.kind/variable
   "var" :symbol.kind/variable
   "var-" :symbol.kind/variable
   "varglobal" :symbol.kind/variable
   "defn" :symbol.kind/function
   "defn-" :symbol.kind/function
   "varfn" :symbol.kind/function
   "defmacro" :symbol.kind/function
   "defmacro-" :symbol.kind/function})

(def janet-import-heads
  #{"import" "use" "require"})

(def janet-non-call-heads
  ;; Compiler special forms and syntax-only forms do not represent runtime
  ;; calls. Macros remain call edges because user-defined macros can resolve to
  ;; graph symbols just like functions.
  #{"break" "do" "fn" "if" "quasiquote" "quote" "set" "splice"
    "unquote" "upscope" "while"})

(defn- clojure-namespace [source root fallback]
  (or (some (fn [node]
              (when (and (= "list_lit" (:type node))
                         (= "ns" (lisp-form-head source node)))
                (when-let [name-node (second (:children node))]
                  (source-text source name-node))))
            (:children root))
      fallback))

(defn- lisp-symbol-candidates [source root node-type definitions]
  (keep (fn [node]
          (when (and (= node-type (:type node))
                     (contains? definitions (lisp-form-head source node)))
            (let [name-node (second (:children node))]
              ;; Janet and Clojure both allow destructuring in some binding
              ;; forms. Only a literal symbol denotes one canonical graph
              ;; symbol; aggregate bindings need compiler expansion to split.
              (when (and name-node
                         (contains? #{"sym_lit" "sym_name" "simple_symbol"}
                                    (:type name-node)))
                {:node node
                 :name (source-text source name-node)
                 :kind (get definitions (lisp-form-head source node))}))))
        (walk-nodes root)))

(defn- symbol-candidates [language source root]
  (cond
    (contains? #{:language/clojure :language/clojurescript
                 :language/clojure-common} language)
    (lisp-symbol-candidates source root "list_lit" clojure-definitions)

    (= :language/janet language)
    (lisp-symbol-candidates source root "par_tup_lit" janet-definitions)

    :else
    (let [symbols (get-in language-profiles [language :symbols])]
      (keep (fn [node]
              (when-let [kind (get symbols (:type node))]
                (when-let [name-node (named-node node)]
                  {:node node :name (source-text source name-node) :kind kind})))
            (walk-nodes root)))))

(defn- contains-node? [outer inner]
  (and (<= (:start-byte outer) (:start-byte inner))
       (>= (:end-byte outer) (:end-byte inner))
       (not= outer inner)))

(defn- qualify-candidates [module candidates]
  (let [containers (filter #(contains? #{:symbol.kind/class :symbol.kind/interface
                                         :symbol.kind/module :symbol.kind/type}
                                       (:kind %))
                           candidates)]
    (mapv (fn [candidate]
            (let [parent (->> containers
                              (filter #(contains-node? (:node %) (:node candidate)))
                              (sort-by #(- (:end-byte (:node %))
                                           (:start-byte (:node %))))
                              first)
                  qualified (str module "/"
                                 (when parent (str (:name parent) "."))
                                 (:name candidate))]
              (assoc candidate :qualified-name qualified)))
          candidates)))

(defn- signature [source node]
  (-> (source-text source node)
      (str/split #"\r?\n" 2)
      first
      str/trim
      (#(subs % 0 (min 240 (count %))))))

(defn- canonical-symbol [file source candidate]
  (let [node (:node candidate)
        parts {:file-id (:file/id file)
               :platform (:symbol/platform candidate)
               :kind (:kind candidate)
               :qualified-name (:qualified-name candidate)
               :signature (signature source node)}]
    (merge {:entity/type :entity.type/symbol
            :symbol/id (ids/symbol-id parts)
            :symbol/name (:name candidate)
            :symbol/qualified-name (:qualified-name candidate)
            :symbol/kind (:kind candidate)
            :symbol/file (:file/id file)
            :symbol/platform (:symbol/platform candidate)
            :symbol/analyzer :tree-sitter-compat
            :symbol/signature (:signature parts)}
           (range-data node))))

(defn- owner-symbol [node candidates symbols module-symbol]
  (or (->> (map vector candidates symbols)
           (filter #(contains-node? (:node (first %)) node))
           (sort-by #(- (:end-byte (:node (first %)))
                        (:start-byte (:node (first %)))))
           first second)
      module-symbol))

(defn- target-node [node]
  (or (field-node node "function")
      (field-node node "name")
      (field-node node "source")
      (identifier-node node)
      (first (:children node))))

(defn- edge [kind from target source node]
  (let [target-text (str/trim (source-text source target))
        parts {:platform (:symbol/platform from)
               :symbol-id (:symbol/id from)
               :kind kind :target-text target-text
               :classification :unresolved
               :start-line (:source/start-line node)
               :start-column (:source/start-column node)}]
    (merge {:entity/type :entity.type/reference
            :reference/id (ids/reference-id parts)
            :reference/kind kind
            :reference/symbol (:symbol/id from)
            :reference/target-text target-text
            :reference/classification :unresolved
            :reference/evidence :tree-sitter-syntax
            :source/snippet (signature source node)}
           (range-data node))))

(defn- contains-edge [module symbol]
  (let [parts {:kind :edge.kind/contains
               :from-id (:symbol/id module)
               :to-id (:symbol/id symbol)
               :target-text (:symbol/qualified-name symbol)
               :start-line (:source/start-line symbol)
               :start-column (:source/start-column symbol)}]
    (merge {:entity/type :entity.type/edge
            :edge/id (ids/edge-id parts)
            :edge/kind :edge.kind/contains
            :edge/from (:symbol/id module)
            :edge/to (:symbol/id symbol)
            :edge/target-text (:symbol/qualified-name symbol)
            :edge/resolution :resolution/exact
            :edge/confidence 1.0
            :edge/evidence :syntactic-containment}
           (select-keys symbol [:source/start-line :source/start-column
                                :source/end-line :source/end-column]))))

(defn- clojure-call? [source node]
  (and (= "list_lit" (:type node))
       (not (contains? (conj (set (keys clojure-definitions)) "ns")
                       (lisp-form-head source node)))))

(defn- janet-call? [source node]
  (let [head (lisp-form-head source node)]
    (and (= "par_tup_lit" (:type node))
         (not (contains? janet-non-call-heads head))
         (not (contains? janet-import-heads head))
         (not (contains? janet-definitions head)))))

(defn- janet-import? [source node]
  (and (= "par_tup_lit" (:type node))
       (contains? janet-import-heads (lisp-form-head source node))))

(defn- extract-edges [language source root candidates symbols module-symbol]
  (let [{:keys [calls imports]} (get language-profiles language)
        nodes (walk-nodes root)
        call? (cond
                (contains? #{:language/clojure :language/clojurescript
                             :language/clojure-common} language)
                #(clojure-call? source %)
                (= :language/janet language) #(janet-call? source %)
                :else #(contains? calls (:type %)))
        import? (if (= :language/janet language)
                  #(janet-import? source %)
                  #(contains? imports (:type %)))]
    (concat
     (keep (fn [node]
             (when (call? node)
               (when-let [target (target-node node)]
                 (edge :edge.kind/calls
                       (owner-symbol node candidates symbols module-symbol)
                       target source node))))
           nodes)
     (keep (fn [node]
             (when (import? node)
               (when-let [target (if (= :language/janet language)
                                   (second (:children node))
                                   (target-node node))]
                 (edge :edge.kind/imports module-symbol target source node))))
           nodes))))

(defn- module-symbol [file source root module platform]
  (let [parts {:file-id (:file/id file) :platform platform
               :kind :symbol.kind/module
               :qualified-name module :signature ""
               :start-line 1 :start-column 1}]
    (merge {:entity/type :entity.type/symbol
            :symbol/id (ids/symbol-id parts)
            :symbol/name module
            :symbol/qualified-name module
            :symbol/kind :symbol.kind/module
            :symbol/file (:file/id file)
            :symbol/platform platform
            :symbol/analyzer :tree-sitter-compat}
           (range-data root))))

(defrecord StructuralIndexer [parser-provider]
  indexer/SemanticIndexer
  (index-file [_ {:keys [relative-path language content size modified-at]}]
    (let [{:keys [root]} (parser/parse-source parser-provider language content)
          file {:entity/type :entity.type/file
                :file/id (ids/file-id relative-path)
                :file/path relative-path
                :file/language language
                :file/content-hash (ids/content-hash content)
                :file/size size
                :file/modified-at modified-at}
          fallback (module-name relative-path)
          module (if (contains? #{:language/clojure :language/clojurescript
                                  :language/clojure-common} language)
                   (clojure-namespace content root fallback)
                   fallback)
          platform (language-platform language)
          module-entity (module-symbol file content root module platform)
          candidates (->> (symbol-candidates language content root)
                          (qualify-candidates module)
                          (mapv #(assoc % :symbol/platform platform)))
          symbols (mapv #(canonical-symbol file content %) candidates)
          contains-edges (mapv #(contains-edge module-entity %) symbols)
          relationships (extract-edges language content root candidates symbols module-entity)
          diagnostics (cond-> []
                        (:error? root)
                        (conj {:level :warning :kind :parse-error
                               :file relative-path}))]
      {:file file
       :entities (vec (concat [module-entity] symbols contains-edges relationships))
       :diagnostics diagnostics})))

(defn create [parser-provider]
  (->StructuralIndexer parser-provider))
