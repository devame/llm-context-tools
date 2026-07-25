(ns llm-context.model.schema
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(def graph-format-version 2)

(def entity-types #{:entity.type/file :entity.type/symbol :entity.type/edge
                    :entity.type/reference :entity.type/topic
                    :entity.type/effect :entity.type/analysis})
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
(def reference-classifications
  #{:external :dynamic :ambiguous :unresolved})
(def topic-kinds
  #{:event :subscription :effect :coeffect :state-key})

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

(s/def :source/start-line pos-int?)
(s/def :source/start-column pos-int?)
(s/def :source/end-line pos-int?)
(s/def :source/end-column pos-int?)
(s/def :source/snippet string?)

(s/def ::file
  (s/keys :req [:entity/type :file/id :file/path :file/language
                :file/content-hash :file/size :file/modified-at]
          :opt [:file/semantic-hash]))
(s/def ::source-range
  (s/keys :req [:source/start-line :source/start-column
                :source/end-line :source/end-column]))
(s/def ::symbol
  (s/and (s/keys :req [:entity/type :symbol/id :symbol/name
                       :symbol/qualified-name :symbol/kind :symbol/file]
                 :opt [:symbol/signature :symbol/doc
                       :symbol/search-text :symbol/platform :symbol/analyzer
                       :symbol/private? :symbol/macro? :symbol/arglists
                       :source/start-line :source/start-column
                       :source/end-line :source/end-column])
         #(or (not (contains? % :source/start-line))
              (s/valid? ::source-range %))))
(s/def ::edge
  (s/and
   (s/keys :req [:entity/type :edge/id :edge/kind :edge/from :edge/to
                 :edge/target-text :edge/resolution :edge/confidence
                 :edge/evidence]
           :opt [:source/start-line :source/start-column
                 :source/end-line :source/end-column :source/snippet])
   #(= :resolution/exact (:edge/resolution %))
   #(= 1.0 (double (:edge/confidence %)))))
(s/def ::reference
  (s/keys :req [:entity/type :reference/id :reference/symbol
                :reference/kind :reference/target-text
                :reference/classification :reference/evidence]
          :opt [:reference/qualified-target
                :source/start-line :source/start-column
                :source/end-line :source/end-column :source/snippet]))
(s/def ::topic
  (s/keys :req [:entity/type :topic/id :topic/kind :topic/key
                :topic/platform]))
(s/def ::effect
  (s/keys :req [:entity/type :effect/id :effect/kind :effect/symbol
                :effect/detail :effect/confidence]
          :opt [:source/start-line :source/start-column
                :source/end-line :source/end-column :source/snippet]))

(def entity-specs
  {:entity.type/file ::file
   :entity.type/symbol ::symbol
   :entity.type/edge ::edge
   :entity.type/reference ::reference
   :entity.type/topic ::topic
   :entity.type/effect ::effect})

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

(defn with-symbol-search-text [entity]
  (if (= :entity.type/symbol (:entity/type entity))
    (assoc entity :symbol/search-text (symbol-search-text entity))
    entity))

(defn with-derived-attributes [entity]
  (with-symbol-search-text entity))

(def datalevin-schema
  {:llm-context/meta-key {:db/valueType :db.type/string
                          :db/unique :db.unique/identity}
   :llm-context/search-schema-version {:db/valueType :db.type/long}

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

   :source/start-line {:db/valueType :db.type/long}
   :source/start-column {:db/valueType :db.type/long}
   :source/end-line {:db/valueType :db.type/long}
   :source/end-column {:db/valueType :db.type/long}
   :source/snippet {:db/valueType :db.type/string}})
