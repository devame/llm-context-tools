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

(deftest watcher-failure-is-supervised-and-re-registered
  (let [root (Files/createTempDirectory
              "llm-context-watcher-recovery-"
              (make-array java.nio.file.attribute.FileAttribute 0))
        project (project/context (str root))
        settings (-> (config/defaults)
                     (assoc-in [:analysis :include] ["."])
                     (assoc-in [:service :watch-initial] true))
        attempts (atom 0)
        calls (atom 0)
        statuses (atom [])]
    (with-redefs-fn
      {#'watcher/run!
       (fn [current]
         (if (= 1 (swap! attempts inc))
           (throw (ex-info "watch service closed unexpectedly" {}))
           (do
             ((:on-change current))
             (while (not @(:stop? current)) (Thread/sleep 10))
             :stopped)))}
      (fn []
        (let [watched
              (watcher/start!
               (assoc (watcher/create project settings #(swap! calls inc))
                      :status-fn #(swap! statuses conj %)))]
          (try
            (loop [remaining 200]
              (when (and (zero? @calls) (pos? remaining))
                (Thread/sleep 10)
                (recur (dec remaining))))
            (is (= 1 @calls))
            (is (some #(= :recovering (:status %)) @statuses))
            (is (= :running (:status (last @statuses))))
            (finally
              (watcher/stop! watched))))))))
