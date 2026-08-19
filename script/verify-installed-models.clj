(ns verify-installed-models
  "Verify every model file recorded in an installed model registry."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [llm-context.dependencies :as dependencies]
            [llm-context.model-packages :as model-packages])
  (:import [java.io PushbackReader]))

(defn- read-edn [path]
  (with-open [reader (PushbackReader. (io/reader path))]
    (edn/read {:eof nil} reader)))

(defn -main [registry-path]
  (when-not registry-path
    (throw (ex-info "Usage: clojure -M script/verify-installed-models.clj REGISTRY"
                    {:exit-code 2})))
  (let [registry (read-edn registry-path)
        manifest (dependencies/all)]
    (when-not (= 1 (:contract-version registry))
      (throw (ex-info "Unsupported installed model registry contract"
                      {:exit-code 2})))
    (doseq [[role installed] (:roles registry)]
      (let [expected (get-in manifest [:roles role])]
        (when-not expected
          (throw (ex-info (str "Registry contains unknown model role: " role)
                          {:exit-code 2})))
        (when-not (= (:revision expected) (:revision installed))
          (throw (ex-info (str "Registry revision does not match the manifest for " role)
                          {:exit-code 2})))
        (model-packages/verify-package! (:path installed) expected)
        (println "Verified" (name role) "at" (:path installed))))
    (println "Installed model registry verified.")))

(apply -main *command-line-args*)
