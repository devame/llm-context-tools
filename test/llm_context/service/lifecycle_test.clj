(ns llm-context.service.lifecycle-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.project :as project]
            [llm-context.service.lifecycle :as lifecycle])
  (:import [java.nio.file Files LinkOption]))

(defn- temporary-project [prefix]
  (project/context
   (str
    (Files/createTempDirectory
     prefix (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest descriptor-publication-is-complete-and-readable
  (let [project (temporary-project "llm-context-descriptor-")
        descriptor {:transport :tcp :host "127.0.0.1" :port 1234
                    :token "token" :instance-id "instance"}]
    (Files/createDirectories
     (:state-dir project)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (lifecycle/write-descriptor! project descriptor)
    (is (= descriptor (:value (lifecycle/descriptor-snapshot project))))
    (is (:valid? (lifecycle/descriptor-snapshot project)))))

(deftest stale-reclamation-compares-the-advertisement-under-the-lock
  (let [project (temporary-project "llm-context-compare-delete-")
        old-content (pr-str {:transport :tcp :port 1 :token "old"})
        current {:transport :tcp :port 2 :token "new"}]
    (Files/createDirectories
     (:state-dir project)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (lifecycle/write-descriptor! project current)
    (is (= :changed
           (:status (lifecycle/reclaim-stale! project old-content))))
    (is (= current (:value (lifecycle/descriptor-snapshot project))))))

(deftest shutdown-cleanup-only-deletes-the-owning-instance
  (let [project (temporary-project "llm-context-owner-delete-")
        descriptor {:transport :tcp :host "127.0.0.1" :port 1234
                    :token "token" :instance-id "new-owner"}]
    (Files/createDirectories
     (:state-dir project)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (lifecycle/write-descriptor! project descriptor)
    (is (nil? (lifecycle/delete-owned! project "old-owner")))
    (is (Files/exists (lifecycle/descriptor-path project)
                      (make-array LinkOption 0)))
    (is (true? (lifecycle/delete-owned! project "new-owner")))
    (is (not (Files/exists (lifecycle/descriptor-path project)
                           (make-array LinkOption 0))))))
