# Changelog

## 0.4.0

- Reimplemented the application core in Clojure 1.12.
- Made embedded Datalevin the authoritative semantic graph and Datalog engine.
- Added deterministic files, symbols, typed edges, effects, resolution states,
  confidence, and source evidence.
- Added full and graph-correct incremental analysis, including deletion and
  inbound-edge reconciliation.
- Added official JTreeSitter integration and twelve packaged structural
  grammars.
- Added optional SCIP TypeScript semantic enrichment.
- Added Datalog query commands, budgeted context packets, summaries, and
  EDN/JSON/JSONL/Markdown exports.
- Added a measured authenticated resident service for interactive latency.
- Replaced the Node runtime with an uberjar and thin npm launcher.

This is a greenfield cutover. No legacy JSON configuration or JSONL database
migration is provided.
