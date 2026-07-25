# Semantic graph model

## Entities

- Files have deterministic `file:` IDs, project-relative paths, language,
  SHA-256 content hash, byte size, and modification time.
- Symbols have deterministic `symbol:` IDs, names, qualified names, kind,
  owning file, source range, and optional signature/documentation.
- Edges are first-class entities with deterministic `edge:` IDs, kind, source
  symbol, target text, optional resolved target, source evidence, resolution
  state, and confidence.
- Effects have deterministic `effect:` IDs, owning symbol, effect kind, source
  evidence, detail, and confidence.

References are Datalevin refs internally and canonical string IDs at the domain
and export boundaries.

Derived, indexed attributes are stored when they make a graph operation
selective. For example, `:edge/target-name` is derived from target text so an
incremental symbol change can locate potentially affected edges without loading
every edge. These attributes are deterministic and resumably backfilled when an
existing database is opened.

## Ownership and replacement

A file owns its symbols, effects, and edges whose `from` symbol belongs to the
file. It does not own inbound edges from other files. When a target file changes
or is deleted, those inbound edges are preserved, their stale `to` references
are retracted, and graph-wide reconciliation determines whether the new symbol
set makes them exact, heuristic, ambiguous, or unresolved.

This ownership rule is what makes incremental deletion semantically equivalent
to a fresh full analysis.

## Resolution

Analyzer evidence establishes exact project targets. Weaker observations are
diagnostic data and never justify selecting an arbitrary target merely to make
the graph appear complete.

Both full and incremental resolution operate on persisted facts. A full run
selects all persisted edge identities explicitly. An incremental run selects
only edges owned by changed files or whose indexed target identity intersects
the old/new symbol names. Candidate symbols and source-point definitions are
then selected by Datalog before a bounded decision set is transacted.

## Schema evolution

The database schema is colocated with the domain specifications in
`src/llm_context/model/schema.clj`. Deterministic derived attributes have
version markers and resumable backfills. There is no legacy JSONL importer;
JSONL remains an export format rather than a persistence or migration path.
