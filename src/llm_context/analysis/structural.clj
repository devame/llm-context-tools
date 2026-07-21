(ns llm-context.analysis.structural
  (:require [clojure.string :as str]
            [llm-context.indexer :as indexer]
            [llm-context.model.ids :as ids]
            [llm-context.parser.provider :as parser])
  (:import [java.io Closeable]
           [java.nio.charset StandardCharsets]))

(def language-profiles
  {:language/javascript
   {:symbols {"function_declaration" :symbol.kind/function
              "generator_function_declaration" :symbol.kind/function
              "method_definition" :symbol.kind/method
              "class_declaration" :symbol.kind/class}
    :calls #{"call_expression" "new_expression"}
    :imports #{"import_statement"}}
   :language/typescript
   {:symbols {"function_declaration" :symbol.kind/function
              "generator_function_declaration" :symbol.kind/function
              "method_definition" :symbol.kind/method
              "class_declaration" :symbol.kind/class
              "interface_declaration" :symbol.kind/interface
              "type_alias_declaration" :symbol.kind/type}
    :calls #{"call_expression" "new_expression"}
    :imports #{"import_statement"}}
   :language/python
   {:symbols {"function_definition" :symbol.kind/function
              "class_definition" :symbol.kind/class}
    :calls #{"call"}
    :imports #{"import_statement" "import_from_statement"}}
   :language/java
   {:symbols {"method_declaration" :symbol.kind/method
              "constructor_declaration" :symbol.kind/method
              "class_declaration" :symbol.kind/class
              "interface_declaration" :symbol.kind/interface
              "enum_declaration" :symbol.kind/type}
    :calls #{"method_invocation" "object_creation_expression"}
    :imports #{"import_declaration"}}
   :language/go
   {:symbols {"function_declaration" :symbol.kind/function
              "method_declaration" :symbol.kind/method
              "type_declaration" :symbol.kind/type}
    :calls #{"call_expression"}
    :imports #{"import_declaration"}}
   :language/rust
   {:symbols {"function_item" :symbol.kind/function
              "struct_item" :symbol.kind/type
              "enum_item" :symbol.kind/type
              "trait_item" :symbol.kind/interface
              "impl_item" :symbol.kind/type}
    :calls #{"call_expression"}
    :imports #{"use_declaration"}}
   :language/c
   {:symbols {"function_definition" :symbol.kind/function
              "struct_specifier" :symbol.kind/type
              "enum_specifier" :symbol.kind/type}
    :calls #{"call_expression"}
    :imports #{"preproc_include"}}
   :language/cpp
   {:symbols {"function_definition" :symbol.kind/function
              "class_specifier" :symbol.kind/class
              "struct_specifier" :symbol.kind/type
              "enum_specifier" :symbol.kind/type}
    :calls #{"call_expression"}
    :imports #{"preproc_include"}}
   :language/ruby
   {:symbols {"method" :symbol.kind/method
              "singleton_method" :symbol.kind/method
              "class" :symbol.kind/class
              "module" :symbol.kind/module}
    :calls #{"call" "method_call"}
    :imports #{}}
   :language/php
   {:symbols {"function_definition" :symbol.kind/function
              "method_declaration" :symbol.kind/method
              "class_declaration" :symbol.kind/class
              "interface_declaration" :symbol.kind/interface}
    :calls #{"function_call_expression" "member_call_expression"
             "scoped_call_expression"}
    :imports #{"namespace_use_declaration" "require_expression"
               "include_expression"}}
   :language/bash
   {:symbols {"function_definition" :symbol.kind/function}
    :calls #{"command"}
    :imports #{}}
   :language/clojure
   {:symbols {}
    :calls #{"list_lit"}
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

(defn- clojure-form-head [source node]
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

(defn- clojure-namespace [source root fallback]
  (or (some (fn [node]
              (when (and (= "list_lit" (:type node))
                         (= "ns" (clojure-form-head source node)))
                (when-let [name-node (second (:children node))]
                  (source-text source name-node))))
            (:children root))
      fallback))

(defn- symbol-candidates [language source root]
  (if (= :language/clojure language)
    (keep (fn [node]
            (when (and (= "list_lit" (:type node))
                       (contains? clojure-definitions (clojure-form-head source node)))
              (let [name-node (second (:children node))]
                {:node node
                 :name (source-text source name-node)
                 :kind (get clojure-definitions (clojure-form-head source node))})))
          (walk-nodes root))
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
               :kind (:kind candidate)
               :qualified-name (:qualified-name candidate)
               :signature (signature source node)
               :start-line (:source/start-line node)
               :start-column (:source/start-column node)}]
    (merge {:entity/type :entity.type/symbol
            :symbol/id (ids/symbol-id parts)
            :symbol/name (:name candidate)
            :symbol/qualified-name (:qualified-name candidate)
            :symbol/kind (:kind candidate)
            :symbol/file (:file/id file)
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
        parts {:kind kind :from-id (:symbol/id from) :target-text target-text
               :start-line (:source/start-line node)
               :start-column (:source/start-column node)}]
    (merge {:entity/type :entity.type/edge
            :edge/id (ids/edge-id parts)
            :edge/kind kind
            :edge/from (:symbol/id from)
            :edge/target-text target-text
            :edge/resolution :resolution/unresolved
            :edge/confidence 0.0
            :source/snippet (signature source node)}
           (range-data node))))

(defn- contains-edge [module symbol]
  (let [parts {:kind :edge.kind/contains
               :from-id (:symbol/id module)
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
            :edge/confidence 1.0}
           (select-keys symbol [:source/start-line :source/start-column
                                :source/end-line :source/end-column]))))

(defn- clojure-call? [source node]
  (and (= "list_lit" (:type node))
       (not (contains? (conj (set (keys clojure-definitions)) "ns")
                       (clojure-form-head source node)))))

(defn- extract-edges [language source root candidates symbols module-symbol]
  (let [{:keys [calls imports]} (get language-profiles language)
        nodes (walk-nodes root)
        call? (if (= :language/clojure language)
                #(clojure-call? source %)
                #(contains? calls (:type %)))]
    (concat
     (keep (fn [node]
             (when (call? node)
               (when-let [target (target-node node)]
                 (edge :edge.kind/calls
                       (owner-symbol node candidates symbols module-symbol)
                       target source node))))
           nodes)
     (keep (fn [node]
             (when (contains? imports (:type node))
               (when-let [target (target-node node)]
                 (edge :edge.kind/imports module-symbol target source node))))
           nodes))))

(defn- module-symbol [file source root module]
  (let [parts {:file-id (:file/id file) :kind :symbol.kind/module
               :qualified-name module :signature ""
               :start-line 1 :start-column 1}]
    (merge {:entity/type :entity.type/symbol
            :symbol/id (ids/symbol-id parts)
            :symbol/name module
            :symbol/qualified-name module
            :symbol/kind :symbol.kind/module
            :symbol/file (:file/id file)}
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
          module (if (= :language/clojure language)
                   (clojure-namespace content root fallback)
                   fallback)
          module-entity (module-symbol file content root module)
          candidates (qualify-candidates module (symbol-candidates language content root))
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
