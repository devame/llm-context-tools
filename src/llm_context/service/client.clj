(ns llm-context.service.client
  (:require [clojure.edn :as edn]
            [llm-context.service.transport :as transport])
  (:import [java.io PushbackReader]
           [java.net InetAddress InetSocketAddress Socket
            StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]
           [java.nio.file Files LinkOption Path]))

(def ^:private connect-timeout-ms 750)
(def ^:private request-timeout-ms 30000)

(defn descriptor-path [project]
  (.resolve ^Path (:state-dir project) "service.edn"))

(defn descriptor [project]
  (let [path (descriptor-path project)]
    (when (Files/exists path (make-array LinkOption 0))
      (try (edn/read-string (Files/readString path))
           (catch Throwable _ nil)))))

(defn- unavailable-response [type message]
  {:ok false :error message :exit-code 1 :type type})

(defn- communication-error-response [request-timeout error]
  (cond
    (instance? java.net.SocketTimeoutException error)
    (unavailable-response
     :service/timeout
     (str "Project service request timed out after "
          request-timeout " ms; the service may be busy"))

    (instance? java.net.ConnectException error)
    (unavailable-response
     :service/unreachable
     "Project service is advertised but its endpoint is unreachable")

    (instance? java.io.IOException error)
    (unavailable-response
     :service/io-error
     (str "Project service communication failed: " (.getMessage error)))

    :else
    (unavailable-response
     :service/protocol-error
     (str "Project service returned an unreadable response: "
          (.getMessage error)))))

(defn- read-response [input]
  (with-open [reader (PushbackReader. (java.io.InputStreamReader. input))]
    (edn/read {:eof nil} reader)))

(defn- tcp-request
  [{:keys [host port token]} payload connect-timeout request-timeout]
  (with-open [socket (Socket.)]
    (.connect socket
              (InetSocketAddress.
               (or (some-> host InetAddress/getByName)
                   (InetAddress/getLoopbackAddress))
               (int port))
              (int connect-timeout))
    (.setSoTimeout socket (int request-timeout))
    (with-open [writer (java.io.PrintWriter. (.getOutputStream socket) true)]
      (.println writer (pr-str (assoc payload :token token)))
      (read-response (.getInputStream socket)))))

(defn- unix-request
  [{:keys [socket-path token]} payload]
  (with-open [channel (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect channel (UnixDomainSocketAddress/of socket-path))
    (with-open [writer (java.io.PrintWriter.
                        (Channels/newOutputStream channel) true)]
      (.println writer (pr-str (assoc payload :token token)))
      (read-response (Channels/newInputStream channel)))))

(defn request
  "Send one authenticated EDN request.

  Return nil only when no service descriptor exists. Once a project advertises
  a resident service, connection and response failures are explicit so callers
  never mistake a busy or unreachable service for permission to open a second
  Datalevin connection."
  ([project payload]
   (request project payload {}))
  ([project payload {:keys [connect-timeout request-timeout]
                     :or {connect-timeout connect-timeout-ms
                          request-timeout request-timeout-ms}}]
   (when-let [{:keys [transport] :as endpoint} (descriptor project)]
     (try
       ;; Descriptors written before Unix transport did not carry :transport.
       (case (or transport :tcp)
         :unix
         (let [operation (future (unix-request endpoint payload))
               response (deref operation request-timeout ::timeout)]
           (if (= ::timeout response)
             (do
               (future-cancel operation)
               (throw (java.net.SocketTimeoutException.)))
             response))
         :tcp (tcp-request endpoint payload connect-timeout request-timeout)
         (throw (ex-info (str "Unknown project service transport: " transport)
                         {:transport transport})))
       (catch java.util.concurrent.ExecutionException error
         ;; Unix requests run in a future so a stuck connect can be bounded.
         ;; Future.get/deref wraps the actual socket exception, so unwrap it
         ;; before classifying the endpoint failure.
         (communication-error-response
          request-timeout (or (.getCause error) error)))
       (catch java.net.SocketTimeoutException error
         (communication-error-response request-timeout error))
       (catch java.net.ConnectException error
         (communication-error-response request-timeout error))
       (catch java.io.IOException error
         (communication-error-response request-timeout error))
       (catch RuntimeException error
         (communication-error-response request-timeout error))))))

(defn available? [project]
  (= {:ok true :value :pong} (request project {:op :ping})))
