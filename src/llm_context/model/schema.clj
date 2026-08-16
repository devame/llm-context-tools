(ns llm-context.model.schema
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(def graph-format-version 4)

(def entity-types #{:entity.type/file :entity.type/symbol :entity.type/edge
                    :entity.type/reference :entity.type/topic
                    :entity.type/effect :entity.type/aggregate
                    :entity.type/membership :entity.type/analysis})
(def symbol-kinds #{:symbol.kind/function :symbol.kind/method :symbol.kind/class
                    :symbol.kind/interface :symbol.kind/module :symbol.kind/namespace
                    :symbol.kind/macro :symbol.kind/protocol
                    :symbol.kind/multimethod :symbol.kind/variable
                    :symbol.kind/constant :symbol.kind/type})
(def edge-kinds #{:edge.kind/calls :edge.kind/imports :edge.kind/extends
                  :edge.kind/implements :edge.kind/references :edge.kind/contains
                  :edge.kind/macro-invokes :edge.kind/protocol-implements
                  :edge.kind/event-dispatches :edge.kind/subscribes
                  :edge.kind/topic-registers :edge.kind/state-reads
                  :edge.kind/state-writes})
(def resolution-states #{:resolution/exact})
(def effect-kinds #{:effect.kind/file-read :effect.kind/file-write
                    :effect.kind/network :effect.kind/database-read
                    :effect.kind/database-write :effect.kind/process
                    :effect.kind/environment-read :effect.kind/global-mutation
                    :effect.kind/logging :effect.kind/unknown})
