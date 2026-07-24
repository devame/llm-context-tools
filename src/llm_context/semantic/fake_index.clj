(ns llm-context.semantic.fake-index
  "In-memory SemanticIndex used by deterministic worker and query tests."
  (:require [llm-context.semantic.index :as index]))

(defrecord FakeIndex [state]
  index/SemanticIndex

  (index-health [_]
    (:health @state))

  (ensure-index! [_]
    (swap! state
           (fn [current]
             (-> current
                 (assoc :declared? true)
                 (update :operations conj {:operation :ensure-index}))))
    {:name "llm-context"})

  (add-documents! [_ documents]
    (swap! state
           (fn [current]
             (-> current
                 (update :operations conj
                         {:operation :add
                          :document-ids (mapv :id documents)})
                 (update :documents
                         (fn [stored]
                           (reduce #(assoc %1 (:id %2) %2)
                                   stored documents))))))
    "queued")

  (delete-symbols! [_ symbol-ids]
    (let [symbols (set symbol-ids)]
      (swap! state
             (fn [current]
               (-> current
                   (update :operations conj
                           {:operation :delete
                            :symbol-ids (vec symbol-ids)})
                   (update :documents
                           (fn [documents]
                             (into {}
                                   (remove
                                    (fn [[_ document]]
                                      (contains? symbols
                                                 (:symbol-id document))))
                                   documents)))))))
    "queued")

  (indexed-chunk-count [_ symbol-id document-hash]
    (count
     (filter (fn [document]
               (and (= symbol-id (:symbol-id document))
                    (or (nil? document-hash)
                        (= document-hash (:document-hash document)))))
             (vals (:documents @state)))))

  (search-text [_ query options]
    (swap! state update :operations conj
           {:operation :search :query query :options options})
    (vec (:search-results @state)))

  (close-index! [_]
    (swap! state assoc :closed? true)
    nil))

(defn create
  ([]
   (create {}))
  ([initial]
   (->FakeIndex
    (atom (merge {:health {:healthy? true :model-ready? true :ready? true}
                  :declared? false
                  :documents {}
                  :search-results []
                  :operations []
                  :closed? false}
                 initial)))))

(defn snapshot [fake]
  @(:state fake))

(defn set-search-results! [fake results]
  (swap! (:state fake) assoc :search-results (vec results)))
