(ns corpus.auth.session)

(defn decode-session-cookie
  "Decode the signed browser cookie into a session token."
  [cookie]
  {:token cookie})

(defn load-session-record
  "Load the server-side session record for a decoded token."
  [store token]
  (get store token))

(defn authenticate-request
  "Authenticate an incoming HTTP request by decoding its cookie and loading
  the corresponding server-side session."
  [store request]
  (some->> (get-in request [:headers "cookie"])
           decode-session-cookie
           :token
           (load-session-record store)))

(defn authorize-request
  "Check whether an already authenticated request has a required role."
  [session required-role]
  (contains? (set (:roles session)) required-role))

(defn refresh-session
  "Extend the expiry of an existing session without authenticating a request."
  [session now]
  (assoc session :expires-at (+ now 3600)))
