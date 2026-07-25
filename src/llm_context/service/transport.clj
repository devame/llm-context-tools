(ns llm-context.service.transport
  "Project-local service transports.

  Unix-domain sockets are the default wherever the JVM supports them. They
  remain reachable across network namespaces that share the project state
  directory. Windows retains authenticated loopback TCP."
  (:import [java.io Closeable InputStream OutputStream]
           [java.net InetAddress ServerSocket Socket StandardProtocolFamily
            UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel SocketChannel]
           [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute PosixFilePermissions]))

(defn windows? []
  (.startsWith (.toLowerCase (System/getProperty "os.name" "")) "windows"))

(defn socket-path ^Path [project]
  (.resolve ^Path (:state-dir project) "service.sock"))

(defn- connection-input ^InputStream [connection]
  (if (instance? Socket connection)
    (.getInputStream ^Socket connection)
    (Channels/newInputStream ^SocketChannel connection)))

(defn- connection-output ^OutputStream [connection]
  (if (instance? Socket connection)
    (.getOutputStream ^Socket connection)
    (Channels/newOutputStream ^SocketChannel connection)))

(defn input-stream [connection]
  (connection-input connection))

(defn output-stream [connection]
  (connection-output connection))

(defn close-connection! [connection]
  (.close ^Closeable connection))

(defrecord Listener [resource accept-fn descriptor cleanup-fn]
  Closeable
  (close [_]
    (try
      (.close ^Closeable resource)
      (finally
        (cleanup-fn)))))

(defn secure-owner-only! [^Path path]
  (try
    (Files/setPosixFilePermissions
     path (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _)
    (catch java.io.IOException _))
  path)

(defn open-listener
  "Open the platform service listener. The caller must already own the project
  service lock, so deleting a leftover Unix socket here is safe."
  [project]
  (if (windows?)
    (let [server (ServerSocket. 0 50 (InetAddress/getLoopbackAddress))]
      (->Listener
       server
       #(.accept server)
       {:transport :tcp
        :host "127.0.0.1"
        :port (.getLocalPort server)}
       (constantly nil)))
    (let [path (socket-path project)
          _ (Files/deleteIfExists path)
          server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (try
        (.bind server (UnixDomainSocketAddress/of path))
        (secure-owner-only! path)
        (->Listener
         server
         #(.accept server)
         {:transport :unix
          :socket-path (str path)}
         #(Files/deleteIfExists path))
        (catch Throwable error
          (.close server)
          (Files/deleteIfExists path)
          (throw error))))))

(defn accept [^Listener listener]
  ((:accept-fn listener)))

(defn endpoint-descriptor [^Listener listener]
  (:descriptor listener))
