## 0.12.5

- feat: add operational health and self-healing




## 0.12.4

- Add GPU and CUDA host preflight reporting for the CLI, installer, and
  runtime doctor, including NVIDIA driver, device, CUDA, and cuDNN checks.
- Add interactive `llm-context setup` guidance with an explicitly confirmed
  Debian/Ubuntu cuDNN installation path and WSL-specific driver instructions.
- Make the Linux installer select the CUDA or CPU semantic runtime in `auto`
  mode, fail early for incomplete explicit CUDA requests, and surface runtime
  provider failures instead of hiding them behind CPU fallback.


# Changelog

## 0.12.3

- Automatically start the project service after local semantic analysis queues
  durable indexing work, preventing a stopped service from leaving the queue
  permanently pending.
- Add `analyze --no-service` for one-shot graph analysis and CI workflows, and
  report automatic service-start failures clearly.
- Update onboarding, benchmark, and packaged-release verification instructions
  for the new service lifecycle.

## 0.12.2

- Added safe, non-evaluating ClojureScript `#js` literal support to aggregate
  analysis, including nested objects and vectors with validation.
- Added aggregate counts, semantic-document indexing state, and skipped-file
  diagnostics to `llm-context semantic status`.

## 0.12.1

- Automatically rebuild older incompatible graph formats on a normal
  `llm-context analyze`, while keeping queries fail-closed until the rebuild
  completes. `analyze --full` remains available to force a rebuild.
- Published the installer download progress and timeout/retry improvements
  from the 0.12.0 maintenance update.

## 0.12.0

- Added graph-format 4 aggregate and concept entities so retrieval can combine
  granular symbols with bounded module and repository-level meaning.
- Added adaptive query planning, structural evidence qualification, diverse
  seed selection, and exact-relationship-aware context expansion.
- Added the verified 32M Mixedbread candidate reranker with shadow/enforce
  modes, bounded caches, telemetry, and deterministic fallback.
- Added durable full-analysis and semantic progress reporting that can be
  watched safely from another terminal.
- Added one verified model-package contract for semantic retrieval, query
  routing/reranking, and optional answer reading, including pinned manifests,
  local or Hugging Face-compatible sources, runtime registries, and mandatory
  SHA-256 verification with no unverified escape hatch.
- Added self-healing resident-service lifecycle handling, host-native
  supervisors, and verified CUDA/accelerator selection for semantic runtimes.
- Made full graph replacement resumable and storage-safe with bounded staging
  generations, headroom checks, recovery status, safe cleanup, and read-only
  storage inventory.
- Added provider-aware crash recovery, verified compact graph copies, exact
  semantic-document reuse, and serialized analysis finalization during
  shutdown.

## 0.11.0

- Added a pinned public cross-repository semantic evaluation suite for
  clojure-lsp, re-frame, and Metabase, with development and held-out corpora,
  hard negatives, deterministic repeats, bootstrap confidence intervals, and
  aggregate-only output.
- Added explicit `fts-only`, `lateon-only`, and `hybrid` retrieval modes with
  comparable recall, MRR, nDCG, context, slice, latency, and freshness
  diagnostics.
- Made canonical graph traversal lock-free and bounded, including bounded
  transitive call tracing, lexical fallback, status aggregation, and
  high-connectivity semantic metadata.
- Reworked Clojure-family materialization around indexed ownership, source
  coordinates, framework forms, and namespace declarations, eliminating
  repository-wide per-usage owner scans on dense projects.
- Added rebuildable analyzer dependency manifests and incremental dependency
  closures so unchanged analysis can reuse validated analyzer snapshots while
  preserving full-versus-incremental convergence.
- Reduced full-analysis memory and persistence cost through streamed semantic
  fingerprints, bounded snapshot state, batched replacement planning, and
  identity-indexed semantic-state resets and traversal.
- Shortened service analysis coordination and moved dirty semantic
  reconciliation fully into the background so queries and watcher-triggered
  analysis remain responsive during large synchronizations.
- Made semantic synchronization resumable across compatible full analyses,
  preserved valid document hashes, verified the physical NextPlaid generation
  sentinel, and rebuilt conservatively when the external index is missing or
  recreated.
- Pipelined concurrent asynchronous NextPlaid updates, bulk inventory and
  visibility checks, batched durable leasing/renewal/completion transitions,
  and progress telemetry while retaining conditional completion and lease
  recovery semantics.
- Added bounded public-evaluation stages, reusable analyzer snapshots, dense
  scaling gates, packaged semantic convergence checks, and corrected corpus
  resolution/count validation for release qualification.

## 0.10.0

- Kept semantic status responsive by moving NextPlaid I/O and semantic
  retrieval outside the graph coordination lock.
- Added `context --intent` for freshness-safe LateOn plus Datalevin hybrid
  resolution of a natural-language request into one canonical context seed.
