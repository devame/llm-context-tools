(ns llm-context.runtime.doctor
  (:require [clojure.string :as str]
            [llm-context.accelerator :as accelerator]
            [clojure.java.io :as io]
            [llm-context.analysis.clj-kondo :as clj-kondo]
            [llm-context.analysis.janet :as janet]
            [llm-context.config :as config]
            [llm-context.graph.read :as graph-read]
            [llm-context.health :as health]
            [llm-context.intent.router :as intent-router]
            [llm-context.model.schema :as schema]
            [llm-context.parser.jtreesitter :as jtreesitter]
            [llm-context.parser.provider :as parser]
            [llm-context.semantic.artifacts :as artifacts]
            [llm-context.semantic.reconcile :as semantic-reconcile]
            [llm-context.semantic.runtime :as semantic-runtime]
            [llm-context.semantic.state :as semantic-state]
            [llm-context.service.client :as service-client]
            [llm-context.service.contract :as service-contract]
            [llm-context.service.progress :as analysis-progress]
            [llm-context.storage :as storage]
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
  without making exact graph analysis unavailable."
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
        storage-check
        (try
          (let [{:keys [safe? usable-bytes minimum-free-space-bytes probe-path]}
                (storage/status project config)]
            {:check :storage-capacity :required? true :ok? safe?
             :detail
             (format "%.1f GiB usable at %s; %.1f GiB reserve required"
                     (storage/gibibytes usable-bytes) probe-path
                     (storage/gibibytes minimum-free-space-bytes))})
          (catch Throwable error
            {:check :storage-capacity :required? true :ok? false
             :detail (.getMessage error)}))
        graph-state (atom nil)
        semantic-summary (atom nil)
        datalevin-check (try
                          (store/with-store [graph project config]
                            (graph-read/any-entity? (store/database graph))
                            (reset! graph-state (store/graph-state graph))
                            (when (= :ready @graph-state)
                              (reset! semantic-summary
                                      (semantic-state/semantic-summary
                                       graph semantic-reconcile/provider
                                       (System/currentTimeMillis)))))
                          {:check :datalevin :required? true :ok? true
                           :detail (str (.resolve ^Path (:root project)
                                                 (get-in config [:store :path])))}
                          (catch Throwable error
                            {:check :datalevin :required? true :ok? false
                             :detail (.getMessage error)}))
        clj-kondo-check
        {:check :clj-kondo :required? true :ok? true
         :detail (str "embedded " clj-kondo/analyzer-version)}
        janet-catalog-check
        (let [resource (io/resource janet/catalog-resource)]
          {:check :janet-catalog :required? true :ok? (some? resource)
           :detail (if resource
                     (str "Janet " janet/catalog-version)
                     (str "missing " janet/catalog-resource))})
        janet-grammar-check
        (try
          (with-open [provider (jtreesitter/open project)]
            (parser/parse-source provider :language/janet "(def x 1)"))
          {:check :janet-grammar :required? true :ok? true
           :detail "packaged Tree-sitter Janet grammar"}
          (catch Throwable error
            {:check :janet-grammar :required? true :ok? false
             :detail (.getMessage error)}))
        graph-format-check
        {:check :graph-format
         :required? true
         :ok? (not= :incompatible @graph-state)
         :detail
         (case @graph-state
           :ready (str "format " schema/graph-format-version)
           :empty (str "uninitialized; analyze --full creates format "
                       schema/graph-format-version)
           :incompatible
           (str "incompatible; run llm-context analyze --full for format "
                schema/graph-format-version)
           "unavailable")}
        lateon-enabled? (semantic-reconcile/enabled? config)
        lateon-settings (get-in config [:semantic :lateon-code])
        executable (when lateon-enabled?
                     (semantic-runtime/find-executable
                      (first (:next-plaid-command lateon-settings))))
        runtime-check
        {:check :next-plaid-api
         :required? lateon-enabled?
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
         :required? lateon-enabled?
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
         :required? lateon-enabled?
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
        accelerator-check
        (let [selection
              (when (and lateon-enabled? executable model-path)
                (try
                  (accelerator/resolve-runtime lateon-settings executable
                                               model-path)
                  (catch clojure.lang.ExceptionInfo error
                    {:error error})))]
          {:check :semantic-accelerator
           :required? lateon-enabled?
           :warning? (and selection
                          (seq (:fallback-reasons selection)))
           :ok? (or (not lateon-enabled?)
                    (and selection (nil? (:error selection))))
           :detail
           (cond
             (not lateon-enabled?) "provider disabled"
             (nil? executable) "NextPlaid executable unavailable"
             (nil? model-path) "model path unavailable"
             (:error selection) (.getMessage ^Throwable (:error selection))
             :else (accelerator/describe selection))})
        cuda-host-check
        (let [cuda-requested? (contains? #{:auto :cuda}
                                         (:accelerator lateon-settings))
              host (when (and lateon-enabled? cuda-requested?)
                     (accelerator/cuda-host-readiness lateon-settings
                                                      executable))]
          {:check :cuda-host
           :required? false
           :warning? (and host (not (:ready? host)))
           :ok? true
           :detail
           (cond
             (not lateon-enabled?) "provider disabled"
             (not cuda-requested?) "CUDA not requested; configured for CPU"
             :else (accelerator/describe-host host))})
        router-settings (get-in config [:context :query-router])
        router-enabled? (:enabled router-settings)
        reranker-enabled? (get-in config [:context :candidate-reranker
                                          :enabled])
        advisory-enabled? (or router-enabled? reranker-enabled?)
        router-model-path (when advisory-enabled?
                            (semantic-runtime/model-path project router-settings))
        router-verification
        (when router-model-path
          (artifacts/verify-query-router-model router-model-path))
        router-model-ok?
        (and router-model-path
             (empty? (:missing router-verification))
             (empty? (:mismatched router-verification)))
        router-model-check
        {:check :query-router-model
         :required? (boolean advisory-enabled?)
         :ok? (or (not advisory-enabled?) router-model-ok?)
         :detail
         (cond
           (not advisory-enabled?) "router and reranker disabled"
           router-model-ok?
           (str router-model-path " @ "
                (subs artifacts/query-router-model-revision 0 12))
           (seq (:missing router-verification))
           (str "missing " (str/join ", " (:missing router-verification))
                " below " router-model-path)
           :else
           (str "checksum mismatch: "
                (str/join ", " (:mismatched router-verification))))}
        descriptor (service-client/descriptor project)
        service-present? (or (some? descriptor)
                             (service-client/available? project))
        service-compatibility (if descriptor
                                (service-contract/compatibility descriptor)
                                (if service-present? :compatible :absent))
        service-response
        (when service-present?
          (or (service-client/request project {:op :semantic-status})
              {:ok false :type :service/unreachable
               :error "stale service descriptor was reclaimed"}))
        live-status (when (:ok service-response) (:value service-response))
        service-runtime (:runtime live-status)
        operational-health
        (or (:health live-status)
            (when live-status
              (health/semantic-health
               (assoc live-status
                      :analysis-progress (analysis-progress/read-state project))
               (System/currentTimeMillis))))
        local-status @semantic-summary
        status (or live-status local-status)
        desired (long (or (:desired status) 0))
        operationally-required? (and lateon-enabled?
                                      (or service-present?
                                          (and (= :ready @graph-state)
                                               (pos? desired))))
        service-version-check
        {:check :service-version
         :required? (boolean service-present?)
         :ok? (or (not service-present?)
                  (= :compatible service-compatibility))
         :detail (if service-present?
                   (str (name service-compatibility) "; CLI "
                        (:application-version
                         (service-contract/runtime-identity))
                        ", service "
                        (or (:application-version descriptor) "unknown")
                        ", protocol "
                        (or (:protocol-version descriptor) "unknown"))
                   "no resident service descriptor")}
        analysis-state (analysis-progress/read-state project)
        analysis-check
        {:check :analysis-state
         :required? true
         :ok? (not (contains? #{:failed :interrupted :unreadable}
                               (:state analysis-state)))
         :detail (str (name (:state analysis-state))
                      (when-let [error (:last-error analysis-state)]
                        (str ": " error)))}
        runtime-probe
        (when (and lateon-enabled? (not service-present?) executable onnx-path
                   model-ok?)
          (let [runtime (atom nil)]
            (try
              (let [started (semantic-runtime/start! project config)]
                (reset! runtime started)
                {:ok? (= :ready (:status started))
                 :runtime started})
              (catch Throwable error
                {:ok? false :error error})
              (finally
                (when @runtime
                  (semantic-runtime/stop! @runtime))))))
        execution-runtime (or service-runtime (:runtime runtime-probe))
        execution-check
        {:check :semantic-execution
         :required? lateon-enabled?
         :warning? (boolean (:recovery execution-runtime))
         :ok? (or (not lateon-enabled?)
                  (and (= :ready (:status execution-runtime))
                       (nil? (:recovery execution-runtime))))
         :detail
         (cond
           (not lateon-enabled?) "provider disabled"
           (:error runtime-probe) (.getMessage ^Throwable (:error runtime-probe))
           (nil? execution-runtime)
           "not probed because an enabled provider prerequisite failed"
           (:recovery execution-runtime)
           (str "ready after automatic "
                (name (get-in execution-runtime [:recovery :kind]))
                " recovery; using "
                (accelerator/describe (:inference execution-runtime)))
           :else
           (str (name (or (:status execution-runtime) :unknown))
                (when-let [detail (:detail execution-runtime)]
                  (str ": " detail))))}
        advisory-probe
        (when (and advisory-enabled? (not service-present?) router-model-ok?)
          (let [runtime (atom nil)]
            (try
              (let [started (intent-router/start! project config)]
                (reset! runtime started)
                {:ok? (= :ready (:status started)) :runtime started})
              (catch Throwable error {:ok? false :error error})
              (finally
                (when @runtime (intent-router/stop! @runtime))))))
        advisory-runtime (:runtime advisory-probe)
        advisory-check
        {:check :advisory-execution
         :required? (boolean advisory-enabled?)
         :warning? (boolean (:recovery advisory-runtime))
         :ok? (or (not advisory-enabled?)
                  (if service-present?
                    (and (or (not router-enabled?)
                             (= :ready (:query-router-status service-runtime)))
                         (or (not reranker-enabled?)
                             (= :ready
                                (:candidate-reranker-status service-runtime)))
                         (nil? (:query-router-recovery service-runtime)))
                    (and (= :ready (:status advisory-runtime))
                         (nil? (:recovery advisory-runtime)))))
         :detail
         (cond
           (not advisory-enabled?) "router and reranker disabled"
           (:error advisory-probe)
           (.getMessage ^Throwable (:error advisory-probe))
           service-present?
           (str "router "
                (name (or (:query-router-status service-runtime) :unknown))
                "; reranker "
                (name (or (:candidate-reranker-status service-runtime)
                          :unknown)))
           (:recovery advisory-runtime)
           "ready after automatic CUDA-to-CPU recovery"
           advisory-runtime (name (:status advisory-runtime))
           :else "not probed because a model prerequisite failed")}
        queue-failed (long (or (:failed status) 0))
        queue-dirty (long (or (:dirty status) 0))
        queue-check
        {:check :semantic-queue
         :required? operationally-required?
         :warning? (or (pos? queue-failed) (pos? queue-dirty))
         :ok? (or (not operationally-required?)
                  (and (zero? queue-failed) (zero? queue-dirty)))
         :detail (if status
                   (format "%d indexed, %d pending, %d leased, %d failed, %d dirty"
                           (long (or (:indexed status) 0))
                           (long (or (:pending status) 0))
                           (long (or (:leased status) 0))
                           queue-failed queue-dirty)
                   "semantic status unavailable")}
        health-check
        {:check :runtime-health
         :required? operationally-required?
         :warning? (boolean (and operational-health
                                  (health/unhealthy? operational-health)))
         :ok? (or (not operationally-required?)
                  (and operational-health
                       (not (health/unhealthy? operational-health))))
         :detail (if operational-health
                   (str (name (:state operational-health)) ": "
                        (:summary operational-health))
                   "no live operational health snapshot")}
        service-check
        {:check :project-service
         :required? operationally-required?
         :warning? (boolean
                    (or (and service-response (not (:ok service-response)))
                        (:runtime-diagnostic service-runtime)))
         :ok? (or (not operationally-required?)
                  (and (:ok service-response)
                       (= :compatible service-compatibility)
                       (= :ready (:status service-runtime))
                       (not= :failed (:worker-status service-runtime))))
         :detail
         (cond
           (not service-present?) "not running"
           (not (:ok service-response))
           (str (name (or (:type service-response) :unavailable)) ": "
                (:error service-response))
           :else
           (str "running; runtime "
                (name (or (:status service-runtime) :unknown))
                (when-let [detail (:detail service-runtime)]
                  (str ": " detail))
                "; worker "
                (name (or (:worker-status service-runtime) :unknown))
                (when-let [detail (:worker-detail service-runtime)]
                  (str ": " detail))
                "; watcher "
                (name (or (:watcher-status service-runtime) :unknown))
                (when-let [diagnostic (:runtime-diagnostic service-runtime)]
                  (str "; warning: " (:detail diagnostic)
                       "; action: " (:action diagnostic)))))}]
    [java-check writable-check storage-check clj-kondo-check janet-catalog-check
     janet-grammar-check datalevin-check graph-format-check runtime-check
     onnx-check model-check accelerator-check cuda-host-check
     router-model-check analysis-check service-version-check execution-check
     advisory-check
     service-check queue-check health-check]))

(defn healthy? [checks]
  (every? #(or (not (:required? %)) (:ok? %)) checks))

(defn print-report [checks]
  (doseq [{:keys [check required? ok? warning? detail]} checks]
    (println (format "%-5s %-20s %s%s"
                     (cond ok? (if warning? "warn" "ok")
                           :else "fail")
                     (name check)
                     detail
                     (if required? "" " (optional)")))))
