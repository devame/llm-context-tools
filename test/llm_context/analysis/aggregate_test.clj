(ns llm-context.analysis.aggregate-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.analysis.aggregate :as aggregate]
            [llm-context.analysis.canonical :as canonical]
            [llm-context.model.ids :as ids]))

(defn- fixture
  ([source name]
   (fixture source name :language/clojure "src/neutral/catalog.clj" :clj))
  ([source name language path platform]
   (let [file {:entity/type :entity.type/file
              :file/id (ids/file-id path)
              :file/path path
              :file/language language
              :file/content-hash (ids/content-hash source)
              :file/size (alength (.getBytes source
                                             java.nio.charset.StandardCharsets/UTF_8))
              :file/modified-at 1}
        symbol {:entity/type :entity.type/symbol
                :symbol/id "symbol:neutral-catalog"
                :symbol/name name
                :symbol/qualified-name (str "neutral.catalog/" name)
                :symbol/kind :symbol.kind/variable
                :symbol/file (:file/id file)
                :symbol/platform platform
                :symbol/analyzer :clj-kondo
                :symbol/scope :scope/top-level
                :symbol/role :role/variable
                :symbol/indexable? true
                :symbol/doc "All built-in transport providers"
                :source/start-line 2 :source/start-column 1
                :source/end-line 2
                :source/end-column (inc (count (second (clojure.string/split-lines source))))}
        file-source {:relative-path path :language language
                     :content source}
        output {:file file :entities [symbol] :diagnostics []}]
     (aggregate/enrich-output file-source output))))

(deftest complete-literal-collections-produce-canonical-membership-facts
  (let [source (str "(ns neutral.catalog)\n"
                    "(def transports \"All built-in transport providers\" "
                    "#{\"river\" \"road\" \"rail\"})\n")
        output (fixture source "transports")
        entities (canonical/canonical-snapshot
                  (cons (:file output) (:entities output)))
        aggregate (some #(when (= :entity.type/aggregate (:entity/type %)) %)
                        entities)
        members (filter #(= :entity.type/membership (:entity/type %))
                        entities)]
    (is (= :complete-static (:aggregate/completeness aggregate)))
    (is (= :aggregate.kind/literal-set (:aggregate/kind aggregate)))
    (is (= 3 (:aggregate/member-count aggregate)))
    (is (= ["\"rail\"" "\"river\"" "\"road\""]
           (mapv :membership/value members)))
    (is (every? #(= :literal (:membership/evidence %)) members))
    (is (= (:entities output)
           (:entities (fixture source "transports")))
        "aggregate identities and ordering are deterministic")))

(deftest dynamic-members-make-an-aggregate-partial-without-evaluation
  (let [source (str "(ns neutral.catalog)\n"
                    "(def transports [\"road\" discovered-provider])\n")
        output (fixture source "transports")
        aggregate (some #(when (= :entity.type/aggregate (:entity/type %)) %)
                        (:entities output))
        members (filter #(= :entity.type/membership (:entity/type %))
                        (:entities output))]
    (is (= :partial-static (:aggregate/completeness aggregate)))
    (is (= [:literal :dynamic-expression]
           (mapv :membership/evidence members)))
    (is (= ["\"road\"" "discovered-provider"]
           (mapv :membership/value members)))))

(deftest map-keys-remain-searchable-when-values-are-dynamic
  (let [source (str "(ns neutral.catalog)\n"
                    "(def transports {:rail rail-provider :river river-provider})\n")
        output (fixture source "transports")
        aggregate (some #(when (= :entity.type/aggregate (:entity/type %)) %)
                        (:entities output))]
    (is (= :partial-static (:aggregate/completeness aggregate)))
    (is (clojure.string/includes? (:aggregate/search-text aggregate) ":rail"))
    (is (clojure.string/includes? (:aggregate/search-text aggregate) ":river"))))

(deftest namespace-alias-keywords-produce-aggregate-facts
  (let [source (str "(ns neutral.catalog\n"
                    "  (:require [neutral.email :as email]\n"
                    "            [neutral.memoize :as-alias memoize]))\n"
                    "(def transports {::email/error \"failed\"\n"
                    "                 ::memoize/args-fn \"args\"\n"
                    "                 ::local \"local\"})\n")
        output (fixture source "transports")
        aggregate (some #(when (= :entity.type/aggregate (:entity/type %)) %)
                        (:entities output))
        members (filter #(= :entity.type/membership (:entity/type %))
                        (:entities output))]
    (is (empty? (:diagnostics output)))
    (is (= :complete-static (:aggregate/completeness aggregate)))
    (is (= #{":neutral.email/error"
             ":neutral.memoize/args-fn"
             ":neutral.catalog/local"}
           (set (map :membership/key members))))))

(deftest unknown-namespace-alias-remains-a-contained-diagnostic
  (let [source (str "(ns neutral.catalog)\n"
                    "(def transports {::missing/error \"failed\"})\n")
        output (fixture source "transports")]
    (is (= :aggregate-analysis-skipped
           (:kind (first (:diagnostics output)))))
    (is (clojure.string/includes?
         (:message (first (:diagnostics output)))
         "Invalid keyword: ::missing/error"))))

(deftest clojurescript-js-literals-produce-aggregate-facts
  (let [source (str "(ns neutral.catalog)\n"
                    "(def request-options #js {:method \"GET\"\n"
                    "                          :headers #js {\"Accept\" \"application/json\"}})\n")
        output (fixture source "request-options"
                        :language/clojurescript
                        "src/neutral/catalog.cljs"
                        :cljs)
        aggregate (some #(when (= :entity.type/aggregate (:entity/type %)) %)
                        (:entities output))
        members (filter #(= :entity.type/membership (:entity/type %))
                        (:entities output))]
    (is (empty? (:diagnostics output)))
    (is (= :aggregate.kind/literal-map (:aggregate/kind aggregate)))
    (is (= 2 (:aggregate/member-count aggregate)))
    (is (= #{":headers" ":method"}
           (set (map :membership/key members))))
    (is (every? #(= :literal (:membership/evidence %)) members))))

(deftest invalid-js-literal-keys-remain-a-contained-diagnostic
  (let [source (str "(ns neutral.catalog)\n"
                    "(def request-options #js {::method \"GET\"})\n")
        output (fixture source "request-options"
                        :language/clojurescript
                        "src/neutral/catalog.cljs"
                        :cljs)]
    (is (= :aggregate-analysis-skipped
           (:kind (first (:diagnostics output)))))
    (is (clojure.string/includes?
         (:message (first (:diagnostics output)))
         "JavaScript literal keys must be strings or unqualified keywords"))))

(deftest ordinary-definitions-and-unsupported-languages-are-not-promoted
  (testing "function bodies are not mistaken for literal registries"
    (let [output (fixture (str "(ns neutral.catalog)\n"
                               "(defn transports [] [\"road\"])\n")
                          "transports")]
      (is (not-any? #(= :entity.type/aggregate (:entity/type %))
                    (:entities output)))))
  (testing "the producer boundary leaves unsupported languages unchanged"
    (let [output {:file {:file/path "src/catalog.janet"}
                  :entities [] :diagnostics []}]
      (is (= output
             (aggregate/enrich-output
              {:relative-path "src/catalog.janet"
               :language :language/janet :content "(def transports @[])"}
              output))))))
