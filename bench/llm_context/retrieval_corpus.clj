(ns llm-context.retrieval-corpus
  "Validate retrieval judgments against real analyzer output."
  (:require [clojure.set :as set]
            [llm-context.analysis.files :as files]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.analysis.project-analyzer :as project-analyzer]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.evaluation :as evaluation]))

(def default-project "bench/retrieval-corpus/project")
(def default-queries "bench/retrieval-corpus/queries.edn")

(defn- result-symbols [outputs]
  (mapcat
   (fn [{:keys [file entities]}]
     (keep (fn [entity]
             (when (= :entity.type/symbol (:entity/type entity))
               {:id (:symbol/id entity)
                :name (:symbol/name entity)
                :qualified-name (:symbol/qualified-name entity)
                :platform (:symbol/platform entity)
                :file (:file/path file)
                :kind (:symbol/kind entity)}))
           entities))
   outputs))

(defn- selector-key [selector]
  (dissoc selector :grade))

(defn- selector-matches [symbols selector]
  (filterv #(evaluation/selector-match? % selector) symbols))

(defn- query-resolution-errors [symbols query]
  (let [version (:evaluation/corpus-version query)
        relevance (:relevance query)
        hard-negatives (:hard-negatives query)
        selectors (concat relevance hard-negatives)
        resolved (mapv #(selector-matches symbols %) selectors)
        missing
        (keep-indexed
         (fn [index matches]
           (when (zero? (count matches))
             {:query-id (:id query)
              :selector (selector-key (nth (vec selectors) index))}))
         resolved)
        ambiguous
        (when (= 2 version)
          (keep-indexed
           (fn [index matches]
             (when (> (count matches) 1)
               {:query-id (:id query)
                :selector (selector-key (nth (vec selectors) index))
                :matches (mapv #(select-keys % [:id :qualified-name
                                                :platform :file :kind])
                               matches)}))
           resolved))
        relevant-count (count relevance)
        relevant-ids (set (mapcat #(map :id %) (take relevant-count resolved)))
        negative-ids (set (mapcat #(map :id %) (drop relevant-count resolved)))
        overlap (set/intersection relevant-ids negative-ids)]
    {:missing (vec missing)
     :ambiguous (vec ambiguous)
     :overlap (when (seq overlap)
                {:query-id (:id query) :symbol-ids (vec (sort overlap))})}))

(defn validate!
  [project-path query-path]
  (let [project (project/context project-path)
        config (config/load-config project)
        discovery (files/discover project config incremental/supported-languages)
        snapshot (project-analyzer/analyze project (:files discovery))
        outputs (:outputs snapshot)
        symbols (vec (result-symbols outputs))
        corpus (evaluation/read-corpus-data query-path)
        queries (:queries corpus)
        resolutions (mapv #(query-resolution-errors symbols %) queries)
        missing (vec (mapcat :missing resolutions))
        ambiguous (vec (mapcat :ambiguous resolutions))
        overlaps (vec (keep :overlap resolutions))
        diagnostics (vec (concat (:diagnostics discovery)
                                 (:diagnostics snapshot)
                                 (mapcat :diagnostics outputs)))
        errors (filterv #(= :error (:level %)) diagnostics)
        warnings (filterv #(not= :error (:level %)) diagnostics)
        selectors
        (set (map selector-key
                  (concat (mapcat :relevance queries)
                          (mapcat :hard-negatives queries))))]
    (when (or (seq missing) (seq ambiguous) (seq overlaps) (seq errors))
      (throw
       (ex-info "Retrieval corpus does not match analyzer output"
                {:exit-code 1
                 :missing-selectors missing
                 :ambiguous-selectors ambiguous
                 :relevant-hard-negative-overlaps overlaps
                 :diagnostics errors})))
    {:corpus/version (:corpus/version corpus)
     :queries (count queries)
     :languages (frequencies (map :language queries))
     :query-types (frequencies (map :query-type queries))
     :files (count (:files discovery))
     :symbols (count symbols)
     :judged-identities (count selectors)
     :analyzer-warnings (count warnings)}))

(defn -main [& [project-path query-path]]
  (prn (validate! (or project-path default-project)
                  (or query-path default-queries))))
