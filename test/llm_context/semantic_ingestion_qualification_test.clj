(ns llm-context.semantic-ingestion-qualification-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.semantic.index :as semantic-index]
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

(deftest custom-matrix-preserves-declared-order
  (is (= [{:request-provider-document-limit 128
           :request-concurrency-limit 1}
          {:request-provider-document-limit 160
           :request-concurrency-limit 1}
          {:request-provider-document-limit 192
           :request-concurrency-limit 1}]
         (qualification/qualification-matrix [128 160 192] [1]))))

(deftest qualification-arguments-accept-custom-sweeps
  (is (= [128 160 192]
         (:request-batches
          (#'qualification/parse-args
           ["/project" "--request-batches" "128,160,192"])))))
  (is (= [1 2]
         (:request-concurrencies
          (#'qualification/parse-args
           ["/project" "--request-concurrencies" "1,2,1"]))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"comma-separated positive integers"
       (#'qualification/parse-args
        ["/project" "--request-batches" "128,zero"])))

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

(deftest qualification-waits-for-cumulative-visibility-between-waves
  (let [documents [{:id "a"} {:id "b"}]
        events (atom [])]
    (with-redefs-fn
      {#'qualification/submit-wave!
       (fn [_ wave]
         (swap! events conj [:submit (mapv #(mapv :id %) wave)])
         {:elapsed-ms 1.0 :request-latencies-ms [1.0]})
       #'qualification/await-visible!
       (fn [_ submitted _]
         (swap! events conj [:visible (mapv :id submitted)])
         submitted)}
      #(let [result (#'qualification/submit-documents!
                     :client documents 1 1 1000)]
         (is (= [[:submit [["a"]]]
                 [:visible ["a"]]
                 [:submit [["b"]]]
                 [:visible ["a" "b"]]]
                @events))
         (is (= documents (:visible-documents result)))
         (is (= 2.0 (:submit-ms result)))))))

(deftest visibility-queries-each-multi-chunk-symbol-once
  (let [queries (atom [])]
    (with-redefs [semantic-index/indexed-documents
                  (fn [_ symbol-ids]
                    (swap! queries conj (vec symbol-ids))
                    [])]
      (#'qualification/visible-documents
       :client [{:symbol-id "a"} {:symbol-id "a"} {:symbol-id "b"}])
      (is (= [["a" "b"]] @queries)))))

(deftest visibility-polling-retries-transient-provider-errors
  (let [document {:id "a" :symbol-id "a"}
        calls (atom 0)]
    (with-redefs-fn
      {#'qualification/visible-documents
       (fn [_ _]
         (if (= 1 (swap! calls inc))
           (throw (ex-info "metadata is busy" {:status 500}))
           [document]))}
      #(is (= [document]
              (#'qualification/await-visible!
               :client [document] 1000))))
    (is (= 2 @calls))))

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
