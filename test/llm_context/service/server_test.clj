(ns llm-context.service.server-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
            [llm-context.context :as context]
            [llm-context.intent.router :as intent-router]
            [llm-context.query :as query]
            [llm-context.semantic.fake-index :as fake]
            [llm-context.semantic.worker :as semantic-worker]
            [llm-context.project :as project]
            [llm-context.service.client :as client]
            [llm-context.service.server :as server]
            [llm-context.service.transport :as transport]
            [llm-context.store :as store])
  (:import [java.nio.file Files LinkOption]))

(defn- await-service [project]
  (loop [attempt 0]
    (cond
      (client/available? project) true
      (>= attempt 100) false
      :else (do (Thread/sleep 20) (recur (inc attempt))))))

(defn- await-semantic-status [project expected]
  (loop [attempt 0]
    (let [status (get-in (client/request project {:op :semantic-status})
                         [:value :runtime :status])]
      (cond
        (= expected status) true
        (>= attempt 100) false
        :else (do (Thread/sleep 20) (recur (inc attempt)))))))

(defn- router-factory [_ _]
  {:status :disabled :client (intent-router/unavailable :test)})

(deftest authenticated-loopback-service-round-trip
  (let [root (Files/createTempDirectory "llm-context-service-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        runtime-factory (fn [_ _]
                          {:status :unavailable
                           :reason :model-missing
                           :detail "/missing/model"})
        running (future
                  (with-out-str
                    (server/start! project
                                   {:runtime-factory runtime-factory
                                    :router-factory router-factory})))]
    (is (await-service project))
    (let [descriptor (client/descriptor project)]
      (if (transport/windows?)
        (is (= :tcp (:transport descriptor)))
        (do
          (is (= :unix (:transport descriptor)))
          (is (= (str (transport/socket-path project))
                 (:socket-path descriptor)))
          (is (Files/exists (transport/socket-path project)
                            (make-array LinkOption 0))))))
    (is (= {:ok true :value :pong} (client/request project {:op :ping})))
    (is (await-semantic-status project :unavailable))
    (is (= {:status :unavailable
            :reason :model-missing
            :detail "/missing/model"
            :worker-status :not-running
            :query-router-status :disabled}
           (get-in (client/request project {:op :semantic-status})
                   [:value :runtime])))
    (is (= 0 (get-in (client/request project
                                     {:op :query :subcommand "stats" :args []})
                     [:value :entities])))
    (is (= {:ok true :value :stopping}
           (client/request project {:op :stop})))
    (is (not= ::timeout (deref running 5000 ::timeout)))
    (is (not (Files/exists (client/descriptor-path project)
                           (make-array LinkOption 0))))
    (when-not (transport/windows?)
      (is (not (Files/exists (transport/socket-path project)
                             (make-array LinkOption 0)))))))

