(ns llm-context.service.client-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.project :as project]
            [llm-context.service.client :as client]
            [llm-context.service.transport :as transport])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel]
           [java.nio.file Files LinkOption OpenOption]))

(deftest stale-unix-endpoint-is-reported-without-throwing
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
          (is (= false (:ok response)))
          (is (= :service/unreachable (:type response)))
          (is (false? (client/available? project))))
        (finally
          (Files/deleteIfExists socket-path)
          (Files/deleteIfExists (client/descriptor-path project)))))))