(def platforms #{:clj :cljs :janet :data})
(def symbol-scopes
  #{:scope/namespace :scope/module :scope/top-level :scope/method})
(def symbol-roles
  #{:role/definition :role/macro :role/protocol :role/method
    :role/variable :role/namespace :role/module})
(def reference-classifications
  #{:external :dynamic :ambiguous :unresolved})
(def topic-kinds
  #{:event :subscription :effect :coeffect :state-key})
(def aggregate-kinds
  #{:aggregate.kind/literal-set :aggregate.kind/literal-vector
    :aggregate.kind/literal-list :aggregate.kind/literal-map})
(def aggregate-completeness
  #{:complete-static :complete-resolved :partial-static :dynamic :unknown})
(def membership-value-kinds
  #{:string :keyword :symbol :number :boolean :nil :character
    :map :set :vector :list :other})

(s/def :entity/type entity-types)
(s/def :file/id (s/and string? #(str/starts-with? % "file:")))
(s/def :file/path (s/and string? seq))
(s/def :file/language keyword?)
(s/def :file/content-hash (s/and string? #(str/starts-with? % "sha256:")))
(s/def :file/size nat-int?)
(s/def :file/modified-at int?)
(s/def :file/semantic-hash
  (s/and string? #(str/starts-with? % "sha256:")))

(s/def :symbol/id (s/and string? #(str/starts-with? % "symbol:")))
(s/def :symbol/name (s/and string? seq))
(s/def :symbol/qualified-name (s/and string? seq))
(s/def :symbol/kind symbol-kinds)
(s/def :symbol/file :file/id)
(s/def :symbol/platform platforms)
(s/def :symbol/analyzer keyword?)
(s/def :symbol/scope symbol-scopes)
(s/def :symbol/role symbol-roles)
(s/def :symbol/indexable? boolean?)
(s/def :symbol/protocol-name (s/and string? seq))
(s/def :symbol/private? boolean?)
(s/def :symbol/macro? boolean?)
(s/def :symbol/arglists string?)
(s/def :symbol/signature string?)
(s/def :symbol/doc string?)
(s/def :symbol/search-text (s/and string? seq))

(s/def :edge/id (s/and string? #(str/starts-with? % "edge:")))
(s/def :edge/kind edge-kinds)
(s/def :edge/from :symbol/id)
(s/def :edge/to (s/or :symbol :symbol/id :topic :topic/id))
(s/def :edge/target-text (s/and string? seq))
(s/def :edge/resolution resolution-states)
(s/def :edge/confidence (s/and number? #(<= 0.0 (double %) 1.0)))
(s/def :edge/evidence keyword?)

(s/def :reference/id (s/and string? #(str/starts-with? % "reference:")))
(s/def :reference/symbol :symbol/id)
(s/def :reference/kind edge-kinds)
(s/def :reference/target-text (s/and string? seq))
(s/def :reference/qualified-target (s/and string? seq))
(s/def :reference/classification reference-classifications)
(s/def :reference/evidence keyword?)

(s/def :topic/id (s/and string? #(str/starts-with? % "topic:")))
(s/def :topic/kind topic-kinds)
(s/def :topic/key string?)
(s/def :topic/platform platforms)

(s/def :effect/id (s/and string? #(str/starts-with? % "effect:")))
(s/def :effect/kind effect-kinds)
(s/def :effect/symbol :symbol/id)
(s/def :effect/detail string?)
(s/def :effect/confidence (s/and number? #(<= 0.0 (double %) 1.0)))

(s/def :aggregate/id (s/and string? #(str/starts-with? % "aggregate:")))
(s/def :aggregate/name (s/and string? seq))
(s/def :aggregate/kind aggregate-kinds)
(s/def :aggregate/owner :symbol/id)
(s/def :aggregate/file :file/id)
(s/def :aggregate/completeness aggregate-completeness)
(s/def :aggregate/member-count nat-int?)
(s/def :aggregate/member-kind keyword?)
(s/def :aggregate/analyzer keyword?)
(s/def :aggregate/search-text (s/and string? seq))

(s/def :membership/id
  (s/and string? #(str/starts-with? % "membership:")))
(s/def :membership/aggregate :aggregate/id)
(s/def :membership/key string?)
(s/def :membership/value string?)
(s/def :membership/value-kind membership-value-kinds)
(s/def :membership/ordinal nat-int?)
(s/def :membership/evidence keyword?)

(s/def :source/start-line pos-int?)
(s/def :source/start-column pos-int?)
(s/def :source/end-line pos-int?)
(s/def :source/end-column pos-int?)
(s/def :source/snippet string?)
(s/def :source/start-byte nat-int?)
(s/def :source/end-byte nat-int?)

;; Datalevin does not have a portable arbitrary-EDN value type. Provenance is
;; therefore normalized into deterministic, queryable scalar attributes rather
;; than serialized as an opaque map.
(s/def :entity/evidence keyword?)
(s/def :entity/analyzer keyword?)
(s/def :entity/record-kind keyword?)

(def source-line-range-keys
  [:source/start-line :source/start-column
   :source/end-line :source/end-column])

(def source-byte-range-keys
  [:source/start-byte :source/end-byte])

(def source-range-keys
  (into source-line-range-keys source-byte-range-keys))

(defn source-range?
  "True when all source coordinates form one ordered range. Lines and columns
  are one-based display coordinates; byte offsets are zero-based UTF-8 offsets
  with an exclusive end."
  [entity]
  (and (every? #(contains? entity %) source-line-range-keys)
       (let [byte-count (count (filter #(contains? entity %)
                                      source-byte-range-keys))]
         (or (zero? byte-count) (= byte-count 2)))
       (let [{:source/keys [start-line start-column end-line end-column
                            start-byte end-byte]} entity]
         (and (or (< start-line end-line)
                  (and (= start-line end-line)
                       (<= start-column end-column)))
              (or (nil? start-byte)
                  (<= start-byte end-byte))))))

(defn valid-optional-source-range?
  "Compatibility predicate for persisted source coordinates. Existing
  analyzers may still emit partial line/column observations. A format-3 byte
  range, when present, is always a complete ordered pair and accompanies a
  complete line/column range."
  [entity]
  (let [line-count (count (filter #(contains? entity %)
                                  source-line-range-keys))
        byte-count (count (filter #(contains? entity %)
                                  source-byte-range-keys))
        complete-lines? (= line-count (count source-line-range-keys))]
    (and
     (contains? #{0 2} byte-count)
     (or (not complete-lines?)
         (let [{:source/keys [start-line start-column
                              end-line end-column]} entity]
           (or (< start-line end-line)
               (and (= start-line end-line)
                    (<= start-column end-column)))))
     (or (zero? byte-count)
         (and complete-lines?
              (<= (:source/start-byte entity)
                  (:source/end-byte entity)))))))

(s/def ::provenance
  (s/keys :req [:entity/evidence :entity/analyzer :entity/record-kind]))

(s/def ::file
  (s/keys :req [:entity/type :file/id :file/path :file/language
                :file/content-hash :file/size :file/modified-at]
          :opt [:file/semantic-hash]))
(s/def ::source-range
  (s/and (s/keys :req [:source/start-line :source/start-column
                       :source/end-line :source/end-column]
                 :opt [:source/start-byte :source/end-byte])
         source-range?))
(s/def ::symbol
  (s/and (s/keys :req [:entity/type :symbol/id :symbol/name
                       :symbol/qualified-name :symbol/kind :symbol/file
                       :symbol/platform :symbol/analyzer :symbol/scope
                       :symbol/role :symbol/indexable?]
                 :opt [:symbol/signature :symbol/doc
                       :symbol/search-text
                       :symbol/private? :symbol/macro? :symbol/arglists
                       :symbol/protocol-name
                       :source/start-line :source/start-column
                       :source/end-line :source/end-column
                       :source/start-byte :source/end-byte
                       :entity/evidence :entity/analyzer :entity/record-kind])
         valid-optional-source-range?))
(s/def ::edge
  (s/and
   (s/keys :req [:entity/type :edge/id :edge/kind :edge/from :edge/to
                 :edge/target-text :edge/resolution :edge/confidence
                 :edge/evidence]
           :opt [:source/start-line :source/start-column
                 :source/end-line :source/end-column
                 :source/start-byte :source/end-byte :source/snippet
                 :entity/evidence :entity/analyzer :entity/record-kind])
   #(= :resolution/exact (:edge/resolution %))
   #(= 1.0 (double (:edge/confidence %)))
   valid-optional-source-range?))
(s/def ::reference
  (s/and
   (s/keys :req [:entity/type :reference/id :reference/symbol
                 :reference/kind :reference/target-text
                 :reference/classification :reference/evidence]
           :opt [:reference/qualified-target
                 :source/start-line :source/start-column
                 :source/end-line :source/end-column
                 :source/start-byte :source/end-byte :source/snippet
                 :entity/evidence :entity/analyzer :entity/record-kind])
   valid-optional-source-range?))
(s/def ::topic
  (s/keys :req [:entity/type :topic/id :topic/kind :topic/key
                :topic/platform]))
(s/def ::effect
  (s/and
   (s/keys :req [:entity/type :effect/id :effect/kind :effect/symbol
                 :effect/detail :effect/confidence]
           :opt [:source/start-line :source/start-column
                 :source/end-line :source/end-column
                 :source/start-byte :source/end-byte :source/snippet
                 :entity/evidence :entity/analyzer :entity/record-kind])
   valid-optional-source-range?))
(s/def ::aggregate
  (s/and
   (s/keys :req [:entity/type :aggregate/id :aggregate/name
                 :aggregate/kind :aggregate/owner :aggregate/file
                 :aggregate/completeness :aggregate/member-count
                 :aggregate/member-kind :aggregate/analyzer]
           :opt [:aggregate/search-text
                 :source/start-line :source/start-column
                 :source/end-line :source/end-column
                 :source/start-byte :source/end-byte])
   valid-optional-source-range?))
(s/def ::membership
  (s/and
   (s/keys :req [:entity/type :membership/id :membership/aggregate
                 :membership/value :membership/value-kind
                 :membership/ordinal :membership/evidence]
           :opt [:membership/key
                 :source/start-line :source/start-column
                 :source/end-line :source/end-column
                 :source/start-byte :source/end-byte])
   valid-optional-source-range?))

(def entity-specs
  {:entity.type/file ::file
   :entity.type/symbol ::symbol
   :entity.type/edge ::edge
   :entity.type/reference ::reference
   :entity.type/topic ::topic
   :entity.type/effect ::effect
   :entity.type/aggregate ::aggregate
   :entity.type/membership ::membership})

(defn validate-entity!
  "Validate a canonical entity and return it unchanged."
  [entity]
  (let [spec (get entity-specs (:entity/type entity))]
    (when-not spec
      (throw (ex-info "Unknown semantic graph entity type"
                      {:entity entity :entity/type (:entity/type entity)})))
    (when-not (s/valid? spec entity)
      (throw (ex-info "Invalid semantic graph entity"
                      {:entity entity :explain (s/explain-data spec entity)})))
    entity))

(defn- normalized-identifier [value]
  (-> value
      (str/replace #"([\p{Ll}\p{N}])([\p{Lu}])" "$1 $2")
      (str/replace #"([\p{Lu}]+)([\p{Lu}][\p{Ll}])" "$1 $2")
      (str/replace #"[^\p{L}\p{N}]+" " ")
      str/lower-case
      str/trim))

(defn symbol-search-text
  "Build the deterministic full-text document stored for a symbol. Preserve
  source spellings while also splitting camelCase, kebab-case, namespaces,
  and punctuation so natural-language queries can match identifiers."
  [symbol]
  (->> ((juxt :symbol/name :symbol/qualified-name :symbol/signature :symbol/doc)
        symbol)
       (filter #(and (string? %) (seq %)))
       (mapcat (fn [value] [value (normalized-identifier value)]))
       (remove str/blank?)
       distinct
       (str/join "\n")))

(defn symbol-search-grams
  "Return deterministic one-, two-, and three-character grams for bounded
  identifier substring and typo candidate selection."
  [symbol]
  (->> ((juxt :symbol/name :symbol/qualified-name) symbol)
       (filter #(and (string? %) (seq %)))
       (map str/lower-case)
       (mapcat (fn [value]
                 (for [width (range 1 (inc (min 3 (count value))))
                       start (range (inc (- (count value) width)))]
                   (subs value start (+ start width)))))
       set))

(defn with-symbol-search-text [entity]
  (if (= :entity.type/symbol (:entity/type entity))
    (assoc entity
           :symbol/search-text (symbol-search-text entity)
           :symbol/search-grams (symbol-search-grams entity))
    entity))

(defn with-derived-attributes [entity]
  (cond-> (with-symbol-search-text entity)
    (= :entity.type/aggregate (:entity/type entity))
    (assoc :aggregate/search-text
           (or (:aggregate/search-text entity)
               (str (:aggregate/name entity) "\n"
                    (name (:aggregate/kind entity)) "\n"
                    (name (:aggregate/completeness entity)))))))

(def datalevin-schema
  {:llm-context/meta-key {:db/valueType :db.type/string
                          :db/unique :db.unique/identity}
   :llm-context/search-schema-version {:db/valueType :db.type/long}
   :llm-context/graph-format {:db/valueType :db.type/long}
   :llm-context/analyzer-name {:db/valueType :db.type/string}
   :llm-context/analyzer-version {:db/valueType :db.type/string}
   :llm-context/analyzer-configuration-fingerprint {:db/valueType :db.type/string}
   :llm-context/semantic-fingerprint-version {:db/valueType :db.type/long}
   :llm-context/janet-catalog-version {:db/valueType :db.type/string}
   :llm-context/semantic-document-version {:db/valueType :db.type/long}
   :llm-context/semantic-index-name {:db/valueType :db.type/string}

   ;; Operational semantic state is deliberately not assigned :entity/type.
   ;; Full graph replacement only retracts canonical graph entities, so the
   ;; durable queue and its recovery markers survive interrupted rebuilds.
   :semantic.dirty/id {:db/valueType :db.type/string
                       :db/unique :db.unique/identity}
   :semantic.dirty/provider {:db/valueType :db.type/keyword
                             :db/index true}
   :semantic.dirty/file-id {:db/valueType :db.type/string
                            :db/index true}
   :semantic.dirty/file-hash {:db/valueType :db.type/string}
   :semantic.dirty/operation {:db/valueType :db.type/keyword
                              :db/index true}
   :semantic.dirty/created-at {:db/valueType :db.type/long}
   :semantic.dirty/last-error-at {:db/valueType :db.type/long}
   :semantic.dirty/last-error {:db/valueType :db.type/string}

   :semantic.job/id {:db/valueType :db.type/string
                     :db/unique :db.unique/identity}
   :semantic.job/provider {:db/valueType :db.type/keyword
                           :db/index true}
   :semantic.job/symbol-id {:db/valueType :db.type/string
                            :db/index true}
   :semantic.job/file-id {:db/valueType :db.type/string
                          :db/index true}
   :semantic.job/operation {:db/valueType :db.type/keyword
                            :db/index true}
   :semantic.job/document-hash {:db/valueType :db.type/string}
   :semantic.job/status {:db/valueType :db.type/keyword
                         :db/index true}
   :semantic.job/attempts {:db/valueType :db.type/long}
   :semantic.job/available-at {:db/valueType :db.type/long
                               :db/index true}
   :semantic.job/lease-owner {:db/valueType :db.type/string
                              :db/index true}
   :semantic.job/lease-until {:db/valueType :db.type/long
                              :db/index true}
   :semantic.job/last-error {:db/valueType :db.type/string}
   :semantic.job/updated-at {:db/valueType :db.type/long}

   :semantic.indexed/id {:db/valueType :db.type/string
                         :db/unique :db.unique/identity}
   :semantic.indexed/provider {:db/valueType :db.type/keyword
                               :db/index true}
   :semantic.indexed/symbol-id {:db/valueType :db.type/string
                                :db/index true}
   :semantic.indexed/file-id {:db/valueType :db.type/string
                              :db/index true}
   :semantic.indexed/document-hash {:db/valueType :db.type/string
                                    :db/index true}
   :semantic.indexed/model-revision {:db/valueType :db.type/string}
   :semantic.indexed/document-version {:db/valueType :db.type/long}
   :semantic.indexed/chunk-count {:db/valueType :db.type/long}
   :semantic.indexed/updated-at {:db/valueType :db.type/long}

   :semantic.watermark/id {:db/valueType :db.type/string
                           :db/unique :db.unique/identity}
   :semantic.watermark/provider {:db/valueType :db.type/keyword
                                 :db/index true}
   :semantic.watermark/state {:db/valueType :db.type/keyword
                              :db/index true}
   :semantic.watermark/last-success-at {:db/valueType :db.type/long}
   :semantic.watermark/last-error-at {:db/valueType :db.type/long}
   :semantic.watermark/last-error {:db/valueType :db.type/string}
   :semantic.watermark/graph-revision {:db/valueType :db.type/string}
   :semantic.watermark/index-generation {:db/valueType :db.type/string}

   :entity/type {:db/valueType :db.type/keyword
                 :db/index true}

   :file/id {:db/valueType :db.type/string
             :db/unique :db.unique/identity}
   :file/path {:db/valueType :db.type/string
               :db/index true}
   :file/language {:db/valueType :db.type/keyword
                   :db/index true}
   :file/content-hash {:db/valueType :db.type/string}
   :file/size {:db/valueType :db.type/long}
   :file/modified-at {:db/valueType :db.type/long}
   :file/semantic-hash {:db/valueType :db.type/string
                        :db/index true}

   :symbol/id {:db/valueType :db.type/string
               :db/unique :db.unique/identity}
   :symbol/name {:db/valueType :db.type/string
                 :db/index true}
   :symbol/qualified-name {:db/valueType :db.type/string
                           :db/index true}
   :symbol/kind {:db/valueType :db.type/keyword
                 :db/index true}
   :symbol/platform {:db/valueType :db.type/keyword
                     :db/index true}
   :symbol/analyzer {:db/valueType :db.type/keyword
                     :db/index true}
   :symbol/scope {:db/valueType :db.type/keyword
                  :db/index true}
   :symbol/role {:db/valueType :db.type/keyword
                 :db/index true}
   :symbol/indexable? {:db/valueType :db.type/boolean
                       :db/index true}
   :symbol/protocol-name {:db/valueType :db.type/string
                          :db/index true}
   :symbol/private? {:db/valueType :db.type/boolean
                     :db/index true}
   :symbol/macro? {:db/valueType :db.type/boolean
                   :db/index true}
   :symbol/arglists {:db/valueType :db.type/string}
   :symbol/file {:db/valueType :db.type/ref
                 :db/index true}
   :symbol/signature {:db/valueType :db.type/string}
   :symbol/doc {:db/valueType :db.type/string}
   :symbol/search-text {:db/valueType :db.type/string
                        :db/fulltext true
                        :db.fulltext/domains ["symbols"]}
   :symbol/search-grams {:db/valueType :db.type/string
                         :db/cardinality :db.cardinality/many
                         :db/index true}

   :edge/id {:db/valueType :db.type/string
             :db/unique :db.unique/identity}
   :edge/kind {:db/valueType :db.type/keyword
               :db/index true}
   :edge/from {:db/valueType :db.type/ref
               :db/index true}
   :edge/to {:db/valueType :db.type/ref
             :db/index true}
   :edge/target-text {:db/valueType :db.type/string
                      :db/index true}
   :edge/resolution {:db/valueType :db.type/keyword
                     :db/index true}
   :edge/confidence {:db/valueType :db.type/double}
   :edge/evidence {:db/valueType :db.type/keyword
                   :db/index true}

   :reference/id {:db/valueType :db.type/string
                  :db/unique :db.unique/identity}
   :reference/symbol {:db/valueType :db.type/ref
                      :db/index true}
   :reference/kind {:db/valueType :db.type/keyword
                    :db/index true}
   :reference/target-text {:db/valueType :db.type/string
                           :db/index true}
   :reference/qualified-target {:db/valueType :db.type/string
                                :db/index true}
   :reference/classification {:db/valueType :db.type/keyword
                              :db/index true}
   :reference/evidence {:db/valueType :db.type/keyword
                        :db/index true}

   :topic/id {:db/valueType :db.type/string
              :db/unique :db.unique/identity}
   :topic/kind {:db/valueType :db.type/keyword
                :db/index true}
   :topic/key {:db/valueType :db.type/string
               :db/index true}
   :topic/platform {:db/valueType :db.type/keyword
                    :db/index true}

   :effect/id {:db/valueType :db.type/string
               :db/unique :db.unique/identity}
   :effect/kind {:db/valueType :db.type/keyword
                 :db/index true}
   :effect/symbol {:db/valueType :db.type/ref
                   :db/index true}
   :effect/detail {:db/valueType :db.type/string}
   :effect/confidence {:db/valueType :db.type/double}

   :aggregate/id {:db/valueType :db.type/string
                  :db/unique :db.unique/identity}
   :aggregate/name {:db/valueType :db.type/string
                    :db/index true}
   :aggregate/kind {:db/valueType :db.type/keyword
                    :db/index true}
   :aggregate/owner {:db/valueType :db.type/ref
                     :db/index true}
   :aggregate/file {:db/valueType :db.type/ref
                    :db/index true}
   :aggregate/completeness {:db/valueType :db.type/keyword
                            :db/index true}
   :aggregate/member-count {:db/valueType :db.type/long}
   :aggregate/member-kind {:db/valueType :db.type/keyword
                           :db/index true}
   :aggregate/analyzer {:db/valueType :db.type/keyword
                        :db/index true}
   :aggregate/search-text {:db/valueType :db.type/string
                           :db/fulltext true
                           :db.fulltext/domains ["aggregates"]}

   :membership/id {:db/valueType :db.type/string
                   :db/unique :db.unique/identity}
   :membership/aggregate {:db/valueType :db.type/ref
                          :db/index true}
   :membership/key {:db/valueType :db.type/string
                    :db/index true}
   :membership/value {:db/valueType :db.type/string
                      :db/index true}
   :membership/value-kind {:db/valueType :db.type/keyword
                           :db/index true}
   :membership/ordinal {:db/valueType :db.type/long}
   :membership/evidence {:db/valueType :db.type/keyword
                         :db/index true}

   :entity/evidence {:db/valueType :db.type/keyword
                     :db/index true}
   :entity/analyzer {:db/valueType :db.type/keyword
                     :db/index true}
   :entity/record-kind {:db/valueType :db.type/keyword
                        :db/index true}

   :source/start-line {:db/valueType :db.type/long}
   :source/start-column {:db/valueType :db.type/long}
   :source/end-line {:db/valueType :db.type/long}
   :source/end-column {:db/valueType :db.type/long}
   :source/start-byte {:db/valueType :db.type/long}
   :source/end-byte {:db/valueType :db.type/long}
   :source/snippet {:db/valueType :db.type/string}})
