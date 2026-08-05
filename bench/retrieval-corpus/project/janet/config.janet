(defn read-runtime-settings
  "Read raw runtime settings from disk."
  [path]
  (slurp path))

(defn validate-runtime-settings
  "Check that runtime settings contain a service port and storage path."
  [settings]
  (and (get settings :port) (get settings :storage-path)))

(defn load-runtime-settings
  "Read and validate runtime settings before starting the Janet service."
  [path]
  (let [settings (read-runtime-settings path)]
    (if (validate-runtime-settings settings)
      settings
      (error "invalid runtime settings"))))

(defn load-runtime-cache
  "Read cached runtime values without validating service settings."
  [cache key]
  (get cache key))
