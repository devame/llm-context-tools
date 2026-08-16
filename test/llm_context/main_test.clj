(ns llm-context.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm-context.analysis.check :as analysis-check]
            [llm-context.analysis.full :as analysis-full]
            [llm-context.cli :as cli]
            [llm-context.config :as config]
            [llm-context.main :as main]
            [llm-context.project :as project]
            [llm-context.service.client :as service-client]
            [llm-context.service.progress :as analysis-progress]
            [llm-context.storage :as storage]
            [llm-context.store :as store]
            [llm-context.version :as version])
  (:import [java.nio.file Files]))

(deftest basic-command-routing
  (testing "help is the default"
    (let [out (with-out-str (is (zero? (main/run []))))]
      (is (str/includes? out "Usage:"))))
  (testing "version is printable"
    (is (= (str version/value "\n")
           (with-out-str (main/run ["version"])))))
  (testing "unknown commands are usage errors"
    (is (= 2 (main/run ["no-such-command"])))))

(deftest global-argument-parsing
  (is (= {:project "/tmp/example"
          :quiet? true
          :command "analyze"
          :args ["--full"]}
         (cli/parse-args ["--quiet" "analyze" "-C" "/tmp/example" "--full"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires a path"
                        (cli/parse-args ["analyze" "--project"]))))

(deftest context-intent-argument-parsing
  (is (= {:intent? true
          :focus "where is authentication handled?"
          :max-tokens 2000}
         (#'cli/parse-context-args
          ["--intent" "where is authentication handled?"
           "--max-tokens" "2000"]
          {}))))

(deftest analysis-progress-is-timestamped-and-counted
  (let [output
        (with-out-str
          (#'cli/print-analysis-progress!
           {:stage :analyzer-finalize-complete
            :exact-edges 1000 :references 25
            :external 20 :dynamic 2 :ambiguous 1 :unresolved 2}))]
    (is (re-find
         #"\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\] Graph quality: 1000 exact edges, 25 references"
         output))))

(deftest validation-errors-identify-the-offending-fact
  (let [entity {:entity/type :entity.type/reference
                :reference/target-text ""}
        error (ex-info
               "Invalid semantic graph entity"
               {:entity entity
                :explain
                {:clojure.spec.alpha/problems
                 [{:path [:reference/target-text]
                   :val ""
                   :pred 'clojure.core/seq}]}})
        output (with-out-str (#'cli/print-error! error))]
    (is (str/includes? output "Offending entity:"))
    (is (str/includes? output ":reference/target-text \"\""))
    (is (str/includes? output "Validation failure:"))))

(deftest project-context-is-canonical
  (let [context (project/context ".")]
    (is (.isAbsolute (:root context)))
    (is (= (.resolve (:root context) ".llm-context/db") (:db-dir context)))))

(deftest analysis-delegates-to-the-resident-service-owner
  (let [root (Files/createTempDirectory
              "llm-context-local-analysis-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})
        request (atom nil)]
    (with-redefs [service-client/request
                  (fn [_ payload options]
                    (reset! request [payload options])
                    {:ok true
                     :value {:mode :full :files 0 :entities 0
                             :diagnostics [] :semantic {:enabled? false}}})]
      (is (zero? (cli/execute context "analyze" ["--full"])))
      (is (= {:op :analyze :full? true} (first @request)))
      (is (= 86400000 (:request-timeout (second @request)))))))

(deftest local-analysis-publishes-durable-progress-when-no-service-owns-project
  (let [root (Files/createTempDirectory
              "llm-context-local-analysis-progress-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})]
    (with-redefs [service-client/request (fn [& _] nil)
                  store/graph-state (constantly :empty)
                  analysis-full/analyze!
                  (fn [_ _ progress]
                    (progress {:stage :discover-start})
                    {:mode :full :files 1 :entities 2
                     :diagnostics [] :semantic {:enabled? false}})]
      (is (zero? (cli/execute context "analyze" ["--full"])))
      (let [snapshot (analysis-progress/read-state context)]
        (is (= :complete (:state snapshot)))
        (is (= :full-analysis (:operation snapshot)))
        (is (= :discover-start (:stage snapshot)))))))

(deftest analysis-check-is-read-only-and-does-not-contact-the-service
  (let [root (Files/createTempDirectory
              "llm-context-analysis-check-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})
        checked? (atom false)]
    (with-redefs [service-client/request
                  (fn [& _]
                    (throw (ex-info "service must not be contacted" {})))
                  analysis-check/check!
                  (fn [_ _]
                    (reset! checked? true)
                    {:mode :check :files 1 :entities 2 :symbols 1
                     :exact-edges 0 :references 0 :diagnostics []})]
      (is (zero? (cli/execute context "analyze" ["--check"])))
      (is (true? @checked?))
      (is (not (Files/exists (:db-dir context)
                             (make-array java.nio.file.LinkOption 0)))))))

(deftest semantic-status-remains-available-without-a-service
  (let [root (Files/createTempDirectory
              "llm-context-semantic-status-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})
        output (with-out-str
                 (is (zero? (cli/execute context "semantic"
                                         ["status" "--verbose"]))))]
    (is (str/includes? output ":pending 0"))
    (is (str/includes? output ":status :not-running"))))

(deftest advertised-service-timeout-does-not-fall-back-to-direct-query
  (let [root (Files/createTempDirectory
              "llm-context-service-timeout-query-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})]
    (with-redefs [service-client/request
                  (fn [_ _]
                    {:ok false
                     :error "Project service request timed out"
                     :exit-code 1
                     :type :service/timeout})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Project service request timed out"
           (cli/execute context "query" ["stats"]))))))

(deftest stopping-an-absent-or-reclaimed-service-is-idempotent
  (let [root (Files/createTempDirectory
              "llm-context-service-stop-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})
        output (with-out-str
                 (with-redefs [service-client/request (fn [& _] nil)]
                   (is (zero? (cli/execute context "service" ["stop"])))))]
    (is (= "not running\n" output))))

(deftest semantic-sync-requires-the-project-service
  (let [root (Files/createTempDirectory
              "llm-context-semantic-sync-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires a running project service"
         (cli/execute context "semantic" ["sync"])))))

(deftest semantic-sync-accepts-an-explicit-positive-wait-timeout
  (is (= {:wait? true :timeout-ms 1200000}
         (#'cli/parse-semantic-sync-options
          ["--wait" "--timeout-ms" "1200000"])))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires a positive integer"
       (#'cli/parse-semantic-sync-options ["--timeout-ms" "0"]))))

(deftest semantic-sync-reports-the-worker-failure-detail
  (let [root (Files/createTempDirectory
              "llm-context-semantic-worker-failure-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})
        status {:indexed 0 :dirty 1 :pending 10 :leased 0 :failed 0
                :runtime {:status :ready
                          :worker-status :failed
                          :worker-detail "fixture decoding failed"}}]
    (with-redefs [service-client/request
                  (fn [_ _] {:ok true :value status})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"LateOn semantic worker failed: fixture decoding failed"
           (cli/execute context "semantic" ["sync" "--wait"]))))))

(deftest semantic-status-accepts-watch-options
  (is (= {:watch? true :verbose? true :interval-ms 1500}
         (#'cli/parse-semantic-status-options
          ["--watch" "--verbose" "--interval-ms" "1500"])))
  (is (= {:watch? false :verbose? false :interval-ms 2000}
         (#'cli/parse-semantic-status-options [])))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires a positive integer"
       (#'cli/parse-semantic-status-options ["--interval-ms" "0"]))))

(deftest semantic-status-summary-is-concise-and-reports-throughput
  (let [status {:desired 100
                :indexed 35
                :runtime {:worker-progress
                          {:documents-per-minute 120.0}}}]
    (is (= "65 of 100 documents pending, processing speed: 2.00 docs/s"
           (#'cli/semantic-status-summary status)))
    (is (= "0 of 100 documents pending, processing speed: 0.00 docs/s"
           (#'cli/semantic-status-summary (assoc status :indexed 100))))))

(deftest semantic-status-summary-derives-speed-from-watch-samples
  (is (= "86 of 100 documents pending, processing speed: 2.00 docs/s"
         (#'cli/semantic-status-summary
          {:desired 100 :indexed 14}
          {:desired 100 :indexed 10}
          2000))))

(deftest compact-copy-delegates-to-the-resident-service-owner
  (let [root (Files/createTempDirectory
              "llm-context-maintenance-copy-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})
        request (atom nil)
        output (with-out-str
                 (with-redefs
                  [service-client/request
                   (fn [_ payload options]
                     (reset! request [payload options])
                     {:ok true
                      :value {:verified? true
                              :copy-path (str (.resolve root "copy"))}})]
                   (is (zero? (cli/execute
                               context "maintenance"
                               ["compact-copy" "--output" "copy"])))))]
    (is (= {:op :maintenance-compact-copy
            :destination (str (.resolve root "copy"))}
           (first @request)))
    (is (= 86400000 (:request-timeout (second @request))))
    (is (str/includes? output ":verified? true"))))

(deftest maintenance-status-is-read-only-and-local
  (let [root (Files/createTempDirectory
              "llm-context-maintenance-status-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})
        requested? (atom false)
        output (with-out-str
                 (with-redefs [service-client/request
                               (fn [& _] (reset! requested? true))]
                   (is (zero? (cli/execute context "maintenance" ["status"])))))]
    (is (false? @requested?))
    (is (str/includes? output ":components"))
    (is (str/includes? output ":semantic-index"))))

(deftest maintenance-cleanup-is-dry-run-unless-apply-is-explicit
  (let [root (Files/createTempDirectory
              "llm-context-maintenance-cleanup-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})
        calls (atom [])]
    (with-redefs [storage/cleanup-plan
                  (fn [_ _ days] (swap! calls conj [:plan days]) {:applied? false})
                  storage/apply-cleanup!
                  (fn [_ _ days] (swap! calls conj [:apply days]) {:applied? true})]
      (with-out-str (cli/execute context "maintenance"
                                ["cleanup" "--older-than-days" "30"]))
      (with-out-str (cli/execute context "maintenance"
                                ["cleanup" "--older-than-days" "30" "--apply"])))
    (is (= [[:plan 30] [:apply 30]] @calls))))

(deftest initialization-confirms-the-project-root
  (let [root (Files/createTempDirectory
              "llm-context-init-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? false})
        output (with-in-str "yes\n"
                 (with-out-str (is (zero? (cli/execute context "init" [])))))]
    (is (str/includes? output (str root)))
    (is (= ["."] (get-in (config/load-config context) [:analysis :include]))))
  (let [root (Files/createTempDirectory
              "llm-context-init-cancel-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? false})]
    (with-in-str "no\n"
      (is (zero? (cli/execute context "init" []))))
    (is (not (Files/exists (:config-file context)
                           (make-array java.nio.file.LinkOption 0)))))
  (let [root (Files/createTempDirectory
              "llm-context-init-yes-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? false})]
    (is (zero? (cli/execute context "init" ["--yes"])))
    (is (Files/exists (:config-file context)
                      (make-array java.nio.file.LinkOption 0)))))
