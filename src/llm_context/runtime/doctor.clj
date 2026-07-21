(ns llm-context.runtime.doctor
  (:require [clojure.string :as str]
            [llm-context.config :as config]
            [llm-context.store :as store])
  (:import [java.nio.file Files LinkOption Path Paths]))

(def minimum-java-feature 23)

(defn java-feature
  ([] (.feature (Runtime/version)))
  ([version]
   (let [parts (str/split (str version) #"\.")
         major (if (= "1" (first parts)) (second parts) (first parts))]
     (Long/parseLong (re-find #"\d+" major)))))

(defn- path-candidates [executable]
  (let [path (or (System/getenv "PATH") "")
        extensions (if (str/starts-with? (str/lower-case (System/getProperty "os.name"))
                                         "windows")
                     ["" ".exe" ".cmd" ".bat"]
                     [""])]
    (for [directory (str/split path (re-pattern (java.io.File/pathSeparator)))
          extension extensions]
      (Paths/get directory (into-array String [(str executable extension)])))))

(defn executable? [executable]
  (boolean
   (some #(and (Files/isRegularFile ^Path % (make-array LinkOption 0))
               (Files/isExecutable ^Path %))
         (path-candidates executable))))

(defn check
  "Return structured runtime checks. Optional semantic providers are reported
  without making structural analysis unavailable."
  [project config]
  (let [java (java-feature)
        java-check {:check :java
                    :required? true
                    :ok? (>= java minimum-java-feature)
                    :detail (str "JDK " java " (requires " minimum-java-feature "+)")}
        writable-check {:check :project-writable
                        :required? true
                        :ok? (Files/isWritable ^Path (:root project))
                        :detail (:root-str project)}
        datalevin-check (try
                          (store/with-store [graph project config]
                            (store/query graph '[:find ?e :where [?e :entity/type _]] []))
                          {:check :datalevin :required? true :ok? true
                           :detail (str (.resolve ^Path (:root project)
                                                 (get-in config [:store :path])))}
                          (catch Throwable error
                            {:check :datalevin :required? true :ok? false
                             :detail (.getMessage error)}))
        scip-command (first (get-in config [:semantic :scip-typescript-command]))
        scip-check {:check :scip-typescript
                    :required? false
                    :ok? (and scip-command (executable? scip-command))
                    :detail (if scip-command
                              (str "launcher " scip-command)
                              "provider disabled")}]
    [java-check writable-check datalevin-check scip-check]))

(defn healthy? [checks]
  (every? #(or (not (:required? %)) (:ok? %)) checks))

(defn print-report [checks]
  (doseq [{:keys [check required? ok? detail]} checks]
    (println (format "%-5s %-20s %s%s"
                     (if ok? "ok" "fail")
                     (name check)
                     detail
                     (if required? "" " (optional)")))))
