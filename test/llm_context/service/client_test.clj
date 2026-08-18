(ns llm-context.service.client-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.project :as project]
            [llm-context.service.client :as client]
            [llm-context.service.contract :as contract]
            [llm-context.service.lifecycle :as lifecycle]
            [llm-context.service.transport :as transport])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel]
           [java.nio.file Files LinkOption OpenOption]))

(deftest stale-unix-endpoint-is-reclaimed-without-throwing
  (when-not (transport/windows?)
    (let [root (Files/createTempDirectory
                "llm-context-stale-service-"
                (make-array java.nio.file.attribute.FileAttribute 0))
          project (project/context (str root))
          state-dir (:state-dir project)
          socket-path (transport/socket-path project)
          stale-listener (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (try
        (Files/createDirectories
         state-dir
         (make-array java.nio.file.attribute.FileAttribute 0))
        ;; Bind and close without deleting the path, matching an abruptly
        ;; terminated service whose Unix socket cleanup never ran.
        (.bind stale-listener (UnixDomainSocketAddress/of socket-path))
        (.close stale-listener)
        (Files/writeString
         (client/descriptor-path project)
         (pr-str {:transport :unix
                  :socket-path (str socket-path)
                  :token "stale-token"})
         (make-array OpenOption 0))
        (let [response (client/request project {:op :ping})]
          (is (nil? response))
          (is (false? (client/available? project)))
          (is (not (Files/exists (client/descriptor-path project)
                                 (make-array LinkOption 0))))
          (is (not (Files/exists socket-path
                                 (make-array LinkOption 0)))))
        (finally
          (Files/deleteIfExists socket-path)
          (Files/deleteIfExists (client/descriptor-path project)))))))

(deftest unreachable-endpoint-is-not-reclaimed-while-service-lock-is-owned
  (when-not (transport/windows?)
    (let [root (Files/createTempDirectory
                "llm-context-owned-service-"
                (make-array java.nio.file.attribute.FileAttribute 0))
          project (project/context (str root))
          socket-path (transport/socket-path project)
          stale-listener (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (try
        (Files/createDirectories
         (:state-dir project)
         (make-array java.nio.file.attribute.FileAttribute 0))
        (.bind stale-listener (UnixDomainSocketAddress/of socket-path))
        (.close stale-listener)
        (Files/writeString
         (client/descriptor-path project)
         (pr-str {:transport :unix
                  :socket-path (str socket-path)
                  :token "owned-token"})
         (make-array OpenOption 0))
        (with-open [_ (lifecycle/acquire! project)]
          (let [response (client/request project {:op :ping})]
            (is (= false (:ok response)))
            (is (= :service/unreachable (:type response)))
            (is (Files/exists (client/descriptor-path project)
                              (make-array LinkOption 0)))
            (is (Files/exists socket-path (make-array LinkOption 0)))))
        (finally
          (Files/deleteIfExists socket-path)
          (Files/deleteIfExists (client/descriptor-path project)))))))

(deftest malformed-unowned-descriptor-is-reclaimed
  (let [root (Files/createTempDirectory
              "llm-context-malformed-service-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))]
    (Files/createDirectories
     (:state-dir project)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString (client/descriptor-path project) "{:broken"
                       (make-array OpenOption 0))
    (is (nil? (client/request project {:op :ping})))
    (is (not (Files/exists (client/descriptor-path project)
                           (make-array LinkOption 0))))))

(deftest incompatible-service-is-rejected-before-an-operational-request
  (let [root (Files/createTempDirectory
              "llm-context-versioned-service-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))]
    (Files/createDirectories
     (:state-dir project)
     (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString
     (client/descriptor-path project)
     (pr-str {:transport :tcp :host "127.0.0.1" :port 1 :token "old"
              :application-version "0.0.0"
              :protocol-version contract/protocol-version})
     (make-array OpenOption 0))
    (let [response (client/request project {:op :semantic-status})]
      (is (= false (:ok response)))
      (is (= :service/version-mismatch (:type response)))
      (is (re-find #"run llm-context analyze" (:error response))))))