(deftest service-owns-and-closes-ready-semantic-runtime
  (let [root (Files/createTempDirectory
              "llm-context-semantic-service-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        semantic-index (fake/create)
        runtime-factory (fn [_ _]
                          {:status :ready
                           :endpoint "http://127.0.0.1:12345"
                           :log-path (.resolve root "next-plaid.log")
                           :client semantic-index})
        running (future
                  (with-out-str
                    (server/start! project
                                   {:runtime-factory runtime-factory
                                    :router-factory router-factory})))]
    (is (await-service project))
    (is (await-semantic-status project :ready))
    (is (= :ready
           (get-in (client/request project {:op :semantic-status})
                   [:value :runtime :status])))
    (is (= :available
           (get-in (client/request project {:op :semantic-status})
                   [:value :availability])))
    (is (= :complete
           (get-in (client/request project {:op :semantic-status})
                   [:value :completeness])))
    (is (= (str (.resolve root "next-plaid.log"))
           (get-in (client/request project {:op :semantic-status})
                   [:value :runtime :log-path])))
    (is (= {:ok true :value :stopping}
           (client/request project {:op :stop})))
    (is (not= ::timeout (deref running 5000 ::timeout)))
    (is (:closed? (fake/snapshot semantic-index)))))

(deftest semantic-status-remains-readable-while-worker-processes-a-batch
  (let [root (Files/createTempDirectory
              "llm-context-semantic-status-concurrent-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        semantic-index (fake/create)
        entered (promise)
        release (promise)
        runtime-factory (fn [_ _]
                          {:status :ready
                           :endpoint "http://127.0.0.1:12345"
                           :client semantic-index})]
    (with-redefs [semantic-worker/process-once!
                  (fn [_]
                    (deliver entered true)
                    @release
                    {:leased 0 :completed 0 :retried 0
                     :failed 0 :superseded 0})]
      (let [running (future
                      (with-out-str
                        (server/start! project
                                       {:runtime-factory runtime-factory
                                        :router-factory router-factory})))]
        (is (= true (deref entered 5000 false)))
        (let [status (client/request project {:op :semantic-status}
                                     {:request-timeout 1000})]
          (is (= true (:ok status)))
          (is (= 0 (get-in status [:value :pending])))
          (is (= :ready (get-in status [:value :runtime :status]))))
        (deliver release true)
        (is (= {:ok true :value :stopping}
               (client/request project {:op :stop})))
        (is (not= ::timeout (deref running 5000 ::timeout)))))))

(deftest project-lock-prevents-a-second-unreachable-service-owner
  (let [root (Files/createTempDirectory
              "llm-context-service-owner-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        runtime-factory (fn [_ _] {:status :unavailable
                                   :reason :model-missing})
        running (future
                  (with-out-str
                    (server/start! project
                                   {:runtime-factory runtime-factory
                                    :router-factory router-factory})))]
    (is (await-service project))
    ;; Model the case where another network namespace can see the project but
    ;; cannot contact its advertised endpoint. Ownership must not depend only
    ;; on a successful ping.
    (with-redefs [client/available? (constantly false)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already owns this project"
           (server/start! project {:runtime-factory runtime-factory
                                   :router-factory router-factory}))))
    (is (= {:ok true :value :stopping}
           (client/request project {:op :stop})))
    (is (not= ::timeout (deref running 5000 ::timeout)))))

