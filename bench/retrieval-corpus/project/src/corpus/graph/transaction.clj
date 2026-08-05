(ns corpus.graph.transaction)

(defn normalize-transaction
  "Canonicalize graph facts before they are written to durable storage."
  [facts]
  (vec (distinct facts)))

(defn persist-graph-transaction
  "Commit a normalized collection of graph facts to the durable database."
  [database facts]
  (let [transaction (normalize-transaction facts)]
    (swap! database into transaction)
    {:committed (count transaction)}))

(defn replay-graph-transaction
  "Reapply a historical graph transaction during disaster recovery."
  [database transaction-log]
  (doseq [facts transaction-log]
    (persist-graph-transaction database facts)))

(defn validate-graph-transaction
  "Reject malformed graph facts before persistence."
  [facts]
  (every? map? facts))