- Preserved exact graph traversal as the source of context relationships;
  semantic alternatives remain bounded provenance metadata only.
- Added lexical fallback, focus-resolution packet metadata, and benchmark
  measurements for context seed and packet recall.

## 0.9.0

- Introduced graph format 3 with a canonical analyzer interchange contract,
  normalized provenance, explicit persistent-symbol roles and scopes, and
  paired UTF-8 byte offsets alongside analyzer display-coordinate ranges.
- Added whole-snapshot integrity checks for conflicting identities, missing
  owners or targets, partial ranges, and ranges outside their owning files.
- Added `analyze --check` for the same canonical source-snapshot audit without
  opening or changing the graph database.
- Canonicalization collapses only structurally identical observations. It
  preserves legitimate same-name constructs in distinct scopes and fails
  closed when different facts claim the same canonical identity.
- Required Clojure-family and Janet analyzers to emit evidence-backed canonical
  definitions, relationships, diagnostic references, and effects before
  persistence.
- Replaced regex literal extraction with structural parsing: Janet walks
  Tree-sitter nodes, while Clojure uses tools.reader with evaluation disabled
  and emits topics only for forms proven static.
- Made Clojure declarations analyzer-local until a concrete definition exists,
  assigned observations to the smallest enclosing definition, and materialized
  protocol implementations plus statically proven Java and instance calls.
- Reworked Janet resolution around ordered lexical and module scopes, including
  shadowing, imports, visibility, destructuring, and stable rebinding
  identities. Malformed analyzer snapshots fail closed without persisting
  partial facts.
- Gated queries while graph updates are active and coordinated full,
  incremental, and read-only analysis with project-level concurrency locks.
- Introduced semantic document/index v3 with explicit symbol indexability,
  deterministic document conflict detection, graph-revision freshness
  watermarks, and automatic reconciliation recovery.
- Added a release-quality corpus covering Clojure and Janet, packaged-jar graph
  analysis, canonical invariant checks, and full-versus-incremental convergence
  to both CI and tagged-release validation.

Existing generated graphs require one `llm-context analyze --full` rebuild
after upgrading; no format-2 graph migration is attempted.

## 0.8.1

- Recovered ClojureScript macro-bound local call names from clj-kondo source
  ranges when analysis-data omits `:name`, preventing valid callbacks such as
  `cljs.test/async`'s `done` from producing blank diagnostic references.
- Added a safe omission path when an unnamed local has no recoverable source
  token and regression coverage for both normalized and real clj-kondo input.
- Preflight the complete graph replacement before clearing semantic queue and
  freshness records, so an invalid analyzer snapshot cannot partially alter
  existing generated state.
- Print the offending entity and concise spec failure when canonical graph
  validation rejects analyzer output.

## 0.8.0

- Restricted analysis to Clojure, ClojureScript, CLJC, Janet, and selected EDN
  project configuration; unsupported source extensions are silently ignored,
  and Node/SCIP are no longer installed or invoked.
- Embedded clj-kondo 2026.07.24 as the authoritative Clojure-family analyzer,
  including namespaces, aliases, vars, locals, macros, protocols, CLJC realms,
  and project configuration without executing build tools or project macros.
- Replaced Janet head-text inference with a two-pass lexical, module, import,
  macro, and core-catalog resolver pinned to Janet 1.41.2.
- Introduced graph format 2: only evidence-backed exact in-project
  relationships are traversable; external, dynamic, ambiguous, and unresolved
  observations are non-traversable classified references.
- Added stable platform-aware symbol identities, semantic fingerprints,
  exact-edge invariants, and whole-project incremental convergence when
  definitions are added, renamed, or removed.
- Added ClojureScript re-frame and application-state topics connecting event
  registration/dispatch, subscriptions, effects/coeffects, and statically
  recoverable state reads and writes.
- Added exact-by-default callers/callees, explicit external-reference and topic
  queries, symbol suggestions, and deterministic weighted context paths with
  graph/token truncation reported separately.
- Made semantic fallback observable with `query search --explain`, separated
  runtime availability from index completeness, and added failed/dirty
  inspection plus explicit terminal-job retry.
- Added graph-format/analyzer/catalog/document metadata, coordinated full
  rebuilds through the resident service, and rotated the default semantic
  collection to `llm-context-v2`.
- Added a checksummed installed user guide and release asset. Existing
  `.llm-context` graphs must be rebuilt once with
  `llm-context analyze --full`; no legacy migration is attempted.

## 0.7.1

- Fixed malformed UTF-8 handling by sharing deterministic replacement decoding
  between structural and semantic analysis and emitting per-file diagnostics.
- Isolated semantic reconciliation failures so one problematic source file no
  longer strands unrelated LateOn jobs or terminates the background worker.
- Added worker-health reporting to `semantic status`, `semantic sync --wait`,
  and `doctor` with actionable failure details.

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
