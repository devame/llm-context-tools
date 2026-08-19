(ns llm-context.semantic-ingestion-qualification-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic-ingestion-qualification :as qualification])
  (:import [java.nio.file Files]))

(defn- temp-project []
  (project/context
   (str (Files/createTempDirectory
         "llm-context-ingestion-qualification-test-"
         (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest declared-matrix-covers-request-and-concurrency-candidates
  (is (= #{[32 1] [32 2] [128 1] [128 2]
           [300 1] [300 2] [512 1] [512 2]}
         (set (map (juxt :request-provider-document-limit
                         :request-concurrency-limit)
                   qualification/matrix)))))

(deftest qualification-destination-cannot-overlap-live-index
  (let [project (temp-project)
        settings (get-in (config/defaults) [:semantic :lateon-code])
        active (.resolve (:root project) (:index-path settings))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"overlaps"
         (qualification/assert-isolated-destination!
          project settings active)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"overlaps"
         (qualification/assert-isolated-destination!
          project settings (.resolve active "child"))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"overlaps"
         (qualification/assert-isolated-destination!
          project settings (:state-dir project))))))

(deftest safe-report-removes-private-paths-source-and-identifiers
  (let [safe
        (qualification/safe-report
         {:documents [{:text "private source"}]
          :sample-symbol-ids ["symbol:private"]
          :source-paths ["/home/private/project"]
          :cases [{:status :failed
                   :diagnostic-path "/home/private/temp-index"
                   :request-provider-document-limit 32}]})
        rendered (pr-str safe)]
    (is (not (re-find #"private|/home" rendered)))
    (is (= [{:status :failed :request-provider-document-limit 32}]
           (:cases safe)))))

(deftest ranking-keeps-only-exact-successful-cases
  (is (= [128 32]
         (mapv :request-provider-document-limit
               (qualification/rank-cases
                [{:status :completed :duplicate-metadata 0
                  :wall-ms 20 :request-provider-document-limit 32
                  :request-concurrency-limit 1}
                 {:status :failed :duplicate-metadata 0
                  :wall-ms 1 :request-provider-document-limit 300
                  :request-concurrency-limit 1}
                 {:status :completed :duplicate-metadata 1
                  :wall-ms 2 :request-provider-document-limit 512
                  :request-concurrency-limit 1}
                 {:status :completed :duplicate-metadata 0
                  :wall-ms 10 :request-provider-document-limit 128
                  :request-concurrency-limit 2}])))))
