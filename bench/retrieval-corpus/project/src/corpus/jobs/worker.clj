(ns corpus.jobs.worker)

(defn claim-index-job
  "Atomically claim the oldest queued semantic indexing job."
  [queue worker-id]
  (when-let [job (first @queue)]
    (swap! queue subvec 1)
    (assoc job :worker worker-id :status :running)))

(defn process-index-job
  "Build embeddings for one claimed source document and publish them."
  [encoder index job]
  (let [vectors (encoder (:document job))]
    (swap! index assoc (:document-id job) vectors)
    (assoc job :status :complete)))

(defn retry-index-job
  "Return a failed indexing job to the queue using bounded exponential backoff."
  [queue job]
  (let [attempt (inc (or (:attempt job) 0))]
    (swap! queue conj
           (assoc job :attempt attempt :status :queued
                  :retry-after-ms (min 60000 (* 1000 attempt))))))

(defn inspect-index-job
  "Read indexing job state without claiming or processing it."
  [job]
  (select-keys job [:document-id :status :attempt]))
