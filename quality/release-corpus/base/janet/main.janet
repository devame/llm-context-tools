(import ./lib/names :as names)

(defn run [value]
  (let [formatter names/format-name]
    (print (formatter (names/format-name value)))))
