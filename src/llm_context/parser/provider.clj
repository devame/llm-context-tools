(ns llm-context.parser.provider
  (:require [clojure.string :as str]))

(defprotocol ParserProvider
  (supported-languages [provider])
  (parse-source [provider language source]
    "Parse source into a provider-neutral concrete syntax tree."))

(def extension-languages
  {".clj" :language/clojure
   ".cljs" :language/clojurescript
   ".cljc" :language/clojure-common
   ".janet" :language/janet})

(def edn-data-paths
  #{"deps.edn" "bb.edn" "shadow-cljs.edn" ".clj-kondo/config.edn"})

(defn language-for-path [path]
  (let [name (str/replace (str path) "\\" "/")
        dot (.lastIndexOf name ".")]
    (cond
      (contains? edn-data-paths name) :language/edn-data
      (not= -1 dot) (get extension-languages (subs name dot)))))
