# Changelog

## 0.4.2

- Changed initialization to confirm the canonical project root before writing
  configuration; automation can use `init --yes`.
- Changed default discovery to scan the complete project root while honoring
  Git ignores and pruning generated/cache directories.
- Added actionable diagnostics for missing includes and skipped known
  languages while ignoring unrelated extensions.
- Made full graph replacement atomic so cross-file references resolve
  regardless of file transaction order.

## 0.4.1

- Updated the embedded Datalevin dependency to 1.0.0.
- Added a complete installed-user workflow guide.

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
- Added checksum-verifying one-script installers for Unix and Windows.
- Added tagged-release automation for the jar and checksum artifacts.

This is a greenfield cutover. No legacy JSON configuration or JSONL database
migration is provided.
