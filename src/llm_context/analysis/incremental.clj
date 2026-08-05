(ns llm-context.analysis.incremental
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.files :as files]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.janet :as janet]
            [llm-context.analysis.manifest :as manifest]
            [llm-context.analysis.project-analyzer :as project-analyzer]
            [llm-context.graph.read :as graph-read]
            [llm-context.model.canonical-hash :as canonical-hash]
            [llm-context.model.ids :as ids]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.document :as semantic-document]
            [llm-context.store :as store]))

(def supported-languages full/supported-languages)

(defn index-present?
  ([project config]
   (store/with-store [graph project config]
     (index-present? graph)))
  ([graph]
   (graph-read/any-file? (store/database graph))))

(defn- changed-output?
  [existing {:keys [file]}]
  (let [record (get existing (:file/path file))]
    (or (nil? record)
        (not= (:file/content-hash file) (:hash record))
        (not= (:file/semantic-hash file) (:semantic-hash record)))))

(defn- reactivate-metadata!
  [graph config candidate]
  (store/write-graph-metadata!
   graph
   {:analyzer-name full/analyzer-name
    :analyzer-version (get-in candidate [:analyzers :clj-kondo :version])
    :analyzer-configuration-fingerprint
    (get-in candidate [:analyzers :clj-kondo :configuration-fingerprint])
    :semantic-fingerprint-version canonical-hash/contract-version
    :janet-catalog-version (get-in candidate [:analyzers :janet
                                               :catalog-version])
    :semantic-document-version
    (get-in config [:semantic :lateon-code :document-version])
    :semantic-index-name
    (get-in config [:semantic :lateon-code :index-name])}))

(defn- expected-contracts [project config]
  {:llm-context/analyzer-name full/analyzer-name
   :llm-context/analyzer-version clj-kondo/analyzer-version
   :llm-context/analyzer-configuration-fingerprint
   (clj-kondo/config-fingerprint project)
   :llm-context/janet-catalog-version janet/catalog-version
   :llm-context/semantic-fingerprint-version canonical-hash/contract-version
   :llm-context/semantic-document-version
   (get-in config [:semantic :lateon-code :document-version])
   :llm-context/semantic-index-name
   (get-in config [:semantic :lateon-code :index-name])})

(defn- contracts-compatible? [metadata expected]
  (every? (fn [[attribute value]] (= value (get metadata attribute))) expected))

(defn- inventory-unchanged? [discovered existing]
  (and (= (set (keys existing)) (set (map :relative-path discovered)))
       (every? (fn [{:keys [relative-path content]}]
                 (= (ids/content-hash content)
                    (get-in existing [relative-path :hash])))
               discovered)))

(defn- expected-analyzers [project]
  {:clj-kondo {:version clj-kondo/analyzer-version
                :configuration-fingerprint
                (clj-kondo/config-fingerprint project)}
   :janet {:catalog-version janet/catalog-version}
   :semantic-fingerprint {:version canonical-hash/contract-version}})

(defn- active-manifests [graph project]
  (manifest/load-active
   project
   (semantic-document/graph-revision (store/database graph))
   (expected-analyzers project)))

(defn- expanded-platforms [record]
  (or (:platforms record)
      (when-let [platform (:platform record)] [platform])
      []))

(defn- snapshot-exported-keys [snapshot]
  (set
   (concat
    (for [record (get-in snapshot [:analysis :namespace-definitions])
          platform (expanded-platforms record)]
      [platform (str (:name record))])
    (for [record (get-in snapshot [:analysis :var-definitions])
          platform (expanded-platforms record)]
      [platform (str (:ns record) "/" (:name record))]))))

(defn- exported-namespaces [manifest]
  (into #{}
        (keep (fn [{:keys [kind qualified-name]}]
                (if (= :symbol.kind/namespace kind)
                  qualified-name
                  (some-> qualified-name symbol namespace))))
        (:exports manifest)))

