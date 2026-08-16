(ns llm-context.storage-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.storage :as storage])
  (:import [java.nio.file Files OpenOption]))

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

(deftest inventory-measures-only-allowlisted-generated-components
  (let [project (temp-project)
        settings (-> (config/defaults)
                     (assoc-in [:store :minimum-free-space-bytes] 0))
        graph-file (.resolve (:db-dir project) "data.bin")
        log-file (.resolve (:state-dir project) "logs/service.log")
        unrelated (.resolve (:root project) "large-source.bin")]
    (doseq [path [graph-file log-file unrelated]]
      (Files/createDirectories (.getParent path)
                               (make-array java.nio.file.attribute.FileAttribute 0)))
    (Files/writeString graph-file "graph" (make-array OpenOption 0))
    (Files/writeString log-file "log-data" (make-array OpenOption 0))
    (Files/writeString unrelated "not-generated" (make-array OpenOption 0))
    (let [result (storage/inventory project settings)
          components (into {} (map (juxt :component identity)
                                   (:components result)))]
      (is (:safe? (:storage result)))
      (is (= 5 (get-in components [:graph :bytes])))
      (is (= 1 (get-in components [:graph :files])))
      (is (= 8 (get-in components [:logs :bytes])))
      (is (= 1 (get-in components [:logs :files])))
      (is (false? (get-in components [:maintenance :exists?])))
      (is (not-any? #(= (str unrelated) (:path %)) (:components result))))))
