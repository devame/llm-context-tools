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
