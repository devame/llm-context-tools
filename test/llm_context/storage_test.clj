(ns llm-context.storage-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.analysis.staging :as staging]
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

(deftest semantic-operation-probes-the-index-filesystem-ancestor
  (let [project (temp-project)
        settings (-> (config/defaults)
                     (assoc-in [:store :minimum-free-space-bytes] 0)
                     (assoc-in [:semantic :lateon-code :index-path]
                               ".llm-context/semantic/missing-index"))
        semantic-parent (.resolve (:state-dir project) "semantic")]
    (Files/createDirectories semantic-parent
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (let [guard (storage/operation-guard project settings
                                         :semantic-indexing
                                         #{:semantic-index})
          snapshot (storage/assert-operation-safe! guard)]
      (is (= (str (.toRealPath semantic-parent
                               (make-array java.nio.file.LinkOption 0)))
             (:probe-path snapshot)))
      (is (= (str (.resolve semantic-parent "missing-index"))
             (:probe-target snapshot))))))

(deftest filesystem-probe-resolves-symbolic-link-ancestors
  (let [project (temp-project)
        target (Files/createTempDirectory
                "llm-context-storage-target-"
                (make-array java.nio.file.attribute.FileAttribute 0))
        link (.resolve (:root project) "linked-index")]
    (Files/createSymbolicLink link target
                              (make-array java.nio.file.attribute.FileAttribute 0))
    (is (= (str (.toRealPath target
                             (make-array java.nio.file.LinkOption 0)))
           (str (storage/probe-path project (config/defaults)
                                    (.resolve link "missing")))))))

(deftest operation-growth-guard-stops-before-the-next-write-unit
  (let [project (temp-project)
        settings (-> (config/defaults)
                     (assoc-in [:store :minimum-free-space-bytes] 0)
                     (assoc-in [:store :maximum-operation-growth-bytes] 4)
                     (assoc-in [:store :storage-sample-interval-ms] 1))
        guard (storage/operation-guard project settings :fixture #{:graph})
        graph-file (.resolve (:db-dir project) "growth")]
    (Files/createDirectories (:db-dir project)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString graph-file "12345" (make-array OpenOption 0))
    (Thread/sleep 2)
    (let [error (try
                  (storage/assert-operation-safe! guard)
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (= :store/operation-growth-limit (:type (ex-data error))))
      (is (= 5 (:operation-growth-bytes (ex-data error))))
      (is (= {:graph 5} (:component-growth-bytes (ex-data error))))
      (is (= 4 (:maximum-operation-growth-bytes (ex-data error)))))))

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

(deftest retention-cleanup-requires-markers-and-preserves-newest-artifacts
  (let [project (temp-project)
        settings (config/defaults)
        maintenance (.resolve (:state-dir project) "maintenance")
        old (.resolve maintenance "graph-copy-old")
        newest (.resolve maintenance "graph-copy-new")
        unmarked (.resolve maintenance "do-not-delete")
        old-time (- (System/currentTimeMillis) (* 40 24 60 60 1000))]
    (doseq [path [old newest unmarked]]
      (Files/createDirectories path
                               (make-array java.nio.file.attribute.FileAttribute 0))
      (Files/writeString (.resolve path "data") "x" (make-array OpenOption 0)))
    (Files/writeString
     (.resolveSibling old "graph-copy-old.verified.edn")
     (pr-str {:artifact/type :verified-compact-copy :artifact/format 1
              :artifact/path (str old) :artifact/created-at old-time})
     (make-array OpenOption 0))
    (Files/writeString
     (.resolveSibling newest "graph-copy-new.verified.edn")
     (pr-str {:artifact/type :verified-compact-copy :artifact/format 1
              :artifact/path (str newest)
              :artifact/created-at (System/currentTimeMillis)})
     (make-array OpenOption 0))
    (let [plan (storage/cleanup-plan project settings 30)]
      (is (= 1 (:eligible-count plan)))
      (is (= (str old) (:path (first (filter :eligible? (:candidates plan))))))
      (is (Files/exists old (make-array java.nio.file.LinkOption 0))))
    (storage/apply-cleanup! project settings 30)
    (is (not (Files/exists old (make-array java.nio.file.LinkOption 0))))
    (is (Files/exists newest (make-array java.nio.file.LinkOption 0)))
    (is (Files/exists unmarked (make-array java.nio.file.LinkOption 0)))))

(deftest retention-cleanup-removes-old-staging-but-keeps-newest-partial
  (let [project (temp-project)
        settings (config/defaults)
        staging-root (staging/root-path project settings)
        old (.resolve staging-root "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        newest (.resolve staging-root "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        old-time (- (System/currentTimeMillis) (* 40 24 60 60 1000))]
    (doseq [path [old newest]]
      (Files/createDirectories path
                               (make-array java.nio.file.attribute.FileAttribute 0))
      (Files/writeString (.resolve path "partial") "x" (make-array OpenOption 0)))
    (Files/setLastModifiedTime old
                               (java.nio.file.attribute.FileTime/fromMillis
                                old-time))
    (Files/setLastModifiedTime (.resolve old "partial")
                               (java.nio.file.attribute.FileTime/fromMillis
                                old-time))
    (let [plan (storage/cleanup-plan project settings 30)
          staging-candidates (filter #(= :analysis-staging (:component %))
                                     (:candidates plan))]
      (is (= 2 (count staging-candidates)))
      (is (:eligible? (first (filter #(= (str old) (:path %))
                                    staging-candidates))))
      (is (:protected? (first (filter #(= (str newest) (:path %))
                                     staging-candidates)))))
    (storage/apply-cleanup! project settings 30)
    (is (not (Files/exists old (make-array java.nio.file.LinkOption 0))))
    (is (Files/exists newest (make-array java.nio.file.LinkOption 0)))))
