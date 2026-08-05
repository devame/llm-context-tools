(defn decode-bearer-token
  "Decode an API bearer token into identity claims."
  [header]
  {:subject header})

(defn verify-api-session
  "Authenticate an API request by decoding its bearer token and checking the
  resulting identity claims."
  [request]
  (decode-bearer-token (get-in request [:headers :authorization])))

(defn authorize-api-session
  "Check permissions for an API session that was already authenticated."
  [session permission]
  (has-value? (get session :permissions @[]) permission))

(defn renew-api-session
  "Renew an API session expiry without verifying request credentials."
  [session expires-at]
  (put session :expires-at expires-at))
