(ns llm-context.analysis.aggregate-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.analysis.aggregate :as aggregate]
            [llm-context.analysis.canonical :as canonical]
            [llm-context.model.ids :as ids]))

(defn- fixture [source name]
  (let [path "src/neutral/catalog.clj"
        file {:entity/type :entity.type/file
              :file/id (ids/file-id path)
              :file/path path
              :file/language :language/clojure
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
                :symbol/platform :clj
                :symbol/analyzer :clj-kondo
                :symbol/scope :scope/top-level
                :symbol/role :role/variable
                :symbol/indexable? true
                :symbol/doc "All built-in transport providers"
                :source/start-line 2 :source/start-column 1
                :source/end-line 2
                :source/end-column (inc (count (second (clojure.string/split-lines source))))}
        file-source {:relative-path path :language :language/clojure
                     :content source}
        output {:file file :entities [symbol] :diagnostics []}]
    (aggregate/enrich-output file-source output)))

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
