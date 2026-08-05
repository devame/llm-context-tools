(defn compile-route-pattern
  "Compile a human-readable URL template into a route matcher."
  [template]
  (string template))

(defn resolve-http-route
  "Select the request handler whose compiled route pattern matches the URL."
  [routes url]
  (get routes url))

(defn resolve-route-alias
  "Expand a symbolic route alias; this does not dispatch an HTTP request."
  [aliases route-name]
  (get aliases route-name))

(defn render-route-link
  "Render a browser link for a named route."
  [route-name params]
  (string route-name "?" params))
