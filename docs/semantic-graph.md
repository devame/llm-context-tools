# Semantic graph model

Graph format 2 separates navigable project facts from diagnostic observations.

## Canonical entities

- Files identify project-relative supported sources and carry content and
  semantic fingerprints.
- Symbols use stable platform/file/qualified-name/kind identities. Moving a
  normal definition or changing its signature does not change its ID.
- Edges have an exact existing in-project symbol or topic target, resolution
  `:resolution/exact`, confidence `1.0`, analyzer evidence, and a source range.
- References record external, dynamic, ambiguous, or unresolved observations.
  They have no graph target and cannot participate in traversal.
- Topics identify literal events, subscriptions, effects, coeffects, and
  application-state keys used by focused ClojureScript adapters.
- Effects are derived only from resolved qualified APIs and retain evidence.

CLJ and CLJS realms of the same CLJC definition have distinct platform-aware
identities. Datalevin refs are used internally; canonical string IDs are used
at command and export boundaries.

## Ownership and incremental replacement

A file owns its symbols, outgoing edges, references, and effects. Topics are
project-global and are pruned when no edge references them. Whole-project
analyzers produce normalized facts grouped by owning file and a deterministic
semantic fingerprint. An unchanged source file is replaced when another file
changes its resolution result.

File replacement and its semantic dirty marker are one Datalevin transaction.
Deletion retracts every owned fact and inbound exact edges atomically. Full and
incremental analysis therefore converge without a global name heuristic or a
post-persistence resolution pass.

## Compatibility

Datalevin metadata records graph format, analyzer name/version, Janet catalog
version, semantic document version, and versioned NextPlaid index name. Normal
queries refuse an incompatible graph with an instruction to run
`llm-context analyze --full`. The rebuild changes only generated project state.