(defn- affected-closure
  [manifests changed-paths changed-export-keys]
  (loop [affected (set changed-paths)
         target-keys (set (map second changed-export-keys))
         namespaces
         (into (set (keep (comp namespace symbol second)
                          changed-export-keys))
               (map second
                    (filter (fn [[_ name]] (not (str/includes? name "/")))
                            changed-export-keys)))]
    (let [affected-manifests (keep manifests affected)
          target-keys
          (into target-keys
                (mapcat (comp #(map second %) :exported-keys))
                affected-manifests)
          namespaces (into namespaces (mapcat exported-namespaces)
                           affected-manifests)
          dependents
          (into #{}
                (keep (fn [[path manifest]]
                        (when (and (not (contains? affected path))
                                   (or (seq (set/intersection
                                             target-keys
                                             (:referenced-targets manifest)))
                                       (seq (set/intersection
                                             namespaces
                                             (:imported-namespaces manifest)))))
                          path)))
                manifests)
          next-affected (into affected dependents)]
      (if (= affected next-affected)
        affected
        (recur next-affected target-keys namespaces)))))

(defn- clojure-source? [file]
  (contains? clj-kondo/clojure-languages (:language file)))

(defn- closure-candidate
  [graph project config progress]
  (let [{:keys [files diagnostics]}
        (files/discover project config supported-languages)
        active (active-manifests graph project)
        old-files (:files active)]
    (when (and active (= (set (keys old-files))
                         (set (keys (graph-read/files-by-path
                                    (store/database graph))))))
      (let [by-path (into {} (map (juxt :relative-path identity)) files)
            changed-paths
            (into #{}
                  (keep (fn [[path file]]
                          (when (not= (ids/content-hash (:content file))
                                      (get-in old-files [path :content-hash]))
                            path)))
                  by-path)
            added-paths (set/difference (set (keys by-path))
                                        (set (keys old-files)))
            changed-paths (into changed-paths added-paths)
            deleted-paths (set/difference (set (keys old-files))
                                          (set (keys by-path)))
            changed-files (mapv by-path (sort changed-paths))]
        ;; Janet and data adapters currently require whole-catalog context.
        (when (and (seq (into changed-paths deleted-paths))
                   (every? clojure-source? changed-files)
                   (every? #(contains? clj-kondo/clojure-languages
                                       (get-in old-files [% :output :file
                                                          :file/language]))
                           deleted-paths))
          (let [changed-snapshot (clj-kondo/analyze! project changed-files)
                old-export-keys
                (into #{} (mapcat #(get-in old-files [% :exported-keys]))
                      (into changed-paths deleted-paths))
                changed-export-keys
                (into old-export-keys (snapshot-exported-keys changed-snapshot))
                affected-paths
                (affected-closure old-files
                                  (into changed-paths deleted-paths)
                                  changed-export-keys)
                affected-existing (set/intersection affected-paths
                                                    (set (keys by-path)))
                affected-files (mapv by-path (sort affected-existing))
                snapshot (if (= (set changed-paths) affected-existing)
                           changed-snapshot
                           (clj-kondo/analyze! project affected-files))
                unaffected-paths (set/difference (set (keys by-path))
                                                 affected-existing)
                external-symbols
                (into []
                      (comp (map #(get-in old-files [% :output :entities]))
                            cat
                            (filter #(= :entity.type/symbol (:entity/type %))))
                      unaffected-paths)
                fresh (project-analyzer/analyze
                       project affected-files progress
                       {:clojure-snapshot snapshot
                        :external-symbols external-symbols
                        :defer-finalization? true})
                fresh-by-path
                (into {} (map (juxt (comp :file/path :file) identity))
                      (:outputs fresh))
                raw-outputs
                (mapv (fn [{:keys [relative-path]}]
                        (or (get fresh-by-path relative-path)
                            (get-in old-files [relative-path :output])))
                      files)
                outputs (project-analyzer/finalize-outputs files raw-outputs)
                preserved (filterv :preserve? outputs)]
            (when (seq preserved)
              (throw
               (ex-info
                "Incremental analysis produced an incomplete snapshot; existing graph was preserved"
                {:exit-code 1 :type :analysis/incomplete-snapshot
                 :files (mapv (comp :file/path :file) preserved)
                 :diagnostics (vec (mapcat :diagnostics preserved))})))
            {:started (System/nanoTime)
             :files files
             :file-count (count files)
             :source-inventory (full/source-inventory files)
             :outputs outputs
             :entities (vec (mapcat (fn [{:keys [file entities]}]
                                      (cons file entities))
                                    outputs))
             :analysis-metrics
             (assoc (:analysis-metrics fresh)
                    :incremental-closure
                    {:changed (count changed-paths)
                     :deleted (count deleted-paths)
                     :affected (count affected-existing)
                     :reused (count unaffected-paths)})
             :analyzers (expected-analyzers project)
             :diagnostics (vec (concat diagnostics (:diagnostics fresh)
                                       (mapcat :diagnostics outputs)))}))))))

(defn commit-candidate!
  "Persist only changed/deleted files from a fully prepared candidate. The
  caller coordinates this mutation boundary."
  [graph project config candidate]
  ;; Incremental mutation cannot recover an interrupted or format-incompatible
  ;; full snapshot because it has no authoritative old baseline to preserve.
  (store/assert-query-compatible! graph)
  (let [files (:files candidate)
        scanned (set (map :relative-path files))
        existing (graph-read/files-by-path (store/database graph))
        outputs (:outputs candidate)
        metadata (store/graph-metadata graph)
        fingerprint-compatible?
        (= canonical-hash/contract-version
           (:llm-context/semantic-fingerprint-version metadata))
        changed (if fingerprint-compatible?
                  (filterv #(changed-output? existing %) outputs)
                  outputs)
        deleted (->> (keys existing) (remove scanned) sort vec)
        updating? (boolean (or (seq changed) (seq deleted)))
        metadata (when updating? metadata)]
    ;; File replacements are individually atomic, but a cross-file semantic
    ;; snapshot may span several of them. Mark the graph unavailable before
    ;; the first mutation so a process interruption cannot advertise a mixed
    ;; project revision as ready.
    (when updating?
      (store/begin-full-replacement! graph))
    (doseq [path deleted]
      (let [file-id (get-in existing [path :id])]
        (if (semantic-reconcile/enabled? config)
          (store/delete-file-and-mark!
           graph file-id
           [(semantic-reconcile/dirty-entity file-id nil :delete)])
          (store/delete-file! graph file-id))))
    (doseq [{:keys [file entities]} changed]
      (if (semantic-reconcile/enabled? config)
        (store/replace-file-and-mark!
         graph file entities
         [(semantic-reconcile/dirty-entity
           (:file/id file) (:file/content-hash file) :upsert)])
        (store/replace-file! graph file entities)))
    (store/prune-orphan-topics! graph)
    (when updating?
      (reactivate-metadata! graph config candidate))
    (let [graph-revision (semantic-document/graph-revision
                          (store/database graph))
          manifest-index (manifest/write! project candidate graph-revision)
          result
          {:mode :incremental
           :files (count files)
           :changed (count changed)
           :deleted (count deleted)
           :entities (reduce + 0 (map #(inc (count (:entities %))) changed))
           :analysis-metrics (:analysis-metrics candidate)
           :analyzers (:analyzers candidate)
           :manifest {:version (:version manifest-index)
                      :graph-revision graph-revision
                      :files (count (:files manifest-index))}
           :diagnostics (:diagnostics candidate)
           :started (:started candidate)}]
      result)))

(defn finish-candidate!
  [graph project config result]
  (let [semantic-plan (semantic-reconcile/reconcile! graph project config)]
    (-> result
        (dissoc :started)
        (assoc :semantic semantic-plan)
        (update :diagnostics into (:diagnostics semantic-plan)))))

(defn unchanged-result
  "Return the no-analyzer result when source inventory and every active
  analyzer contract match, otherwise nil."
  [graph project config]
  (store/assert-query-compatible! graph)
  (let [{:keys [files diagnostics]}
        (files/discover project config supported-languages)
        existing (graph-read/files-by-path (store/database graph))
        metadata (store/graph-metadata graph)
        expected (expected-contracts project config)
        active (active-manifests graph project)]
    (when (and (inventory-unchanged? files existing)
               (contracts-compatible? metadata expected)
               active)
      (let [semantic-plan (semantic-reconcile/reconcile! graph project config)]
        {:mode :incremental
         :files (count files)
         :changed 0
         :deleted 0
         :entities 0
         :analysis-metrics {:short-circuit true
                            :discovered-files (count files)}
         :semantic semantic-plan
         :analyzers
         {:clj-kondo
          {:version clj-kondo/analyzer-version
           :configuration-fingerprint
           (:llm-context/analyzer-configuration-fingerprint expected)}
          :janet {:catalog-version janet/catalog-version}
          :semantic-fingerprint {:version canonical-hash/contract-version}}
         :diagnostics (vec (concat diagnostics
                                   (:diagnostics semantic-plan)))}))))

(defn prepare-current
  "Return an unchanged result or a source-current closure/full candidate."
  [graph project config progress]
  (if-let [unchanged (unchanged-result graph project config)]
    {:complete-result unchanged}
    (let [closure (closure-candidate graph project config progress)
          candidate (or closure
                        (full/prepare-current project config progress
                                              :incremental))]
      (if (and closure (full/stale-candidate? project config closure))
        (full/prepare-current project config progress :incremental)
        candidate))))

(defn- analyze-graph! [graph project config]
  (let [prepared (prepare-current graph project config nil)]
    (cond
      (:complete-result prepared) (:complete-result prepared)
      (:stale? prepared) prepared
      :else
      (finish-candidate!
       graph project config
       (commit-candidate! graph project config prepared)))))

(defn analyze!
  "Run authoritative project analyzers, then persist only source or semantic
  fingerprints that changed."
  ([project config]
   (store/with-store [graph project config]
     (analyze-graph! graph project config)))
  ([graph project config]
   (analyze-graph! graph project config)))
