# Semantic graph model

Graph format 3 makes analyzer output a canonical, provenance-bearing
interchange contract while preserving the separation between navigable project
facts and diagnostic observations.

## Canonical entities

- Files identify project-relative supported sources and carry content and
  semantic fingerprints.
- Symbols use stable platform/file/qualified-name/kind identities. Moving a
  normal definition or changing its signature does not change its ID.
- Persistent symbols declare their scope, semantic role, and whether they are
  eligible for semantic indexing. Analyzer-local lexical facts do not become
  persistent definitions.
- Edges have an exact existing in-project symbol or topic target, resolution
  `:resolution/exact`, confidence `1.0`, analyzer evidence, and a source range.
- References record external, dynamic, ambiguous, or unresolved observations.
  They have no graph target and cannot participate in traversal.
- Topics identify literal events, subscriptions, effects, coeffects, and
  application-state keys used by focused ClojureScript adapters.
- Effects are derived only from resolved qualified APIs and retain evidence.
- Symbols, edges, references, and effects carry normalized analyzer, evidence,
  and record-kind provenance. Structural adapters add paired zero-based UTF-8
  byte offsets, with an exclusive end, alongside one-based display coordinates.

CLJ and CLJS realms of the same CLJC definition have distinct platform-aware
identities. Datalevin refs are used internally; canonical string IDs are used
at command and export boundaries.

## Ownership and incremental replacement

A file owns its symbols, outgoing edges, references, and effects. Topics are
project-global and are pruned when no edge references them. Whole-project
analyzers produce normalized facts grouped by owning file and a deterministic
semantic fingerprint. An unchanged source file is replaced when another file
changes its resolution result.

Clojure declarations remain analyzer-local unless a concrete definition
exists. Observations belong to the smallest enclosing persistent definition,
and proven protocol, Java, and instance relationships become canonical facts.
Janet uses ordered lexical and module environments, so visibility, shadowing,
and rebinding are resolved at the observation site rather than by a global
name heuristic.

File replacement and its semantic dirty marker are one Datalevin transaction.
Deletion retracts every owned fact and inbound exact edges atomically. Full and
incremental analysis therefore converge without a global name heuristic or a
post-persistence resolution pass.

Before persistence, analyzer observations are normalized and deduplicated by
canonical identity only when the complete facts are structurally identical.
Same-name constructs in distinct lexical scopes retain distinct identities;
conflicting facts for one identity, partial source ranges, out-of-bounds ranges,
and missing owners or targets fail analysis instead of being resolved
implicitly by Datalevin.

Literal extraction is structural rather than regex-based. Janet adapters walk
Tree-sitter nodes. Focused Clojure adapters use tools.reader with evaluation
disabled and create topic facts only for values proven static by the parsed
form.

Malformed or conflicting analyzer snapshots fail closed before persistence.
Queries are gated while a graph update is active, and project-level locks
coordinate full, incremental, and read-only analysis.

## Compatibility

Datalevin metadata records graph format, analyzer name/version, Janet catalog
version, semantic document version, and versioned NextPlaid index name. Normal
queries refuse an incompatible graph with an instruction to run
`llm-context analyze --full`. The rebuild changes only generated project state.
Graph format 3 requires that full rebuild; format-2 data is not migrated.
