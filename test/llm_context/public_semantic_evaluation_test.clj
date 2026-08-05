(ns llm-context.public-semantic-evaluation-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm-context.public-semantic-evaluation :as suite])
  (:import [java.nio.file Files LinkOption]))

(defn- temp-checkout []
  (Files/createTempDirectory
   "llm-context-public-process-"
   (make-array java.nio.file.attribute.FileAttribute 0)))

(deftest checked-in-manifest-has-the-three-public-repositories
  (let [manifest (suite/read-manifest "bench/public-semantic-evaluation/manifest.edn")]
    (is (= #{:clojure-lsp :re-frame :metabase}
           (set (map :id (:repositories manifest)))))
    (is (= 120
           (reduce + (map #(reduce + (vals (:expected-queries %)))
                          (:repositories manifest)))))
    (is (identical? manifest (suite/validate-manifest! manifest)))))

(deftest corpus-paths-are-anchored-to-the-manifest-directory
  (let [manifest (suite/read-manifest
                  "bench/public-semantic-evaluation/manifest.edn")
        repository (first (:repositories manifest))
        manifest-directory (::suite/manifest-directory (meta manifest))
        path (#'suite/corpus-path manifest-directory repository :development)]
    (is (Files/exists path (make-array LinkOption 0)))
    (is (.endsWith path "clojure-lsp/development.edn"))))

(deftest semantic-preflight-requires-loopback-and-complete-coverage
  (let [complete {:completeness :complete
                  :pending 0 :leased 0 :failed 0 :dirty 0
                  :runtime {:endpoint "http://127.0.0.1:12345"}}]
    (is (suite/synchronized-status? complete))
    (is (not (suite/synchronized-status?
              (assoc-in complete [:runtime :endpoint]
                        "http://192.0.2.10:12345"))))
    (is (not (suite/synchronized-status?
              (assoc complete :pending 1))))))

(deftest bootstrap-confidence-interval-is-seed-deterministic
  (is (= (suite/bootstrap-ci [0.0 0.5 1.0] 42)
         (suite/bootstrap-ci [0.0 0.5 1.0] 42)))
  (is (= {:low 1.0 :high 1.0}
         (suite/bootstrap-ci [1.0 1.0] 42))))

(deftest aggregation-is-public-metadata-only
  (let [rows [{:id :query/one :language :clojure :query-type :behavior
               :domain :auth :search-hit? true
               :search-ms 10 :context-ms 20
               :search-recall-at-10? true :search-recall-at-20? true
               :search-recall-at-50? true :reciprocal-rank 1.0 :ndcg 1.0
               :hard-negative-before-relevant? false
               :seed-hit? true :packet-hit? true}
              {:id :query/two :language :clojure :query-type :state
               :domain :state :search-hit? false
               :search-ms 30 :context-ms 40
               :search-recall-at-10? false :search-recall-at-20? true
               :search-recall-at-50? true :reciprocal-rank 0.0 :ndcg 0.5
               :hard-negative-before-relevant? true
               :seed-hit? false :packet-hit? false}]
        report (suite/aggregate-mode
                [{:repository :example :split :development :mode :hybrid
                  :result {:query-results rows}}]
                :hybrid)
        rendered (pr-str report)]
    (is (= 2 (:queries report)))
    (is (= 1 (:repositories report)))
    (is (= 2 (get-in report [:by-split :development :queries])))
    (is (= 0.5 (get-in report [:query-weighted :search-hit? :mean])))
    (is (= 20.0 (get-in report [:latency-ms :search :mean])))
    (is (not (re-find #"query/one|query/two" rendered)))))

(deftest process-stages-stream-separate-logs-and-heartbeats
  (let [checkout (temp-checkout)
        output
        (binding [suite/*heartbeat-ms* 10]
          (with-out-str
            (suite/run-process!
             checkout ".llm-context/public-semantic-evaluation/test.log"
             :benchmark
             ["bash" "-c"
              "printf streamed-out; printf streamed-err >&2; sleep 0.08"]
             2000)))
        base (.resolve checkout
                       ".llm-context/public-semantic-evaluation/test.log")]
    (is (= "streamed-out" (Files/readString base)))
    (is (= "streamed-err"
           (Files/readString
            (.resolve checkout
                      ".llm-context/public-semantic-evaluation/test.log.stderr.log"))))
    (is (re-find #":public-stage-heartbeat" output))))

(deftest timed-out-stage-terminates-descendants-and-records-failure
  (when-not (.startsWith (.toLowerCase (System/getProperty "os.name")) "windows")
    (let [checkout (temp-checkout)
          error
          (try
            (suite/run-process!
             checkout ".llm-context/public-semantic-evaluation/timeout.log"
             :benchmark
             ["bash" "-c" "sleep 30 & child=$!; echo $child; wait"]
             100)
            nil
            (catch clojure.lang.ExceptionInfo error error))
          stdout (.resolve checkout
                           ".llm-context/public-semantic-evaluation/timeout.log")
          child-pid (parse-long (.trim (Files/readString stdout)))]
      (is (true? (:timed-out? (ex-data error))))
      (is (= :timeout (:exit (ex-data error))))
      (loop [attempt 0]
        (let [alive? (when-let [handle (some-> child-pid
                                               java.lang.ProcessHandle/of
                                               (.orElse nil))]
                       (.isAlive handle))]
          (if (or (not alive?) (= attempt 50))
            (is (not alive?))
            (do (Thread/sleep 20) (recur (inc attempt))))))
      (is (Files/exists
           (.resolve checkout
                     ".llm-context/public-semantic-evaluation/timeout.log.result.edn")
           (make-array LinkOption 0))))))

(deftest resume-records-require-an-exact-compatibility-key
  (let [checkout (temp-checkout)
        key {:commit "a" :runtime "1"}
        result {:repository {:id :fixture} :runs []}]
    (#'suite/write-resume! checkout key result)
    (is (= result (#'suite/resumed-result checkout key)))
    (is (nil? (#'suite/resumed-result checkout (assoc key :runtime "2"))))))

(deftest repository-failure-still-attempts-service-shutdown
  (let [checkout (temp-checkout)
        stopped? (atom false)
        manifest {:repositories [{:id :fixture}] :repetitions 1}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"preflight failed"
         (with-redefs-fn
           {#'suite/validate-manifest! identity
            #'suite/checkout-path (fn [& _] checkout)
            #'suite/clean-and-pinned! (fn [& _] true)
            #'suite/validate-corpora! (fn [& _] {})
            #'suite/repository-resume-key (fn [& _] {:key true})
            #'suite/resumed-result (fn [& _] nil)
            #'suite/start-and-synchronize!
            (fn [& _] (throw (ex-info "preflight failed" {})))
            #'suite/stop-service! (fn [& _] (reset! stopped? true))}
           #(suite/run-suite! (str checkout) manifest {:resume? false}))))
    (is (true? @stopped?))))
