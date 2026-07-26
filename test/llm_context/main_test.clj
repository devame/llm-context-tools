(ns llm-context.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm-context.cli :as cli]
            [llm-context.config :as config]
            [llm-context.main :as main]
            [llm-context.project :as project]
            [llm-context.service.client :as service-client]
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

(deftest semantic-status-remains-available-without-a-service
  (let [root (Files/createTempDirectory
              "llm-context-semantic-status-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})
        output (with-out-str
                 (is (zero? (cli/execute context "semantic" ["status"]))))]
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

(deftest semantic-sync-requires-the-project-service
  (let [root (Files/createTempDirectory
              "llm-context-semantic-sync-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        context (assoc (project/context (str root)) :options {:quiet? true})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"requires a running project service"
         (cli/execute context "semantic" ["sync"])))))

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
