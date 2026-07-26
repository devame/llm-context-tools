(ns llm-context.analysis.janet-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.janet :as janet]
            [llm-context.project :as project])
  (:import [java.nio.file Files]))

(defn- input [root relative content]
  (let [path (.resolve root relative)]
    (Files/createDirectories
     (.getParent path)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str path) content)
    {:path path :relative-path relative :language :language/janet
     :content content
     :size (count (.getBytes content
                             java.nio.charset.StandardCharsets/UTF_8))
     :modified-at 1}))

(defn- entities [result type]
  (filter #(= type (:entity/type %))
          (mapcat :entities (:outputs result))))

(deftest janet-analysis-resolves-modules-core-macros-and-lexical-calls
  (let [root (Files/createTempDirectory
              "llm-context-janet-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        files [(input root "lib/names.janet"
                      "(defn format-name [x] (string/ascii-lower x))")
               (input root "main.janet"
                      (str "(import ./lib/names :as names)\n"
                           "(defmacro traced [body] body)\n"
                           "(defn run [x]\n"
                           " (let [f names/format-name]\n"
                           "  (print (f (names/format-name x)))))"))]
        result (janet/analyze (project/context (str root)) files)
        entities (mapcat :entities (:outputs result))
        edges (filter #(= :entity.type/edge (:entity/type %)) entities)
        refs (filter #(= :entity.type/reference (:entity/type %)) entities)]
    (is (= "1.41.2" (:catalog-version result)))
    (is (some #(and (= :edge.kind/imports (:edge/kind %))
                    (= "./lib/names" (:edge/target-text %))) edges))
    (is (some #(and (= :edge.kind/calls (:edge/kind %))
                    (= "names/format-name" (:edge/target-text %))) edges))
    (is (some #(and (= :dynamic (:reference/classification %))
                    (= "f" (:reference/target-text %))) refs))
    (is (some #(and (= :external (:reference/classification %))
                    (= "print" (:reference/target-text %))) refs))
    (is (not-any? #(contains? #{"defn" "defmacro" "let"}
                              (:reference/target-text %))
                  refs))
    (is (every? #(and (nat-int? (:source/start-byte %))
                      (nat-int? (:source/end-byte %)))
                (filter #(contains? #{:entity.type/symbol
                                      :entity.type/edge
                                      :entity.type/reference}
                                    (:entity/type %))
                        entities)))))

