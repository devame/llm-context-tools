(defn enqueue-semantic-document
  "Place a source document on the semantic embedding work queue."
  [queue document]
  (array/push queue {:document document :status :queued}))

(defn flush-document-batch
  "Encode and publish a batch of semantic documents to the vector index."
  [encoder index documents]
  (each document documents
    (put index (get document :id) (encoder document)))
  (length documents))

(defn retry-document-batch
  "Requeue a failed semantic document batch after a bounded delay."
  [queue documents attempt]
  (each document documents
    (array/push queue {:document document :attempt (+ attempt 1)})))

(defn inspect-document-batch
  "Return diagnostic state for a batch without encoding or publishing it."
  [documents]
  (map |(get $ :status) documents))
