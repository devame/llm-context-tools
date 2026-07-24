(ns llm-context.service.client
  (:require [clojure.edn :as edn])
  (:import [java.io PushbackReader]
           [java.net InetAddress InetSocketAddress Socket]
           [java.nio.file Files LinkOption Path]))

(defn descriptor-path [project]
  (.resolve ^Path (:state-dir project) "service.edn"))

(defn descriptor [project]
  (let [path (descriptor-path project)]
    (when (Files/exists path (make-array LinkOption 0))
      (try (edn/read-string (Files/readString path))
           (catch Throwable _ nil)))))

(defn request
  "Send one authenticated EDN request. Return nil when no resident service is
  reachable so the CLI can fall back to its direct execution path."
  [project payload]
  (when-let [{:keys [port token]} (descriptor project)]
    (try
      (with-open [socket (Socket.)]
        (.connect socket (InetSocketAddress. (InetAddress/getLoopbackAddress)
                                             (int port)) 750)
        (.setSoTimeout socket 30000)
        (with-open [writer (java.io.PrintWriter. (.getOutputStream socket) true)
                    reader (PushbackReader. (java.io.InputStreamReader.
                                             (.getInputStream socket)))]
          (.println writer (pr-str (assoc payload :token token)))
          (edn/read {:eof nil} reader)))
      (catch java.net.ConnectException _ nil)
      (catch java.net.SocketTimeoutException _ nil)
      (catch java.io.IOException _ nil)
      ;; A service terminated between descriptor lookup and response parsing,
      ;; or an older service emitted a value not readable as plain EDN.
      (catch RuntimeException _ nil))))

(defn available? [project]
  (= {:ok true :value :pong} (request project {:op :ping})))
