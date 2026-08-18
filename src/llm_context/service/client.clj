(ns llm-context.service.client
  (:require [clojure.edn :as edn]
            [llm-context.service.contract :as service-contract]
            [llm-context.service.lifecycle :as lifecycle]
            [llm-context.service.transport :as transport])
  (:import [java.io PushbackReader]
           [java.net InetAddress InetSocketAddress Socket
            StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(def ^:private connect-timeout-ms 750)
(def ^:private request-timeout-ms 30000)

(defn descriptor-path [project]
  (lifecycle/descriptor-path project))

(defn descriptor [project]
  (let [{:keys [exists? valid? value]}
        (lifecycle/descriptor-snapshot project)]
    (when (and exists? valid?) value)))

(defn compatibility [project]
  (service-contract/compatibility (descriptor project)))

(defn compatible? [project]
  (= :compatible (compatibility project)))

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
    (let [response (edn/read {:eof ::eof} reader)]
      (when-not (and (map? response) (contains? response :ok))
        (throw
         (ex-info "Project service returned an invalid response envelope"
                  {:response response})))
      response)))

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

(defn- reclaimable-error? [response]
  (contains? #{:service/unreachable :service/io-error
               :service/protocol-error}
             (:type response)))

(defn- reclaim-or-report [project snapshot response]
  (if (reclaimable-error? response)
    (case (:status (lifecycle/reclaim-stale! project (:content snapshot)))
      (:reclaimed :absent) nil
      response)
    response))

(defn- invalid-descriptor-response [project snapshot]
  (case (:status (lifecycle/reclaim-stale! project (:content snapshot)))
    (:reclaimed :absent) nil
    (unavailable-response
     :service/protocol-error
     "Project service descriptor is unreadable while the service lock is owned")))

(defn- usable-descriptor? [{:keys [transport token port socket-path]}]
  (and (string? token)
       (case (or transport :tcp)
         :unix (string? socket-path)
         :tcp (integer? port)
         false)))

(defn request
  "Send one authenticated EDN request.

  Return nil when no descriptor exists or when an unreachable stale descriptor
  was safely reclaimed under the project service lock. Timeouts and failures
  while another process owns the lock remain explicit, so callers never open a
  second Datalevin connection beside a live or potentially busy service."
  ([project payload]
   (request project payload {}))
  ([project payload {:keys [connect-timeout request-timeout]
                     :or {connect-timeout connect-timeout-ms
                          request-timeout request-timeout-ms}}]
   (let [{:keys [exists? valid? value] :as snapshot}
         (lifecycle/descriptor-snapshot project)]
     (cond
       (not exists?) nil
       (or (not valid?) (not (usable-descriptor? value)))
       (invalid-descriptor-response project snapshot)
       (and (not (contains? #{:ping :stop :service-info} (:op payload)))
            (not= :compatible (service-contract/compatibility value)))
       (unavailable-response
        :service/version-mismatch
        (str "Project service is incompatible with this CLI (CLI "
             (:application-version (service-contract/runtime-identity))
             ", service " (or (:application-version value) "unknown")
             ", protocol " (or (:protocol-version value) "unknown")
             "); run llm-context analyze or service start to replace it"))
       :else
       (let [{:keys [transport] :as endpoint} value
             response
             (try
               ;; Descriptors written before Unix transport did not carry
               ;; :transport.
               (case (or transport :tcp)
                 :unix
                 (let [operation (future (unix-request endpoint payload))
                       response (deref operation request-timeout ::timeout)]
                   (if (= ::timeout response)
                     (do
                       (future-cancel operation)
                       (throw (java.net.SocketTimeoutException.)))
                     response))
                 :tcp (tcp-request endpoint payload connect-timeout
                                   request-timeout)
                 (throw
                  (ex-info
                   (str "Unknown project service transport: " transport)
                   {:transport transport})))
               (catch java.util.concurrent.ExecutionException error
                 ;; Unix requests run in a future so a stuck connect can be
                 ;; bounded. Deref wraps the actual socket exception.
                 (communication-error-response
                  request-timeout (or (.getCause error) error)))
               (catch java.net.SocketTimeoutException error
                 (communication-error-response request-timeout error))
               (catch java.net.ConnectException error
                 (communication-error-response request-timeout error))
               (catch java.io.IOException error
                 (communication-error-response request-timeout error))
               (catch RuntimeException error
                 (communication-error-response request-timeout error)))]
         (reclaim-or-report project snapshot response))))))

(defn available? [project]
  (= {:ok true :value :pong} (request project {:op :ping})))
