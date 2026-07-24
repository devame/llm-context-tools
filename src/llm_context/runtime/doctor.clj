(ns llm-context.runtime.doctor
  (:require [clojure.string :as str]
            [llm-context.config :as config]
            [llm-context.semantic.artifacts :as artifacts]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.runtime :as semantic-runtime]
            [llm-context.service.client :as service-client]
            [llm-context.store :as store])
  (:import [java.nio.file Files LinkOption Path Paths]))

(def minimum-java-feature 23)

(defn java-feature
  ([] (.feature (Runtime/version)))
  ([version]
   (let [parts (str/split (str version) #"\.")
         major (if (= "1" (first parts)) (second parts) (first parts))]
     (Long/parseLong (re-find #"\d+" major)))))

(defn- path-candidates [executable]
  (let [path (or (System/getenv "PATH") "")
        extensions (if (str/starts-with? (str/lower-case (System/getProperty "os.name"))
                                         "windows")
                     ["" ".exe" ".cmd" ".bat"]
                     [""])]
    (for [directory (str/split path (re-pattern (java.io.File/pathSeparator)))
          extension extensions]
      (Paths/get directory (into-array String [(str executable extension)])))))

(defn executable? [executable]
  (boolean
   (some #(and (Files/isRegularFile ^Path % (make-array LinkOption 0))
               (Files/isExecutable ^Path %))
         (path-candidates executable))))

(defn check
  "Return structured runtime checks. Optional semantic providers are reported
  without making structural analysis unavailable."
  [project config]
  (let [java (java-feature)
        java-check {:check :java
                    :required? true
                    :ok? (>= java minimum-java-feature)
                    :detail (str "JDK " java " (requires " minimum-java-feature "+)")}
        writable-check {:check :project-writable
                        :required? true
                        :ok? (Files/isWritable ^Path (:root project))
                        :detail (:root-str project)}
        datalevin-check (try
                          (store/with-store [graph project config]
                            (store/query graph '[:find ?e :where [?e :entity/type _]] []))
                          {:check :datalevin :required? true :ok? true
                           :detail (str (.resolve ^Path (:root project)
                                                 (get-in config [:store :path])))}
                          (catch Throwable error
                            {:check :datalevin :required? true :ok? false
                             :detail (.getMessage error)}))
        scip-command (first (get-in config [:semantic :scip-typescript-command]))
        scip-check {:check :scip-typescript
                    :required? false
                    :ok? (and scip-command (executable? scip-command))
                    :detail (if scip-command
                              (str "launcher " scip-command)
                              "provider disabled")}
        lateon-enabled? (semantic-reconcile/enabled? config)
        lateon-settings (get-in config [:semantic :lateon-code])
        executable (when lateon-enabled?
                     (semantic-runtime/find-executable
                      (first (:next-plaid-command lateon-settings))))
        runtime-check
        {:check :next-plaid-api
         :required? false
         :ok? (or (not lateon-enabled?) (some? executable))
         :detail
         (cond
           (not lateon-enabled?) "provider disabled"
           executable
           (str executable " (requires "
                artifacts/next-plaid-version ")")
           :else
           (str (first (:next-plaid-command lateon-settings))
                " not found"))}
        onnx-path (when executable
                    (semantic-runtime/onnx-runtime-path executable))
        onnx-check
        {:check :onnx-runtime
         :required? false
         :ok? (or (not lateon-enabled?) (some? onnx-path))
         :detail
         (cond
           (not lateon-enabled?) "provider disabled"
           onnx-path
           (str onnx-path " (requires "
                artifacts/onnx-runtime-version ")")
           :else "library not found beside next-plaid-api")}
        model-path (when lateon-enabled?
                     (semantic-runtime/model-path project lateon-settings))
        model-verification
        (when model-path
          (artifacts/verify-model model-path))
        model-ok? (and model-path
                       (empty? (:missing model-verification))
                       (empty? (:mismatched model-verification)))
        model-check
        {:check :lateon-model
         :required? false
         :ok? (or (not lateon-enabled?) model-ok?)
         :detail
         (cond
           (not lateon-enabled?) "provider disabled"
           model-ok?
           (str model-path " @ "
                (subs artifacts/model-revision 0 12))
           (seq (:missing model-verification))
           (str "missing "
                (str/join ", " (:missing model-verification))
                " below " model-path)
           :else
           (str "checksum mismatch: "
                (str/join ", " (:mismatched model-verification))))}
        service-response
        (when (service-client/available? project)
          (service-client/request project {:op :semantic-status}))
        service-runtime (get-in service-response [:value :runtime])
        worker-failed? (= :failed (:worker-status service-runtime))
        service-check
        {:check :project-service
         :required? false
         :ok? (if lateon-enabled?
                (and (= :ready (:status service-runtime))
                     (not worker-failed?))
                (boolean (:ok service-response)))
         :detail
         (cond
           (not (:ok service-response)) "not running"
           worker-failed?
           (str "running; LateOn ready; worker failed"
                (when-let [detail (:worker-detail service-runtime)]
                  (str ": " detail)))
           lateon-enabled?
           (str "running; LateOn "
                (name (or (:status service-runtime) :unknown))
                "; worker "
                (name (or (:worker-status service-runtime) :unknown)))
           :else "running")}]
    [java-check writable-check datalevin-check scip-check runtime-check
     onnx-check model-check service-check]))

(defn healthy? [checks]
  (every? #(or (not (:required? %)) (:ok? %)) checks))

(defn print-report [checks]
  (doseq [{:keys [check required? ok? detail]} checks]
    (println (format "%-5s %-20s %s%s"
                     (if ok? "ok" "fail")
                     (name check)
                     detail
                     (if required? "" " (optional)")))))
