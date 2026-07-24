# Changelog

## 0.7.0

- Added a durable, coalesced Datalevin outbox for asynchronous LateOn-Code
  indexing with leases, bounded retries, recovery, and freshness watermarks.
- Added deterministic, versioned symbol documents and a supervised
  NextPlaid 1.6.4/ONNX Runtime 1.23.0 sidecar using the immutable INT8
  LateOn-Code model revision.
- Added freshness-safe hybrid `query search`, semantic status/synchronization,
  and deterministic lexical fallback when the model is unavailable.
- Changed the resident service into a detached project coordinator with an
  initial scan, recursive debounced file watching, background ingestion, and
  loopback-only authenticated model queries.
- Extended the Unix and Windows installers to provision and checksum the
  platform runtime and required model files without Docker, Python, or Rust.
- Added component-level doctor checks, a real release-time model/index/search
  gate, and an EDN-driven larger-query benchmark harness.

## 0.6.0

- Added model-free Datalevin full-text indexing for symbol names, qualified
  names, signatures, and documentation.
- Added relevance-ranked natural-language symbol and context lookup while
  preserving exact-name and literal substring matching.
- Added automatic, bounded backfilling for graphs created before the search
  index existed and ensured replacement and deletion retract stale terms.

## 0.5.1

- Changed full graph replacement from one unbounded Datalevin transaction to
  dependency-ordered transactions of at most 100 records.
- Added visible discovery, parsing, semantic, resolution, and persistence
  progress for full analysis.
- Kept analysis in the invoking CLI process so a resident-service timeout
  cannot fall back to a second concurrent database writer.

## 0.5.0

- Added first-class Janet discovery, parsing, structural symbols, calls,
  module imports, and effect classification.
- Embedded a pinned Tree-sitter Janet grammar for every platform shipped by
  the Tree-sitter core runtime; Janet and a C toolchain are not runtime
  requirements.
- Added a reproducible Zig-based native grammar build and recorded its source,
  revision, ABI, and license provenance.
- Fixed the repository's composite GitHub Action to install the tagged,
  checksum-verified release instead of an unrelated public npm package.
- Rebuilt the official Tree-sitter 0.25.3 Windows core DLL with its public C
  API exported so JTreeSitter can initialize and load packaged grammars.

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
