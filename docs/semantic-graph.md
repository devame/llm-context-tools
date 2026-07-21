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

## Ownership and replacement

A file owns its symbols, effects, and edges whose `from` symbol belongs to the
file. It does not own inbound edges from other files. When a target file changes
or is deleted, those inbound edges are preserved, their stale `to` references
are retracted, and graph-wide reconciliation determines whether the new symbol
set makes them exact, heuristic, ambiguous, or unresolved.

This ownership rule is what makes incremental deletion semantically equivalent
to a fresh full analysis.

## Resolution

SCIP occurrence evidence can establish exact JS/TS targets by matching
definition and reference ranges. Without semantic evidence, a unique canonical
or simple symbol name is a heuristic target. Multiple matches are recorded as
ambiguous and no match remains unresolved. No arbitrary target is selected to
make the graph appear complete.

## Schema evolution

The database schema is colocated with the domain specifications in
`src/llm_context/model/schema.clj`. This is a greenfield 0.4 release: there is
no legacy JSONL importer or automatic schema migration. During pre-release
development, rebuild `.llm-context/` after incompatible schema changes.
