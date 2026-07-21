(ns llm-context.indexer)

(defprotocol SemanticIndexer
  (index-file [indexer file]
    "Return {:file canonical-file :entities [...] :diagnostics [...]} for one file."))
