(ns llm-context.analysis.janet
  "Ordered Janet lexical and module-aware semantic analysis."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-context.analysis.effects :as effects]
            [llm-context.dependencies :as dependencies]
            [llm-context.model.ids :as ids]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.parser.provider :as parser])
  (:import [java.nio.charset StandardCharsets]))

(def catalog-resource
  (dependencies/value [:language :janet :catalog-resource]))
(def catalog-version
  (dependencies/value [:language :janet :version]))

(def catalog
  (delay (edn/read-string (slurp (io/resource catalog-resource)))))

(def import-forms #{"import" "use"})
(def scope-forms #{"do"})
(def function-forms #{"fn"})
(def sequential-binding-forms #{"let" "letn" "with-vars"})
(def single-binding-forms #{"for"})
(def quoted-forms #{"quote" "quasiquote"})

(defn- walk [node]
  (tree-seq #(seq (:children %)) :children node))

(defn- text
  [source-bytes node]
  (let [start (min (alength source-bytes) (:start-byte node))
        end (min (alength source-bytes) (:end-byte node))]
    (String. source-bytes start (max 0 (- end start))
             StandardCharsets/UTF_8)))

(defn- literal
  "Extract a Janet atom directly from the concrete syntax node. String
  delimiters are removed by byte range, not by scanning source text."
  [source-bytes node]
  (when node
    (let [value (text source-bytes node)]
      (case (:type node)
        "sym_lit" value
        "kwd_lit" value
        "str_lit" (if (and (>= (count value) 2)
                           (= \" (first value))
                           (= \" (last value)))
                    (subs value 1 (dec (count value)))
                    value)
        nil))))

(defn- child [node index]
  (nth (:children node) index nil))

(defn- form-name [source-bytes node]
  (when (= "par_tup_lit" (:type node))
    (literal source-bytes (child node 0))))

(defn- range-data [node]
  (assoc
   (select-keys node [:source/start-line :source/start-column
                      :source/end-line :source/end-column])
   :source/start-byte (:start-byte node)
   :source/end-byte (:end-byte node)))

(defn- module-name [path]
  (let [normalized (str/replace path "\\" "/")
        without-extension (if (str/ends-with? normalized ".janet")
                            (subs normalized 0 (- (count normalized) 6))
                            normalized)]
    (if (str/ends-with? without-extension "/init")
      (subs without-extension 0 (- (count without-extension) 5))
      without-extension)))

(defn- file-entity
  [{:keys [relative-path language content modified-at]}]
  {:entity/type :entity.type/file
   :file/id (ids/file-id relative-path)
   :file/path relative-path
   :file/language language
   :file/content-hash (ids/content-hash content)
   :file/size (alength (.getBytes ^String content StandardCharsets/UTF_8))
   :file/modified-at modified-at})

(defn- module-symbol [file root module]
  (merge
   {:entity/type :entity.type/symbol
    :symbol/id (ids/symbol-id
                {:platform :janet :file-id (:file/id file)
                 :kind :symbol.kind/module :qualified-name module})
    :symbol/name module
    :symbol/qualified-name module
    :symbol/kind :symbol.kind/module
    :symbol/file (:file/id file)
    :symbol/platform :janet
    :symbol/analyzer :janet-semantic
    :symbol/indexable? true}
   (range-data root)))

(defn- definition-form-node? [source-bytes node]
  (and (= "par_tup_lit" (:type node))
       (contains? (:definition-forms @catalog)
                  (form-name source-bytes node))
       (some? (child node 1))))

(defn- private-definition? [source-bytes node]
  (let [form (form-name source-bytes node)
        kind (get-in @catalog [:definition-forms form])
        candidates (drop 2 (:children node))
        metadata-nodes
        (if (contains? #{:function :macro} kind)
          (take-while #(not= "sqr_tup_lit" (:type %)) candidates)
          (butlast candidates))]
    (boolean
     (or (str/ends-with? form "-")
         (some #(= ":private" (literal source-bytes %))
               metadata-nodes)))))

(defn- symbol-kind [form]
  (case (get-in @catalog [:definition-forms form])
    :function :symbol.kind/function
    :macro :symbol.kind/macro
    :symbol.kind/variable))

(defn- definition-symbol
  [file module source-bytes node name-node]
  (let [form (form-name source-bytes node)
        name (literal source-bytes name-node)
        kind (symbol-kind form)
        qname (str module "/" name)]
    (merge
     {:entity/type :entity.type/symbol
      :symbol/id (ids/symbol-id
                  {:platform :janet :file-id (:file/id file)
                   :kind kind :qualified-name qname})
      :symbol/name name
      :symbol/qualified-name qname
      :symbol/kind kind
      :symbol/file (:file/id file)
      :symbol/platform :janet
      :symbol/analyzer :janet-semantic
      :symbol/private? (private-definition? source-bytes node)
      :symbol/macro? (= :symbol.kind/macro kind)}
     (range-data node))))

(defn- exact-edge [kind from to target evidence node]
  (merge
   {:entity/type :entity.type/edge
    :edge/id (ids/edge-id
              {:kind kind :from-id (:symbol/id from) :to-id (:symbol/id to)
               :target-text target :start-line (:source/start-line node)
               :start-column (:source/start-column node)})
    :edge/kind kind
    :edge/from (:symbol/id from)
    :edge/to (:symbol/id to)
    :edge/target-text target
    :edge/resolution :resolution/exact
    :edge/confidence 1.0
    :edge/evidence evidence}
   (range-data node)))

(defn- reference
  [kind owner target classification evidence node qualified]
  (merge
   (cond-> {:entity/type :entity.type/reference
            :reference/id
            (ids/reference-id
             {:platform :janet :symbol-id (:symbol/id owner)
              :kind kind :target-text target :classification classification
              :start-line (:source/start-line node)
              :start-column (:source/start-column node)})
            :reference/symbol (:symbol/id owner)
            :reference/kind kind
            :reference/target-text target
            :reference/classification classification
            :reference/evidence evidence}
     qualified (assoc :reference/qualified-target qualified))
   (range-data node)))

(defn- split-qualified [target]
  (let [separator (.indexOf ^String target "/")]
    (when (pos? separator)
      [(subs target 0 separator) (subs target (inc separator))])))

(defn- path-parts [path]
  (loop [start 0
         parts []]
    (let [separator (.indexOf ^String path "/" start)
          end (if (neg? separator) (count path) separator)
          part (subs path start end)
          parts (if (empty? part) parts (conj parts part))]
      (if (neg? separator)
        parts
        (recur (inc separator) parts)))))

(defn- normalize-relative [base target]
  (reduce (fn [parts part]
            (case part
              "." parts
              ".." (vec (butlast parts))
              (conj (vec parts) part)))
          (vec base)
          (path-parts target)))

(defn- resolve-import-module [current target modules]
  (when (seq target)
    (let [current-dir (butlast (path-parts current))
          candidate
          (cond
            (str/starts-with? target "/")
            (str/join "/" (normalize-relative [] (subs target 1)))

            (or (str/starts-with? target "./")
                (str/starts-with? target "../"))
            (str/join "/" (normalize-relative current-dir target))

            :else target)]
      (when (contains? modules candidate)
        candidate))))

(defn- option-value [source-bytes children option]
  (first
   (keep-indexed
    (fn [index node]
      (when (= option (literal source-bytes node))
        (literal source-bytes (nth children (inc index) nil))))
    children)))

(defn- import-records [module-name modules source-bytes node]
  (let [children (:children node)
        form (form-name source-bytes node)
        explicit-alias (option-value source-bytes children ":as")
        explicit-prefix (option-value source-bytes children ":prefix")
        target-nodes (if (= form "use")
                       (rest children)
                       [(child node 1)])]
    (into []
          (keep
           (fn [target-node]
             (when-let [target (literal source-bytes target-node)]
               (let [default-alias (last (path-parts target))
                     prefix (cond
                              (= form "use") ""
                              (some? explicit-prefix)
                              (if (str/ends-with? explicit-prefix "/")
                                (subs explicit-prefix
                                      0 (dec (count explicit-prefix)))
                                explicit-prefix)
                              explicit-alias explicit-alias
                              :else default-alias)]
                 {:node target-node
                  :target target
                  :module (resolve-import-module
                           module-name target modules)
                  :prefix prefix}))))
          target-nodes)))

(defn- binding-symbol-nodes
  "Return binding symbols from an AST pattern. Struct/table patterns bind
  values, not keyword keys. Tuple/array patterns bind every symbol except
  destructuring markers."
  [source-bytes node]
  (cond
    (nil? node) []

    (= "sym_lit" (:type node))
    (let [name (literal source-bytes node)]
      (if (contains? #{"&" "&opt" "&keys"} name) [] [node]))

    (contains? #{"struct_lit" "tbl_lit"} (:type node))
    (->> (:children node)
         (partition-all 2)
         (mapcat (fn [[key-node value-node]]
                   (if value-node
                     (binding-symbol-nodes source-bytes value-node)
                     (binding-symbol-nodes source-bytes key-node)))))

    :else
    (mapcat #(binding-symbol-nodes source-bytes %) (:children node))))

(defn- binding-symbols [source-bytes node]
  (map #(literal source-bytes %)
       (binding-symbol-nodes source-bytes node)))

(defn- definition-binding-nodes [source-bytes node]
  (let [kind (get-in @catalog
                     [:definition-forms (form-name source-bytes node)])
        binding-node (child node 1)]
    (if (contains? #{:function :macro} kind)
      (if (= "sym_lit" (:type binding-node)) [binding-node] [])
      (binding-symbol-nodes source-bytes binding-node))))

(defn- lookup-binding [frames name]
  (some #(get @% name) (rseq (vec frames))))

(defn- bind! [frames names binding]
  (doseq [name names]
    (swap! (peek frames) assoc name (assoc binding :name name))))

(defn- definition-arguments [source-bytes node]
  (when (contains? #{:function :macro}
                   (get-in @catalog
                           [:definition-forms
                            (form-name source-bytes node)]))
    (first (filter #(= "sqr_tup_lit" (:type %))
                   (drop 2 (:children node))))))

(defn- definition-body [source-bytes node]
  (let [arguments (definition-arguments source-bytes node)]
    (if arguments
      (drop-while #(not (identical? arguments %)) (drop 2 (:children node)))
      [(last (:children node))])))

(defn- call-kind [binding target]
  (if (or (= :macro (:kind binding))
          (= :symbol.kind/macro
             (:symbol/kind (:symbol binding)))
          (and (nil? binding)
               (contains? (:core-macros @catalog) target)))
    :edge.kind/macro-invokes
    :edge.kind/calls))

(declare analyze-node!)

(defn- analyze-sequence!
  [context nodes frames owner]
  (doseq [node nodes]
    (analyze-node! context node frames owner)))

(defn- analyze-function!
  [context node frames owner argument-node body function-name]
  (let [function-frame (atom {})]
    (when function-name
      (bind! (conj frames function-frame) [function-name]
             {:category :lexical :kind :function}))
    (bind! (conj frames function-frame)
           (binding-symbols (:source-bytes context) argument-node)
           {:category :lexical :kind :value})
    (analyze-sequence! context body (conj frames function-frame) owner)))

(defn- analyze-definition!
  [context node frames owner top-level-symbols]
  (let [source-bytes (:source-bytes context)
        form (form-name source-bytes node)
        kind (get-in @catalog [:definition-forms form])
        names (remove nil?
                      (if (contains? #{:function :macro} kind)
                        [(literal source-bytes (child node 1))]
                        (binding-symbols source-bytes (child node 1))))
        binding (fn [name]
                  (if-let [top-level-symbol
                           (get top-level-symbols name)]
                    {:category :project :kind kind
                     :symbol top-level-symbol :name name}
                    {:category :lexical :kind kind :name name}))
        argument-node (definition-arguments source-bytes node)]
    ;; Named function and macro bindings are recursive. Value definitions only
    ;; become visible after their initializer has been evaluated.
    (when (contains? #{:function :macro} kind)
      (doseq [name names]
        (bind! frames [name] (binding name))))
    (if argument-node
      (analyze-function! context node frames owner argument-node
                         (rest (definition-body source-bytes node))
                         nil)
      (when-let [value-node (last (:children node))]
        (analyze-node! context value-node frames owner)))
    (when-not (contains? #{:function :macro} kind)
      (doseq [name names]
        (bind! frames [name] (binding name))))))

(defn- bind-import!
  [context frames import]
  (let [{:keys [module prefix]} import
        exports (get-in context [:exports module])]
    (if (= "" prefix)
      (if module
        (doseq [[name symbol] exports]
          (bind! frames [name]
                 {:category :project :kind
                  (if (= :symbol.kind/macro (:symbol/kind symbol))
                    :macro :value)
                  :symbol symbol}))
        nil)
      (bind! frames [prefix]
             (if module
               {:category :project-alias :module module}
               {:category :external-alias})))))

(defn- analyze-import!
  [context node frames owner]
  (let [source-bytes (:source-bytes context)
        imports (import-records (:module-name context) (:modules context)
                                source-bytes node)]
    (doseq [{:keys [target module node] :as import} imports]
      (swap! (:facts context) conj
             (if module
               (exact-edge :edge.kind/imports owner
                           (get-in context [:modules module])
                           target :janet-module-resolution node)
               (reference :edge.kind/imports owner target :external
                          :janet-module-resolution node target)))
      (bind-import! context frames import))))

(defn- emit-call! [context node frames owner target]
  (let [binding (lookup-binding frames target)
        qualified-parts (split-qualified target)
        [prefix simple] qualified-parts
        alias-binding (when prefix (lookup-binding frames prefix))
        imported-symbol
        (when (= :project-alias (:category alias-binding))
          (get-in context [:exports (:module alias-binding) simple]))
        resolved (or binding
                     (when imported-symbol
                       {:category :project :symbol imported-symbol
                        :kind (if (= :symbol.kind/macro
                                     (:symbol/kind imported-symbol))
                                :macro :value)}))
        kind (call-kind resolved target)
        qualified (when alias-binding
                    (if-let [module (:module alias-binding)]
                      (str module "/" simple)
                      target))]
    (swap! (:facts context) conj
           (case (:category resolved)
             :project
             (exact-edge kind owner (:symbol resolved) target
                         :janet-lexical-module-resolution node)

             :lexical
             (reference kind owner target :dynamic
                        :janet-lexical-scope node nil)

             :project-alias
             ;; Calling a module table itself is dynamic, while member calls
             ;; are handled through imported-symbol above.
             (reference kind owner target :dynamic
                        :janet-lexical-scope node nil)

             :external-alias
             (reference kind owner target :external
                        :janet-import-resolution node target)

             (cond
               (contains? #{:lexical :project}
                          (:category alias-binding))
               (reference kind owner target :dynamic
                          :janet-lexical-scope node nil)

               (and alias-binding (nil? imported-symbol))
               (reference kind owner target
                          (if (= :external-alias (:category alias-binding))
                            :external :unresolved)
                          :janet-import-resolution node qualified)

               (contains? (:core @catalog) target)
               (reference kind owner target :external
                          :janet-1.41.2-catalog node target)

               prefix
               (reference kind owner target :external
                          :janet-external-module node target)

               :else
               (reference kind owner target :unresolved
                          :janet-lexical-module-resolution node
                          (str (:module-name context) "/" target)))))))

(defn- analyze-binding-vector!
  [context binding-node frames owner]
  (doseq [[pattern value] (partition-all 2 (:children binding-node))]
    (when value
      (analyze-node! context value frames owner))
    (bind! frames (binding-symbols (:source-bytes context) pattern)
           {:category :lexical :kind :value})))

(defn- analyze-node!
  [context node frames owner]
  (let [source-bytes (:source-bytes context)
        form (form-name source-bytes node)
        children (:children node)]
    (cond
      (contains? quoted-forms form)
      nil

      (contains? import-forms form)
      (analyze-import! context node frames owner)

      (definition-form-node? source-bytes node)
      (analyze-definition! context node frames owner nil)

      (contains? function-forms form)
      (let [named? (and (= "sym_lit" (:type (child node 1)))
                        (= "sqr_tup_lit" (:type (child node 2))))
            arguments (child node (if named? 2 1))
            function-name (when named?
                            (literal source-bytes (child node 1)))]
        (analyze-function! context node frames owner arguments
                           (drop (if named? 3 2) children)
                           function-name))

      (contains? scope-forms form)
      (analyze-sequence! context (rest children)
                         (conj frames (atom {})) owner)

      (= "if" form)
      (do
        (when-let [condition (child node 1)]
          (analyze-node! context condition frames owner))
        (doseq [branch (drop 2 children)]
          (analyze-node! context branch
                         (conj frames (atom {})) owner)))

      (= "while" form)
      (do
        (when-let [condition (child node 1)]
          (analyze-node! context condition frames owner))
        (analyze-sequence! context (drop 2 children)
                           (conj frames (atom {})) owner))

      (contains? sequential-binding-forms form)
      (let [scope-frames (conj frames (atom {}))
            bindings (child node 1)]
        (when (= "sqr_tup_lit" (:type bindings))
          (analyze-binding-vector! context bindings scope-frames owner))
        (analyze-sequence! context (drop 2 children) scope-frames owner))

      (contains? #{"each" "eachk" "eachp"} form)
      (let [binding-node (child node 1)
            value-node (child node 2)
            body (drop 3 children)
            scope-frames (conj frames (atom {}))]
        (when value-node
          (analyze-node! context value-node frames owner))
        (bind! scope-frames (binding-symbols source-bytes binding-node)
               {:category :lexical :kind :value})
        (analyze-sequence! context body scope-frames owner))

      (= "loop" form)
      (let [scope-frames (conj frames (atom {}))
            bindings (child node 1)]
        (when (= "sqr_tup_lit" (:type bindings))
          (loop [remaining (:children bindings)]
            (when (seq remaining)
              (let [pattern-or-modifier (first remaining)]
                (if (= "kwd_lit" (:type pattern-or-modifier))
                  (let [value (second remaining)]
                    (when value
                      (if (and (= ":let"
                                  (literal source-bytes pattern-or-modifier))
                               (= "sqr_tup_lit" (:type value)))
                        (analyze-binding-vector!
                         context value scope-frames owner)
                        (analyze-node!
                         context value scope-frames owner)))
                    (recur (nnext remaining)))
                  (let [value (nth remaining 2 nil)]
                    (when value
                      (analyze-node! context value scope-frames owner))
                    (bind! scope-frames
                           (binding-symbols source-bytes pattern-or-modifier)
                           {:category :lexical :kind :value})
                    (recur (drop 3 remaining))))))))
        (analyze-sequence! context (drop 2 children) scope-frames owner))

      (= "with" form)
      (let [scope-frames (conj frames (atom {}))
            binding-form (child node 1)
            [pattern constructor destructor] (:children binding-form)]
        (when constructor
          (analyze-node! context constructor frames owner))
        (when destructor
          (analyze-node! context destructor frames owner))
        (bind! scope-frames (binding-symbols source-bytes pattern)
               {:category :lexical :kind :value})
        (analyze-sequence! context (drop 2 children) scope-frames owner))

      (contains? single-binding-forms form)
      (let [scope-frames (conj frames (atom {}))]
        (analyze-sequence! context (take 2 (drop 2 children)) frames owner)
        (bind! scope-frames
               (binding-symbols source-bytes (child node 1))
               {:category :lexical :kind :value})
        (analyze-sequence! context (drop 4 children) scope-frames owner))

      (= "par_tup_lit" (:type node))
      (do
        (when (and (seq form)
                   (not (contains? (:special-forms @catalog) form))
                   (not (contains? (:binding-forms @catalog) form))
                   (not (contains? (:definition-forms @catalog) form))
                   (not (contains? import-forms form)))
          (emit-call! context node frames owner form))
        (analyze-sequence! context
                           (if form (rest children) children)
                           frames owner))

      :else
      (analyze-sequence! context children frames owner))))

(defn- parse-input [parser-provider input]
  (let [{:keys [root]}
        (parser/parse-source parser-provider :language/janet (:content input))
        file (file-entity input)
        module-name (module-name (:relative-path input))
        module (module-symbol file root module-name)
        source-bytes (.getBytes ^String (:content input)
                                StandardCharsets/UTF_8)
        parse-error? (boolean
                      (some #(or (:error? %) (:missing? %)) (walk root)))
        definition-nodes
        (if parse-error?
          []
          (filterv #(definition-form-node? source-bytes %)
                   (:children root)))
        occurrences
        (mapcat
         (fn [node]
           (map (fn [name-node]
                  {:node node
                   :name-node name-node
                   :name (literal source-bytes name-node)})
                (definition-binding-nodes source-bytes node)))
         definition-nodes)
        ;; Janet redefinition replaces the module binding. Persist one stable
        ;; symbol per effective name and map all earlier forms to that identity
        ;; while preserving ordered visibility during traversal.
        effective-occurrences
        (->> occurrences
             (reduce (fn [by-name occurrence]
                       (assoc by-name (:name occurrence) occurrence))
                     {})
             vals
             (sort-by (juxt (comp :start-byte :node)
                            (comp :start-byte :name-node))))
        definitions
        (mapv
         (fn [{:keys [node name-node]}]
           {:node node
            :name-node name-node
            :symbol (definition-symbol file module-name source-bytes
                                       node name-node)})
         effective-occurrences)
        symbol-by-name
        (into {} (map (juxt (comp :symbol/name :symbol) :symbol))
              definitions)]
    {:input input :file file :root root :source-bytes source-bytes
     :module-name module-name :module module :definitions definitions
     :definition-by-start
     (into {}
           (map (fn [node]
                  [(:start-byte node)
                   {:node node
                    :symbols
                    (into {}
                          (map (fn [name-node]
                                 (let [name (literal source-bytes name-node)]
                                   [name (get symbol-by-name name)])))
                          (definition-binding-nodes
                           source-bytes node))}]))
           definition-nodes)
     :parse-error? parse-error?}))

(defn- module-exports [definitions]
  (->> definitions
       (reduce (fn [bindings {:keys [symbol]}]
                 (assoc bindings (:symbol/name symbol) symbol))
               {})
       (remove (comp :symbol/private? val))
       (into {})))

(defn- analyze-valid-file
  [{:keys [file root source-bytes module-name module definitions
           definition-by-start]}
   modules exports]
  (let [facts (atom [])
        module-frame (atom {})
        frames [module-frame]
        base-context {:source-bytes source-bytes
                      :module-name module-name
                      :modules modules
                      :exports exports
                      :facts facts}]
    (doseq [node (:children root)]
      (if-let [{:keys [symbols]} (get definition-by-start (:start-byte node))]
        (let [owner (if (= 1 (count symbols))
                      (first (vals symbols))
                      module)]
          (analyze-definition! base-context node frames owner symbols))
        (analyze-node! base-context node frames module)))
    (let [base-facts
          (vec (concat
                [module]
                (map :symbol definitions)
                (map #(exact-edge :edge.kind/contains module (:symbol %)
                                  (:symbol/qualified-name (:symbol %))
                                  :janet-definition (:node %))
                     definitions)
                @facts))
          effect-facts
          (->> base-facts
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
                       :source/end-column (:source/end-column reference)
                       :source/start-byte (:source/start-byte reference)
                       :source/end-byte (:source/end-byte reference)}))
               (effects/analyze :language/janet))]
      {:file file
       :entities (into base-facts effect-facts)
       :diagnostics []
       :status :ok
       :preserve? false})))

(defn analyze
  "Analyze Janet files together. Module symbols are direct top-level
  definitions only; nested definitions and destructured bindings remain in the
  ordered lexical environment used to classify references."
  [project files]
  (with-open [parser-provider (jtreesitter/open project)]
    (let [parsed (mapv #(parse-input parser-provider %) files)
          valid (remove :parse-error? parsed)
          modules (into {} (map (juxt :module-name :module)) valid)
          exports (into {}
                        (map (fn [{:keys [module-name definitions]}]
                               [module-name (module-exports definitions)]))
                        valid)
          outputs
          (mapv
           (fn [{:keys [input file parse-error?] :as parsed-file}]
             (if parse-error?
               {:file file
                :entities []
                :diagnostics
                [{:level :warning
                  :kind :parse-error
                  :file (:relative-path input)
                  :message
                  "Janet parse failed; preserving the last complete semantic snapshot"}]
                :status :malformed
                :preserve? true}
               (analyze-valid-file parsed-file modules exports)))
           parsed)]
      {:catalog-version catalog-version
       :outputs outputs
       :diagnostics []})))
