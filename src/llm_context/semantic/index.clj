(ns llm-context.semantic.index)

(defprotocol SemanticIndex
  (index-health [client]
    "Return normalized service, model, and index readiness.")
  (ensure-index! [client]
    "Declare the configured project index if it does not exist.")
  (add-documents! [client documents]
    "Submit already-structured text chunks for asynchronous encoding.")
  (delete-symbols! [client symbol-ids]
    "Submit deletion of every indexed chunk belonging to the symbols.")
  (indexed-chunk-count [client symbol-id document-hash]
    "Count visible chunks for a symbol, optionally restricted to one hash.")
  (search-text [client query options]
    "Search with a text query and return normalized ranked candidates.")
  (close-index! [client]
    "Release client resources. Implementations may be a no-op."))