(deftest nested-definitions-remain-lexical-and-keep-their-module-owner
  (let [root (Files/createTempDirectory
              "llm-context-janet-nested-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        result
        (janet/analyze
         (project/context (str root))
         [(input root "main.janet"
                 (str "(defn outer [x]\n"
                      "  (defn helper [y] (+ x y))\n"
                      "  (helper x))\n"
                      "(defn another [x]\n"
                      "  (do (defn helper [y] (+ x y)) (helper x)))\n"
                      "(defn scoped []\n"
                      "  (if true (defn branch-only [] nil))\n"
                      "  (while false (defn loop-only [] nil))\n"
                      "  ((fn recur [x] (recur x)) 1)\n"
                      "  (branch-only)\n"
                      "  (loop-only))\n"
                      "(helper 1)\n"))])
        symbols (entities result :entity.type/symbol)
        refs (entities result :entity.type/reference)]
    (is (= #{"main" "outer" "another" "scoped"}
           (set (map :symbol/name symbols))))
    (is (= [:dynamic :dynamic]
           (mapv :reference/classification
                 (butlast
                  (filter #(= "helper" (:reference/target-text %)) refs)))))
    (is (= :unresolved
           (:reference/classification
            (last (filter #(= "helper" (:reference/target-text %)) refs)))))
    (is (= {:dynamic #{"helper" "recur"}
            :unresolved #{"branch-only" "loop-only" "helper"}}
           (->> refs
                (filter #(contains? #{"recur" "branch-only"
                                      "loop-only" "helper"}
                                    (:reference/target-text %)))
                (group-by :reference/classification)
                (reduce-kv
                 (fn [result classification classified]
                   (assoc result classification
                          (set (map :reference/target-text classified))))
                 {}))))))

(deftest sequential-module-visibility-and-lexical-shadowing-are-exact
  (let [root (Files/createTempDirectory
              "llm-context-janet-shadow-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        result
        (janet/analyze
         (project/context (str root))
         [(input root "main.janet"
                 (str "(defn before [] (later))\n"
                      "(defn target [] nil)\n"
                      "(defn shadow [target] (target))\n"
                      "(defn later [] (target))\n"
                      "(def values [(target)])\n"
                      "(def [alias ignored] [target nil])\n"
                      "(defn via-destructure [] (alias))\n"
                      "(defn local-destructure [pair]\n"
                      "  (def [f _] pair)\n"
                      "  (f))\n"
                      "(defn shadow-core [when] (when true))\n"))])
        symbols (entities result :entity.type/symbol)
        refs (entities result :entity.type/reference)
        edges (entities result :entity.type/edge)
        target-refs (filter #(= "target" (:reference/target-text %)) refs)]
    (is (some #(and (= "later" (:reference/target-text %))
                    (= :unresolved (:reference/classification %)))
              refs))
    (is (= [:dynamic] (mapv :reference/classification target-refs)))
    (is (= 2
           (count
            (filter #(and (= :edge.kind/calls (:edge/kind %))
                          (= "target" (:edge/target-text %)))
                    edges))))
    (is (every? (set (map :symbol/name symbols))
                #{"alias" "ignored"}))
    (is (some #(and (= "alias" (:edge/target-text %))
                    (= :edge.kind/calls (:edge/kind %)))
              edges))
    (is (some #(and (= "f" (:reference/target-text %))
                    (= :dynamic (:reference/classification %)))
              refs))
    (is (some #(and (= "when" (:reference/target-text %))
                    (= :dynamic (:reference/classification %))
                    (= :edge.kind/calls (:reference/kind %)))
              refs))))

(deftest top-level-rebinding-has-one-stable-module-identity
  (let [root (Files/createTempDirectory
              "llm-context-janet-rebind-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        result
        (janet/analyze
         (project/context (str root))
         [(input root "main.janet"
                 (str "(defn selected [] :first)\n"
                      "(defn first-use [] (selected))\n"
                      "(defn selected [] :second)\n"
                      "(defn second-use [] (selected))\n"))])
        symbols (filter #(= "selected" (:symbol/name %))
                        (entities result :entity.type/symbol))
        calls (filter #(and (= :edge.kind/calls (:edge/kind %))
                            (= "selected" (:edge/target-text %)))
                      (entities result :entity.type/edge))]
    (is (= 1 (count symbols)))
    (is (= 2 (count calls)))
    (is (= #{(:symbol/id (first symbols))}
           (set (map :edge/to calls))))))

(deftest imports-respect-exports-aliases-macros-and-order
  (let [root (Files/createTempDirectory
              "llm-context-janet-imports-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        result
        (janet/analyze
         (project/context (str root))
         [(input root "lib/tools.janet"
                 (str "(defn public [] :private)\n"
                      "(defn- hidden [] nil)\n"
                      "(defmacro wrapped [body] body)\n"))
          (input root "main.janet"
                 (str "(tools/public)\n"
                      "(import \"./lib/tools\" :as tools)\n"
                      "(tools/public)\n"
                      "(tools/wrapped (print :ok))\n"
                      "(tools/hidden)\n"
                      "(defn shadow-module [tools] (tools/public))\n"
                      "(use ./lib/tools)\n"
                      "(public)\n"
                      "(when true (inc 1))\n"))])
        refs (entities result :entity.type/reference)
        edges (entities result :entity.type/edge)
        public-refs (filter #(= "tools/public" (:reference/target-text %)) refs)]
    (is (= [:external :dynamic]
           (mapv :reference/classification public-refs)))
    (is (some #(and (= "tools/public" (:reference/target-text %))
                    (= :dynamic (:reference/classification %)))
              refs))
    (is (some #(and (= :edge.kind/calls (:edge/kind %))
                    (= "tools/public" (:edge/target-text %)))
              edges))
    (is (some #(and (= :edge.kind/macro-invokes (:edge/kind %))
                    (= "tools/wrapped" (:edge/target-text %)))
              edges))
    (is (some #(and (= "tools/hidden" (:reference/target-text %))
                    (= :unresolved (:reference/classification %)))
              refs))
    (is (some #(and (= "when" (:reference/target-text %))
                    (= :external (:reference/classification %))
                    (= :edge.kind/macro-invokes (:reference/kind %)))
              refs))
    (is (some #(and (= "inc" (:reference/target-text %))
                    (= :external (:reference/classification %))
                    (= :edge.kind/calls (:reference/kind %)))
              refs))))

(deftest loop-and-collection-bindings-stay-in-their-lexical-frames
  (let [root (Files/createTempDirectory
              "llm-context-janet-loops-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        result
        (janet/analyze
         (project/context (str root))
         [(input root "main.janet"
                 (str "(defn consume [xs]\n"
                      "  (eachk key xs (key))\n"
                      "  (eachp [key value] xs (key) (value))\n"
                      "  (loop [x :in xs y :range [0 2] :when (x)]\n"
                      "    (x) (y)))\n"))])
        refs (entities result :entity.type/reference)
        bound-calls (filter #(contains? #{"key" "value" "x" "y"}
                                         (:reference/target-text %))
                            refs)]
    (is (= 6 (count bound-calls)))
    (is (every? #(= :dynamic (:reference/classification %))
                bound-calls))))

(deftest malformed-janet-output-is-marked-for-preservation
  (let [root (Files/createTempDirectory
              "llm-context-janet-malformed-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        result (janet/analyze
                (project/context (str root))
                [(input root "broken.janet" "(defn broken [x]")])
        output (first (:outputs result))]
    (is (:preserve? output))
    (is (= :malformed (:status output)))
    (is (empty? (:entities output)))
    (is (= :parse-error (get-in output [:diagnostics 0 :kind])))
    (is (re-find #"preserving the last complete"
                 (get-in output [:diagnostics 0 :message])))))
