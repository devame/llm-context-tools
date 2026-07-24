(ns llm-context.service.watcher-test
  (:require [clojure.test :refer [deftest is]]
            [llm-context.config :as config]
            [llm-context.project :as project]
            [llm-context.service.watcher :as watcher])
  (:import [java.nio.file Files OpenOption StandardOpenOption]))

(deftest recursive-watcher-debounces-source-changes-and-ignores-state
  (let [root (Files/createTempDirectory
              "llm-context-watcher-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        source (.resolve root "src")
        state (.resolve root ".llm-context")
        _ (Files/createDirectories
           source (make-array java.nio.file.attribute.FileAttribute 0))
        _ (Files/createDirectories
           state (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        settings (-> (config/defaults)
                     (assoc-in [:analysis :include] ["."])
                     (assoc-in [:service :watch-initial] false)
                     (assoc-in [:service :watch-debounce-ms] 50))
        calls (atom 0)
        watched (watcher/start!
                 (watcher/create project settings #(swap! calls inc)))]
    (try
      (Files/writeString
       (.resolve state "noise")
       "ignored"
       (into-array OpenOption [StandardOpenOption/CREATE
                               StandardOpenOption/WRITE]))
      (Thread/sleep 400)
      (is (zero? @calls))
      (Files/writeString
       (.resolve source "example.clj")
       "(defn example [] true)"
       (into-array OpenOption [StandardOpenOption/CREATE
                               StandardOpenOption/WRITE]))
      (loop [attempt 0]
        (when (and (zero? @calls) (< attempt 50))
          (Thread/sleep 20)
          (recur (inc attempt))))
      (is (= 1 @calls))
      (finally
        (watcher/stop! watched)))))
