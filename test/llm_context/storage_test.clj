(ns llm-context.storage-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.storage :as storage])
  (:import [java.nio.file Files]))

(defn temp-project []
  (project/context
   (str (Files/createTempDirectory
         "llm-context-storage-"
         (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest explicit-probe-path-controls-storage-guard
  (let [project (temp-project)
        safe (-> (config/defaults)
                 (assoc-in [:store :free-space-probe-path] ".")
                 (assoc-in [:store :minimum-free-space-bytes] 0))
        unsafe (assoc-in safe [:store :minimum-free-space-bytes]
                         Long/MAX_VALUE)
        status (storage/assert-headroom! project safe :test-write)]
    (is (= (str (:root project)) (:probe-path status)))
    (is (:safe? status))
    (let [error (try
                  (storage/assert-headroom! project unsafe :test-write)
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= :store/insufficient-space (:type (ex-data error))))
      (is (= :test-write (:operation (ex-data error))))
      (is (= 1 (:exit-code (ex-data error)))))))
