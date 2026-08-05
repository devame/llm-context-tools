(ns corpus.config.project)

(defn read-project-config
  "Read raw project configuration from an EDN file."
  [read-edn path]
  (read-edn path))

(defn validate-project-config
  "Validate required analysis and semantic settings before startup."
  [config]
  (and (vector? (get-in config [:analysis :include]))
       (map? (:semantic config))))

(defn load-project-config
  "Load project configuration and fail closed when validation does not pass."
  [read-edn path]
  (let [config (read-project-config read-edn path)]
    (when-not (validate-project-config config)
      (throw (ex-info "Invalid project configuration" {:path path})))
    config))

(defn load-project-cache
  "Load cached project data; this does not parse or validate configuration."
  [cache path]
  (get cache path))
