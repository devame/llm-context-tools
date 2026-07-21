(ns llm-context.model.schema
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(def entity-types #{:entity.type/file :entity.type/symbol :entity.type/edge
                    :entity.type/effect :entity.type/analysis})
(def symbol-kinds #{:symbol.kind/function :symbol.kind/method :symbol.kind/class
                    :symbol.kind/interface :symbol.kind/module :symbol.kind/namespace
                    :symbol.kind/variable :symbol.kind/constant :symbol.kind/type})
(def edge-kinds #{:edge.kind/calls :edge.kind/imports :edge.kind/extends
                  :edge.kind/implements :edge.kind/references :edge.kind/contains})
(def resolution-states #{:resolution/exact :resolution/heuristic
                         :resolution/ambiguous :resolution/unresolved})
(def effect-kinds #{:effect.kind/file-read :effect.kind/file-write
                    :effect.kind/network :effect.kind/database-read
                    :effect.kind/database-write :effect.kind/process
                    :effect.kind/environment-read :effect.kind/global-mutation
                    :effect.kind/logging :effect.kind/unknown})

(s/def :entity/type entity-types)
(s/def :file/id (s/and string? #(str/starts-with? % "file:")))
(s/def :file/path (s/and string? seq))
(s/def :file/language keyword?)
(s/def :file/content-hash (s/and string? #(str/starts-with? % "sha256:")))
(s/def :file/size nat-int?)
(s/def :file/modified-at int?)

(s/def :symbol/id (s/and string? #(str/starts-with? % "symbol:")))
(s/def :symbol/name (s/and string? seq))
(s/def :symbol/qualified-name (s/and string? seq))
(s/def :symbol/kind symbol-kinds)
(s/def :symbol/file :file/id)
(s/def :symbol/signature string?)
(s/def :symbol/doc string?)

(s/def :edge/id (s/and string? #(str/starts-with? % "edge:")))
(s/def :edge/kind edge-kinds)
(s/def :edge/from :symbol/id)
(s/def :edge/to :symbol/id)
(s/def :edge/target-text (s/and string? seq))
(s/def :edge/resolution resolution-states)
(s/def :edge/confidence (s/and number? #(<= 0.0 (double %) 1.0)))

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
                :file/content-hash :file/size :file/modified-at]))
(s/def ::source-range
  (s/keys :req [:source/start-line :source/start-column
                :source/end-line :source/end-column]))
(s/def ::symbol
  (s/and (s/keys :req [:entity/type :symbol/id :symbol/name
                       :symbol/qualified-name :symbol/kind :symbol/file]
                 :opt [:symbol/signature :symbol/doc
                       :source/start-line :source/start-column
                       :source/end-line :source/end-column])
         #(or (not (contains? % :source/start-line))
              (s/valid? ::source-range %))))
(s/def ::edge
  (s/keys :req [:entity/type :edge/id :edge/kind :edge/from
                :edge/target-text :edge/resolution :edge/confidence]
          :opt [:edge/to :source/start-line :source/start-column
                :source/end-line :source/end-column :source/snippet]))
(s/def ::effect
  (s/keys :req [:entity/type :effect/id :effect/kind :effect/symbol
                :effect/detail :effect/confidence]
          :opt [:source/start-line :source/start-column
                :source/end-line :source/end-column :source/snippet]))

(def entity-specs
  {:entity.type/file ::file
   :entity.type/symbol ::symbol
   :entity.type/edge ::edge
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

(def datalevin-schema
  {:entity/type {:db/valueType :db.type/keyword
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

   :symbol/id {:db/valueType :db.type/string
               :db/unique :db.unique/identity}
   :symbol/name {:db/valueType :db.type/string
                 :db/index true}
   :symbol/qualified-name {:db/valueType :db.type/string
                           :db/index true}
   :symbol/kind {:db/valueType :db.type/keyword
                 :db/index true}
   :symbol/file {:db/valueType :db.type/ref
                 :db/index true}
   :symbol/signature {:db/valueType :db.type/string}
   :symbol/doc {:db/valueType :db.type/string}

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
