(ns llm-context.test-support.db
  (:require [datalevin.core :as d]))

(def operation-keys
  [:query :entity :pull :pull-many :transact])

(defn empty-counts []
  (zipmap operation-keys (repeat 0)))

(defmacro with-operation-counts
  "Evaluate body with Datalevin's public operations counted. Returns a map
  containing :value and :counts. The original functions are captured before
  with-redefs so Datalevin internals are not recursively wrapped."
  [& body]
  `(let [counts# (atom (empty-counts))
         query# d/q
         entity# d/entity
         pull# d/pull
         pull-many# d/pull-many
         transact# d/transact!]
     (with-redefs [d/q (fn [& args#]
                         (swap! counts# update :query inc)
                         (apply query# args#))
                   d/entity (fn [& args#]
                              (swap! counts# update :entity inc)
                              (apply entity# args#))
                   d/pull (fn [& args#]
                            (swap! counts# update :pull inc)
                            (apply pull# args#))
                   d/pull-many (fn [& args#]
                                 (swap! counts# update :pull-many inc)
                                 (apply pull-many# args#))
                   d/transact! (fn [& args#]
                                 (swap! counts# update :transact inc)
                                 (apply transact# args#))]
       (let [value# (do ~@body)]
         {:value value#
          :counts @counts#}))))

(defn completes-while-monitor-held?
  "Return true when operation completes while another thread owns monitor."
  [monitor operation timeout-ms]
  (let [acquired (promise)
        release (promise)
        holder (future
                 (locking monitor
                   (deliver acquired true)
                   @release))]
    (deref acquired timeout-ms false)
    (let [work (future (operation))
          outcome (deref work timeout-ms ::timeout)]
      (deliver release true)
      (deref holder timeout-ms nil)
      (when (= ::timeout outcome)
        (future-cancel work))
      (not= ::timeout outcome))))
