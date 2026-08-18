(ns llm-context.semantic.runtime-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.runtime :as runtime])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute PosixFilePermission]))

(defn- temporary-project []
  (project/context
   (str
    (Files/createTempDirectory
     "llm-context-semantic-runtime-"
     (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest configured-model-path-is-resolved-against-the-project
  (let [project (temporary-project)
        settings (-> (config/defaults)
                     (get-in [:semantic :lateon-code])
                     (assoc :model-path "models/lateon"))]
    (is (= (.normalize (.resolve (:root project) "models/lateon"))
           (runtime/model-path project settings)))))

(deftest default-model-path-is-pinned-by-revision
  (let [project (temporary-project)
        settings (get-in (config/defaults) [:semantic :lateon-code])
        path (str (runtime/model-path project settings))]
    (is (.endsWith path
                   (str "lightonai--LateOn-Code/"
                        (:model-revision settings))))))

(deftest executable-lookup-accepts-an-explicit-executable
  (when-not (.startsWith
             (.toLowerCase (System/getProperty "os.name"))
             "windows")
    (let [path (Files/createTempFile
                "llm-context-next-plaid-" ""
                (make-array java.nio.file.attribute.FileAttribute 0))]
      (Files/setPosixFilePermissions
       path
       #{PosixFilePermission/OWNER_READ
         PosixFilePermission/OWNER_EXECUTE})
      (is (= (.toAbsolutePath path)
             (runtime/find-executable (str path))))
      (Files/deleteIfExists path))))

(deftest missing-runtime-components-degrade-with-an-actionable-reason
  (let [project (temporary-project)
        settings (-> (config/defaults)
                     (assoc-in [:semantic :lateon-code :next-plaid-command]
                               ["definitely-not-a-real-next-plaid-command"]))
        result (runtime/start! project settings)]
    (is (= :unavailable (:status result)))
    (is (= :executable-missing (:reason result)))
    (is (= "definitely-not-a-real-next-plaid-command"
           (:detail result)))
    (is (not (Files/exists
              (:state-dir project)
              (make-array LinkOption 0))))))

(deftest startup-diagnostic-extracts-provider-failure
  (let [log-path (Files/createTempFile
                  "llm-context-next-plaid-startup-" ".log"
                  (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/writeString
     log-path
     "INFO starting\nERROR next_plaid_api: CUDA support not compiled. Enable the 'cuda' feature.\n"
     (make-array java.nio.file.OpenOption 0))
    (is (= "ERROR next_plaid_api: CUDA support not compiled. Enable the 'cuda' feature."
           ((deref (get (ns-interns 'llm-context.semantic.runtime)
                        'startup-diagnostic))
            {:log-path log-path})))
    (Files/deleteIfExists log-path)))

(deftest runtime-diagnostic-explains-a-cuda-device-fallback
  (let [log-path (Files/createTempFile
                  "llm-context-next-plaid-runtime-" ".log"
                  (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/writeString
     log-path
     "ERROR ort::logging: CUDA failure 100: no CUDA-capable device is detected\n"
     (make-array java.nio.file.OpenOption 0))
    (let [diagnostic (runtime/runtime-diagnostic {:log-path log-path})]
      (is (= :cuda-device-unavailable (:kind diagnostic)))
      (is (= "CUDA was selected, but the runtime could not detect a CUDA-capable device."
             (:detail diagnostic)))
      (is (re-find #"fix NVIDIA/WSL CUDA device visibility"
                   (:action diagnostic))))
    (Files/deleteIfExists log-path)))