(deftest service-reports-background-worker-failure-separately
  (let [root (Files/createTempDirectory
              "llm-context-failed-worker-service-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        semantic-index (fake/create)
        runtime-factory (fn [_ _]
                          {:status :ready
                           :endpoint "http://127.0.0.1:12345"
                           :client semantic-index})]
    (with-redefs [semantic-worker/run!
                  (fn [_] (throw (ex-info "fixture decoding failed" {})))]
      (let [running (future
                      (with-out-str
                        (server/start! project
                                       {:runtime-factory runtime-factory
                                        :router-factory router-factory})))]
        (is (await-service project))
        (loop [attempt 0]
          (let [runtime
                (get-in (client/request project {:op :semantic-status})
                        [:value :runtime])]
            (if (or (= :failed (:worker-status runtime))
                    (>= attempt 100))
              (do
                (is (= :ready (:status runtime)))
                (is (= :failed (:worker-status runtime)))
                (is (= "fixture decoding failed" (:worker-detail runtime))))
              (do (Thread/sleep 20) (recur (inc attempt))))))
        (is (= {:ok true :value :stopping}
               (client/request project {:op :stop})))
        (is (not= ::timeout (deref running 5000 ::timeout)))))))

(deftest slow-request-does-not-block-other-clients
  (let [root (Files/createTempDirectory
              "llm-context-concurrent-service-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        entered (promise)
        release (promise)
        runtime-factory (fn [_ _] {:status :unavailable
                                   :reason :model-missing})]
    (with-redefs [query/stats
                  (fn [_]
                    (deliver entered true)
                    @release
                    {:entities 0})]
      (let [running (future
                      (with-out-str
                        (server/start! project
                                       {:runtime-factory runtime-factory
                                        :router-factory router-factory})))]
        (is (await-service project))
        (let [slow (future
                     (client/request
                      project {:op :query :subcommand "stats" :args []}))]
          (is (= true (deref entered 2000 false)))
          (is (= {:ok true :value :pong}
                 (client/request project {:op :ping}
                                 {:request-timeout 1000})))
          (is (= true
                 (get-in (client/request project {:op :semantic-status}
                                         {:request-timeout 1000})
                         [:ok])))
          (deliver release true)
          (is (= 0 (get-in (deref slow 2000 ::timeout)
                           [:value :entities]))))
        (is (= {:ok true :value :stopping}
               (client/request project {:op :stop})))
        (is (not= ::timeout (deref running 5000 ::timeout)))))))

(deftest graph-reads-remain-available-during-analysis-preparation
  (let [graph (Object.)
        generation (atom 0)
        entered-preparation (promise)
        release-preparation (promise)
        runtime-state (atom {})]
    (with-redefs [full/prepare-current
                  (fn [& _]
                    (deliver entered-preparation true)
                    @release-preparation
                    {:candidate true})
                  full/commit-candidate!
                  (fn [& _] {:mode :full :started 0})
                  full/finish-candidate!
                  (fn [& _] {:mode :full})
                  store/assert-query-compatible! identity
                  query/stats (constantly {:entities 0})]
      (let [analysis
            (future
              (#'server/dispatch nil {} graph generation runtime-state
                                 {:op :analyze :full? true}))]
        (is (= true (deref entered-preparation 1000 false)))
        (is (= {:entities 0}
               (#'server/dispatch nil {} graph generation runtime-state
                                  {:op :query :subcommand "stats" :args []})))
        (is (zero? @generation))
        (deliver release-preparation true)
        (is (= {:mode :full} (deref analysis 1000 ::timeout)))))))

(deftest graph-reads-reject-an-active-analysis-commit-without-waiting
  (let [graph (Object.)
        generation (atom 0)
        entered-commit (promise)
        release-commit (promise)
        runtime-state (atom {})]
    (with-redefs [full/prepare-current (fn [& _] {:candidate true})
                  full/commit-candidate!
                  (fn [& _]
                    (deliver entered-commit true)
                    @release-commit
                    {:mode :full :started 0})
                  full/finish-candidate! (fn [& _] {:mode :full})
                  store/assert-query-compatible! identity
                  query/stats (constantly {:entities 0})]
      (let [analysis
            (future
              (#'server/dispatch nil {} graph generation runtime-state
                                 {:op :analyze :full? true}))]
        (is (= true (deref entered-commit 1000 false)))
        (let [read (try
                     (#'server/dispatch
                      nil {} graph generation runtime-state
                      {:op :query :subcommand "stats" :args []})
                     (catch clojure.lang.ExceptionInfo error
                       (ex-data error)))]
          (is (= :graph/update-in-progress (:type read)))
          (deliver release-commit true)
          (is (= {:mode :full} (deref analysis 1000 ::timeout))))))))

(deftest graph-read-discards-work-overlapped-by-a-write
  (let [graph (Object.)
        generation (atom 0)
        entered-read (promise)
        release-read (promise)
        calls (atom 0)
        runtime-state (atom {})]
    (with-redefs [store/assert-query-compatible! identity
                  query/stats
                  (fn [_]
                    (when (= 1 (swap! calls inc))
                      (deliver entered-read true)
                      @release-read)
                    {:entities @calls})]
      (let [read (future
                   (#'server/dispatch nil {} graph generation runtime-state
                                      {:op :query :subcommand "stats"
                                       :args []}))]
        (is (= true (deref entered-read 1000 false)))
        (is (= :written
               (#'server/with-graph-write graph generation
                                          (constantly :written))))
        (deliver release-read true)
        (is (= {:entities 2} (deref read 1000 ::timeout)))
        (is (= 2 @calls))))))

(deftest semantic-retrieval-does-not-hold-the-graph-lock
  (let [graph (Object.)
        generation (atom 0)
        entered-retrieval (promise)
        release-retrieval (promise)
        acquired-graph (promise)
        seen-options (atom nil)
        runtime-state (atom {:client :semantic-client})]
    (with-redefs [query/semantic-search-attempt
                  (fn [_ _ _]
                    (deliver entered-retrieval true)
                    @release-retrieval
                    {:status :unavailable :candidates [] :latency-ms 0})
                  store/assert-query-compatible! identity
                  query/search-explain-with-attempt
                  (fn [_ _ _ _ options]
                    (reset! seen-options options)
                    {:results []})]
      (let [search
            (future
              (#'server/dispatch nil {} graph generation runtime-state
                                 {:op :query :subcommand "search"
                                  :args ["semantic intent" "--source-preference"
                                         "production"]}))]
        (is (= true (deref entered-retrieval 1000 false)))
        (future
          (locking graph
            (deliver acquired-graph true)))
        (is (= true (deref acquired-graph 1000 false)))
        (deliver release-retrieval true)
        (is (= {:results []} (deref search 1000 ::timeout)))
        (is (= :production (:source-preference @seen-options)))))))

(deftest intent-context-resolves-a-hybrid-seed-before-traversal
  (let [graph (Object.)
        generation (atom 0)
        runtime-state (atom {:client :semantic-client})
        seen (atom nil)
        attempt {:status :ok :candidates [:candidate] :latency-ms 4}
        search
        {:results [{:id "symbol:selected"
                    :qualified-name "fixture/selected"
                    :matched-by #{:lateon}
                    :score 0.5}
                   {:id "symbol:alternative"
                    :qualified-name "fixture/alternative"
                    :matched-by #{:lateon}
                    :score 0.4}]
         :retrieval {:status :ok :latency-ms 4}}]
    (with-redefs [query/semantic-search-attempt
                  (fn [client _ term options]
                    (is (= :semantic-client client))
                    (is (= "where is selection handled?" term))
                    (is (= :hybrid (:mode options)))
                    attempt)
                  query/search-explain-with-attempt
                  (fn [_ _ term actual-attempt options]
                    (is (= "where is selection handled?" term))
                    (is (= attempt actual-attempt))
                    (is (= :auto (:source-preference options)))
                    search)
                  context/build-from-seeds
                  (fn [_ options resolution]
                    (reset! seen {:options options :resolution resolution})
                    {:packet/version 3})
                  store/assert-query-compatible! identity]
      (is (= {:packet/version 3}
             (#'server/dispatch
              nil {:context {:intent-source-preference :auto}}
              graph generation runtime-state
              {:op :context
               :options {:focus "where is selection handled?"
                         :intent? true :format :edn}})))
      (is (= ["symbol:selected"]
             (mapv :id (get-in @seen [:resolution :selected]))))
      (is (= ["symbol:alternative"]
             (mapv :id (get-in @seen [:resolution :alternatives])))))))

(deftest unreadable-service-response-is-an-explicit-protocol-error
  (let [root (Files/createTempDirectory
              "llm-context-unreadable-service-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        running
        (future
          (with-open [server (java.net.ServerSocket.
                              0 1 (java.net.InetAddress/getLoopbackAddress))]
            (Files/createDirectories
             (:state-dir project)
             (make-array java.nio.file.attribute.FileAttribute 0))
            (Files/writeString
             (client/descriptor-path project)
             (pr-str {:port (.getLocalPort server) :token "test"})
             (make-array java.nio.file.OpenOption 0))
            (with-open [socket (.accept server)
                        writer (java.io.PrintWriter.
                                (.getOutputStream socket) true)]
              (.println writer "#object[unreadable]"))))]
    (loop [attempt 0]
      (when (and (not (Files/exists
                       (client/descriptor-path project)
                       (make-array LinkOption 0)))
                 (< attempt 100))
        (Thread/sleep 10)
        (recur (inc attempt))))
    (is (= :service/protocol-error
           (:type (client/request project {:op :ping}))))
    (is (not= ::timeout (deref running 5000 ::timeout)))))

(deftest advertised-service-timeout-is-not-treated-as-absent
  (let [root (Files/createTempDirectory
              "llm-context-timeout-service-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        release (promise)
        running
        (future
          (with-open [server (java.net.ServerSocket.
                              0 1 (java.net.InetAddress/getLoopbackAddress))]
            (Files/createDirectories
             (:state-dir project)
             (make-array java.nio.file.attribute.FileAttribute 0))
            (Files/writeString
             (client/descriptor-path project)
             (pr-str {:port (.getLocalPort server) :token "test"})
             (make-array java.nio.file.OpenOption 0))
            (with-open [socket (.accept server)]
              @release)))]
    (loop [attempt 0]
      (when (and (not (Files/exists
                       (client/descriptor-path project)
                       (make-array LinkOption 0)))
                 (< attempt 100))
        (Thread/sleep 10)
        (recur (inc attempt))))
    (let [response (client/request project {:op :ping}
                                   {:request-timeout 50})]
      (is (= false (:ok response)))
      (is (= :service/timeout (:type response))))
    (deliver release true)
    (is (not= ::timeout (deref running 5000 ::timeout)))))
