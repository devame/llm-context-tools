(ns llm-context.service.server-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.semantic.fake-index :as fake]
            [llm-context.project :as project]
            [llm-context.service.client :as client]
            [llm-context.service.server :as server])
  (:import [java.nio.file Files LinkOption]))

(defn- await-service [project]
  (loop [attempt 0]
    (cond
      (client/available? project) true
      (>= attempt 100) false
      :else (do (Thread/sleep 20) (recur (inc attempt))))))

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
    (is (= {:ok true :value :pong} (client/request project {:op :ping})))
    (is (= {:status :unavailable
            :reason :model-missing
            :detail "/missing/model"}
           (get-in (client/request project {:op :semantic-status})
                   [:value :runtime])))
    (is (= 0 (get-in (client/request project
                                     {:op :query :subcommand "stats" :args []})
                     [:value :entities])))
    (is (= {:ok true :value :stopping}
           (client/request project {:op :stop})))
    (is (not= ::timeout (deref running 5000 ::timeout)))
    (is (not (Files/exists (client/descriptor-path project)
                           (make-array LinkOption 0))))))

(deftest service-owns-and-closes-ready-semantic-runtime
  (let [root (Files/createTempDirectory
              "llm-context-semantic-service-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        semantic-index (fake/create)
        runtime-factory (fn [_ _]
                          {:status :ready
                           :endpoint "http://127.0.0.1:12345"
                           :client semantic-index})
        running (future
                  (with-out-str
                    (server/start! project
                                   {:runtime-factory runtime-factory})))]
    (is (await-service project))
    (is (= :ready
           (get-in (client/request project {:op :semantic-status})
                   [:value :runtime :status])))
    (is (= {:ok true :value :stopping}
           (client/request project {:op :stop})))
    (is (not= ::timeout (deref running 5000 ::timeout)))
    (is (:closed? (fake/snapshot semantic-index)))))
