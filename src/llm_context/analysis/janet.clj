(ns llm-context.analysis.janet
  "Two-pass Janet lexical and module-aware semantic analysis."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [llm-context.analysis.effects :as effects]
            [llm-context.model.ids :as ids]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.parser.provider :as parser])
  (:import [java.nio.charset StandardCharsets]))

(def catalog-resource "llm_context/janet/catalog-1.41.2.edn")
(def catalog-version "1.41.2")

(def catalog
  (delay (edn/read-string (slurp (io/resource catalog-resource)))))

(defn- walk [node]
  (tree-seq #(seq (:children %)) :children node))

(defn- text [source node]
  (let [bytes (.getBytes ^String source StandardCharsets/UTF_8)
        start (min (alength bytes) (:start-byte node))
        end (min (alength bytes) (:end-byte node))]
    (String. bytes start (max 0 (- end start)) StandardCharsets/UTF_8)))

(defn- head [source node]
  (when-let [child (first (:children node))]
    (text source child)))

(defn- range-data [node]
  (select-keys node [:source/start-line :source/start-column
                     :source/end-line :source/end-column]))

(defn- module-name [path]
  (-> path
      (str/replace #"\.janet$" "")
      (str/replace #"/init$" "")
      (str/replace "\\" "/")))

(defn- file-entity
  [{:keys [relative-path language content size modified-at]}]
  {:entity/type :entity.type/file
   :file/id (ids/file-id relative-path)
   :file/path relative-path
   :file/language language
   :file/content-hash (ids/content-hash content)
   :file/size size
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
    :symbol/analyzer :janet-semantic}
   (range-data root)))

(defn- definition-node? [source node]
  (and (= "par_tup_lit" (:type node))
       (contains? (:definition-forms @catalog) (head source node))
       (some? (second (:children node)))))

(defn- definition-symbol [file module source node]
  (let [form (head source node)
        name-node (second (:children node))
        name (text source name-node)
        catalog-kind (get-in @catalog [:definition-forms form])
        kind (case catalog-kind
               :function :symbol.kind/function
               :macro :symbol.kind/macro
               :symbol.kind/variable)
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
      :symbol/private? (str/ends-with? form "-")
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

(defn- contains-node? [outer inner]
  (and (<= (:start-byte outer) (:start-byte inner))
       (>= (:end-byte outer) (:end-byte inner))))

(defn- owner [node definitions module]
  (or (->> definitions
           (filter #(contains-node? (:node %) node))
           (sort-by #(- (:end-byte (:node %))
                        (:start-byte (:node %))))
           first :symbol)
      module))

(defn- import-record [file source node]
  (let [children (:children node)
        target (when-let [child (second children)] (text source child))
        as-index (first (keep-indexed
                         #(when (= ":as" (text source %2)) %1)
                         children))
        alias (if as-index
                (when-let [child (nth children (inc as-index) nil)]
                  (text source child))
                (some-> target (str/split #"/") last))]
    {:file file :node node :target target :alias alias}))

(defn- resolve-import-module [current target modules]
  (let [current-dir (some-> current (str/split #"/") butlast)
        relative (when (str/starts-with? target ".")
                   (->> (concat current-dir (str/split target #"/"))
                        (reduce (fn [parts part]
                                  (case part
                                    "." parts
                                    ".." (vec (butlast parts))
                                    (conj (vec parts) part)))
                                [])
                        (str/join "/")))
        candidates (remove nil?
                           [target relative (str target "/init")
                            (when relative (str relative "/init"))])]
    (some #(when (contains? modules %) %) candidates)))

(defn- binding-names [source definition-node]
  (let [argument-node (nth (:children definition-node) 2 nil)
        args (when (= "sqr_tup_lit" (:type argument-node))
               (map #(text source %) (filter (comp #{"sym_lit"} :type)
                                             (walk argument-node))))
        binding-values
        (mapcat
         (fn [node]
           (when (and (= "par_tup_lit" (:type node))
                      (contains? (:binding-forms @catalog) (head source node)))
             (let [bindings (second (:children node))]
               (when (= "sqr_tup_lit" (:type bindings))
                 (->> (:children bindings)
                      (take-nth 2)
                      (filter #(= "sym_lit" (:type %)))
                      (map #(text source %)))))))
         (walk definition-node))]
    (set (concat args binding-values))))

(defn- call-node? [source node]
  (let [form (head source node)]
    (and (= "par_tup_lit" (:type node))
         (seq form)
         (not (contains? (:special-forms @catalog) form))
         (not (contains? (:binding-forms @catalog) form))
         (not (contains? (:definition-forms @catalog) form))
         (not (contains? #{"import" "use" "require"} form)))))

(defn analyze
  "Analyze Janet files together so imports and project calls resolve exactly."
  [project files]
  (with-open [parser-provider (jtreesitter/open project)]
    (let [parsed
          (mapv
           (fn [input]
             (let [{:keys [root]}
                   (parser/parse-source parser-provider
                                        :language/janet (:content input))
                   file (file-entity input)
                   module-name (module-name (:relative-path input))
                   module (module-symbol file root module-name)
                   parse-error? (boolean
                                 (some #(or (:error? %) (:missing? %))
                                       (walk root)))
                   definitions
                   (mapv (fn [node]
                           {:node node
                            :symbol (definition-symbol
                                     file module-name (:content input) node)})
                         (filter #(definition-node? (:content input) %)
                                 (walk root)))]
               {:input input :file file :root root :module-name module-name
                :module module :definitions definitions
                :parse-error? parse-error?
                :imports
                (mapv #(import-record file (:content input) %)
                      (filter (fn [node]
                                (and (= "par_tup_lit" (:type node))
                                     (contains? #{"import" "use" "require"}
                                                (head (:content input) node))))
                              (walk root)))}))
           files)
          modules (into {} (map (juxt :module-name :module)) parsed)
          definitions-by-qualified
          (reduce
           (fn [result {:keys [definitions]}]
             (reduce (fn [r {:keys [symbol]}]
                       (update r (:symbol/qualified-name symbol)
                               (fnil conj []) symbol))
                     result definitions))
           {} parsed)
          outputs
          (mapv
           (fn [{:keys [input file root module-name module parse-error?
                        definitions imports]}]
             (let [aliases
                   (into {}
                         (keep (fn [{:keys [target alias]}]
                                 (when-let [resolved
                                            (resolve-import-module
                                             module-name target modules)]
                                   [alias resolved])))
                         imports)
                   import-facts
                   (mapv
                    (fn [{:keys [target node]}]
                      (if-let [resolved
                               (resolve-import-module
                                module-name target modules)]
                        (exact-edge :edge.kind/imports module
                                    (get modules resolved) target
                                    :janet-module-resolution node)
                        (reference :edge.kind/imports module target :external
                                   :janet-module-resolution node target)))
                    imports)
                   contains
                   (mapv #(exact-edge
                           :edge.kind/contains module (:symbol %)
                           (:symbol/qualified-name (:symbol %))
                           :janet-definition (:node %))
                         definitions)
                   locals-by-owner
                   (into {}
                         (map (fn [{:keys [node symbol]}]
                                [(:symbol/id symbol)
                                 (binding-names (:content input) node)]))
                         definitions)
                   call-facts
                   (keep
                    (fn [node]
                      (when (call-node? (:content input) node)
                        (let [target (head (:content input) node)
                              owner (owner node definitions module)
                              [prefix simple]
                              (if (str/includes? target "/")
                                (str/split target #"/" 2)
                                [nil target])
                              target-module
                              (if prefix (get aliases prefix) module-name)
                              qualified (when target-module
                                          (str target-module "/" simple))
                              candidates
                              (get definitions-by-qualified qualified)
                              kind (if (= :symbol.kind/macro
                                          (:symbol/kind (first candidates)))
                                     :edge.kind/macro-invokes
                                     :edge.kind/calls)]
                          (cond
                            (contains? (get locals-by-owner
                                            (:symbol/id owner) #{})
                                       target)
                            (reference kind owner target :dynamic
                                       :janet-lexical-scope node nil)

                            (= 1 (count candidates))
                            (exact-edge kind owner (first candidates) target
                                        :janet-lexical-module-resolution node)

                            (> (count candidates) 1)
                            (reference kind owner target :ambiguous
                                       :janet-lexical-module-resolution
                                       node qualified)

                            (or (contains? (:core @catalog) target)
                                (and prefix (not (contains? aliases prefix))))
                            (reference kind owner target :external
                                       :janet-1.41.2-catalog node
                                       (when prefix target))

                            :else
                            (reference kind owner target :unresolved
                                       :janet-lexical-module-resolution
                                       node qualified)))))
                    (walk root))
                   facts (vec (concat
                               [module]
                               (map :symbol definitions)
                               contains import-facts call-facts))
                   effect-facts
                   (->> facts
                        (filter #(and (= :entity.type/reference
                                         (:entity/type %))
                                      (= :external
                                         (:reference/classification %))))
                        (map (fn [r]
                               {:edge/kind (:reference/kind r)
                                :edge/from (:reference/symbol r)
                                :edge/target-text
                                (or (:reference/qualified-target r)
                                    (:reference/target-text r))
                                :source/snippet (:reference/target-text r)
                                :source/start-line (:source/start-line r)
                                :source/start-column (:source/start-column r)
                                :source/end-line (:source/end-line r)
                                :source/end-column (:source/end-column r)}))
                        (effects/analyze :language/janet))]
               {:file file
                :entities (into facts effect-facts)
                :diagnostics
                (cond-> []
                  parse-error?
                  (conj {:level :warning :kind :parse-error
                         :file (:relative-path input)}))
                :preserve? parse-error?}))
           parsed)]
      {:catalog-version catalog-version
       :outputs outputs
       :diagnostics []})))
