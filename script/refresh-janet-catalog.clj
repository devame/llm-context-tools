(ns refresh-janet-catalog
  "Maintenance entry point for reviewing the checked-in Janet catalog.

  Refreshes are intentionally review-driven: project analysis must never fetch
  or execute code. Pass the proposed catalog file and this script validates its
  version before it is committed. With --refresh, the script extracts the
  documented top-level bindings and macro kinds from a pinned Janet API page."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def binding-pattern
  #"class=\"binding-sym\">([^<]+)</span> <span class=\"binding-type\">([^<]+)")

(defn- decode-html [value]
  (-> value
      (str/replace "&amp;" "&")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")))

(defn- refresh! [url path]
  (let [catalog (edn/read-string (slurp (io/file path)))
        bindings (map (fn [[_ name kind]]
                        [(decode-html name) kind])
                      (re-seq binding-pattern (slurp url)))
        names (into (into (sorted-set) (:core catalog))
                    (map first) bindings)
        macros (into (sorted-set)
                     (keep (fn [[name kind]]
                             (when (= "macro" kind) name)))
                     bindings)
        refreshed (assoc catalog :core names :core-macros macros)]
    (when-not (seq bindings)
      (throw (ex-info "No Janet bindings found in API document" {:url url})))
    (with-open [writer (io/writer (io/file path))]
      (binding [*out* writer]
        (pprint/pprint refreshed)))))

(defn -main [& args]
  (let [[path]
        (if (= "--refresh" (first args))
          (let [[_ url output] args]
            (when-not (and url output)
              (throw
               (ex-info
                "usage: clojure -M script/refresh-janet-catalog.clj --refresh URL FILE"
                {:exit-code 2})))
            (refresh! url output)
            [output])
          args)]
  (when-not path
    (throw (ex-info "usage: clojure -M script/refresh-janet-catalog.clj FILE"
                    {:exit-code 2})))
  (let [catalog (edn/read-string (slurp (io/file path)))]
    (when-not (re-matches #"\d+\.\d+\.\d+" (:version catalog))
      (throw (ex-info "Janet catalog requires a semantic version"
                      {:catalog path})))
    (doseq [key [:special-forms :binding-forms :definition-forms
                 :core :core-macros]]
      (when-not (seq (get catalog key))
        (throw (ex-info (str "Janet catalog is missing " key)
                        {:catalog path :key key}))))
    (println "Validated Janet catalog" (:version catalog)
             "with" (count (:core catalog)) "core names and"
             (count (:core-macros catalog)) "macros"))))

(apply -main *command-line-args*)
