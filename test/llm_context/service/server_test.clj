(ns llm-context.service.server-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.analysis.full :as full]
            [llm-context.analysis.incremental :as incremental]
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
                                   {:runtime-factory runtime-factory})))]
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
            :worker-status :not-running}
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
                                   {:runtime-factory runtime-factory})))]
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
                                       {:runtime-factory runtime-factory})))]
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
                                   {:runtime-factory runtime-factory})))]
    (is (await-service project))
    ;; Model the case where another network namespace can see the project but
    ;; cannot contact its advertised endpoint. Ownership must not depend only
    ;; on a successful ping.
    (with-redefs [client/available? (constantly false)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already owns this project"
           (server/start! project {:runtime-factory runtime-factory}))))
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
                                       {:runtime-factory runtime-factory})))]
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
                                       {:runtime-factory runtime-factory})))]
        (is (await-service project))
        (let [slow (future
                     (client/request
                      project {:op :query :subcommand "stats" :args []}))]
          (is (= true (deref entered 2000 false)))
          (is (= {:ok true :value :pong}
                 (client/request project {:op :ping}
                                 {:request-timeout 1000})))
          (deliver release true)
          (is (= 0 (get-in (deref slow 2000 ::timeout)
                           [:value :entities]))))
        (is (= {:ok true :value :stopping}
               (client/request project {:op :stop})))
        (is (not= ::timeout (deref running 5000 ::timeout)))))))

(deftest graph-reads-wait-for-analysis-activation
  (let [graph (Object.)
        entered-analysis (promise)
        release-analysis (promise)
        entered-query (promise)
        runtime-state (atom {})]
    (with-redefs [incremental/index-present? (constantly false)
                  full/analyze!
                  (fn [& _]
                    (deliver entered-analysis true)
                    @release-analysis
                    {:mode :full})
                  store/assert-query-compatible! identity
                  query/stats
                  (fn [_]
                    (deliver entered-query true)
                    {:entities 0})]
      (let [analysis
            (future
              (#'server/dispatch nil {} graph runtime-state
                                 {:op :analyze :full? true}))]
        (is (= true (deref entered-analysis 1000 false)))
        (let [read
              (future
                (#'server/dispatch nil {} graph runtime-state
                                   {:op :query :subcommand "stats" :args []}))]
          (is (= ::blocked (deref entered-query 100 ::blocked)))
          (deliver release-analysis true)
          (is (= {:mode :full} (deref analysis 1000 ::timeout)))
          (is (= true (deref entered-query 1000 false)))
          (is (= {:entities 0} (deref read 1000 ::timeout))))))))

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
