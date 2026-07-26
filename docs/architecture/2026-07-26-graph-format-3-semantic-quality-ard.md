# ARD: Graph Format 3 Semantic-Quality Re-architecture

- Date: 2026-07-26
- Status: Implemented
- Release: 0.9.0
- Decision owners: llm-context maintainers
- Scope: Clojure, ClojureScript, CLJC, Janet, Datalevin graph persistence, and LateOn semantic indexing

## Context

Earlier graph formats allowed analyzer observations to reach persistence before
the project had established that the observations formed one coherent semantic
snapshot. This caused several related failure modes:

- unresolved or weakly inferred relationships could look like traversable
  project edges;
- analyzer output could reuse one canonical identity for different facts;
- repeated observations could fail only after a long-running full analysis
  reached Datalevin;
- full and incremental replacement could produce different final graphs;
- readers could observe an update while a multi-transaction replacement was in
  progress;
- semantic documents could remain associated with a superseded graph;
- syntax-shaped observations introduced noise that reduced context and search
  quality.

The product is intentionally focused on Clojure, ClojureScript, CLJC, and Janet.
These languages can be tested deeply with authoritative or purpose-built
semantic analyzers. Unsupported language breadth is less valuable than reliable
relationships for the supported language family.

## Decision

llm-context uses graph format 3 and constructs a complete canonical project
snapshot before mutating Datalevin.

The snapshot is produced by:

- embedded clj-kondo analysis for Clojure, ClojureScript, and CLJC;
- a Tree-sitter Janet parser followed by an ordered lexical and module resolver;
- focused, non-evaluating literal readers for already identified semantic forms;
- a canonicalization and integrity-audit boundary shared by full analysis,
  incremental analysis, and `analyze --check`.

Only exact, evidence-backed, in-project relationships are traversable edges.
External, dynamic, ambiguous, and unresolved observations remain classified,
non-traversable references.

## Duplicate identity policy

Duplicate source definitions can be intentional. Duplicate analyzer
observations are a separate concern.

The canonical layer therefore distinguishes three cases:

1. Byte-equivalent observations with the same canonical identity and
   provenance are duplicate reports of the same fact. They are collapsed
   deterministically.
2. Semantically different definitions that are valid in the language receive
   distinct identities. Examples include same-named methods belonging to
   different protocols, CLJ and CLJS definitions from one CLJC file, and
   repeatable method implementations.
3. Non-equivalent facts that claim the same canonical identity are an analyzer
   or identity-design defect. Analysis stops with a diagnostic containing the
   conflicting producers and fields.

This policy does not delete legitimate duplicate definitions from source code.
It prevents silent last-write-wins behavior and prevents one Datalevin identity
from representing incompatible facts.

## Literal extraction policy

Regexes are not semantic parsers and are not used to recover Clojure or Janet
literals.

- Clojure literals are read with `tools.reader` and `*read-eval*` disabled.
- Janet literals are recovered from Tree-sitter AST nodes.
- Literal inspection is limited to source ranges belonging to forms that the
  semantic analyzer has already identified.
- Computed or dynamic values do not produce static topics.
- Project code and macros are never executed.

Tree-sitter remains useful for Janet because it provides the ordered syntax
tree. The Janet resolver adds the lexical scopes, binding visibility, modules,
imports, exports, macros, and core catalog needed to turn syntax into semantic
facts.

## Canonical graph contract

Every canonical entity has:

- a stable type-specific identity;
- an owning file or deterministic shared owner;
- analyzer and evidence provenance;
- platform and language identity where applicable;
- normalized UTF-8 byte ranges for source-backed facts;
- references only to entities present in the same canonical snapshot.

Every traversable edge has:

- an exact in-project target;
- `:resolution/exact`;
- confidence `1.0`;
- analyzer and evidence provenance;
- a valid source and target in the same graph snapshot.

References cannot participate in graph-neighbour traversal.

Normal top-level symbol identities depend on platform, file, qualified
identity, and kind. Moving a definition or changing its signature does not
normally change the symbol ID. Position-based discrimination is retained only
where the language permits genuinely repeatable definitions.

## Analyzer decisions

### Clojure, ClojureScript, and CLJC

clj-kondo is the authoritative semantic provider.

- One project-wide analysis covers the complete discovered Clojure source set.
- Existing project clj-kondo configuration is respected.
- Generated clj-kondo cache data stays under `.llm-context/cache`.
- Namespace aliases, referred vars, locals, macros, protocols, Java usages, and
  instance invocations are normalized into the canonical intermediate model.
- Repeated declarations are evidence, not duplicate executable definitions.
- The smallest enclosing definition owns a usage.
- Same-named methods in different protocols have protocol-qualified identities.
- Malformed analyzer output fails closed and cannot replace last-good facts.

Focused ClojureScript adapters create typed topics only when literal identity
and state ownership are statically established. Supported flows include
re-frame registrations, dispatches, subscriptions, effects, coeffects, and
common project-state reads and writes.

### Janet

Janet analysis uses two semantic passes:

1. collect modules, definitions, imports, exports, scopes, and binding forms;
2. resolve invocations and references according to lexical visibility, module
   identity, imports, project definitions, and the pinned Janet core catalog.

Only top-level project definitions become persistent searchable symbols. Nested
bindings remain lexical facts used for correct dynamic-call classification.
Sequential binding visibility and shadowing are preserved. Janet special forms
are omitted from call relationships, project functions and macros resolve to
exact edges, core and external module calls become external references, local
callables become dynamic references, and unknown names become unresolved
references.

The semantic catalog baseline is Janet 1.41.2 and is generated reproducibly
with source and license attribution.

## Project analysis and canonicalization

The project analyzer:

1. discovers only supported sources and selected EDN configuration files;
2. requires exactly one analyzer result for every discovered source;
3. normalizes analyzer-specific results into a shared intermediate model;
4. canonicalizes the complete project snapshot;
5. collapses only equivalent observations;
6. rejects conflicting identities;
7. audits ownership, foreign keys, evidence, and source ranges;
8. groups the accepted canonical snapshot for persistence.

`llm-context analyze --check` performs these steps without opening or mutating
the Datalevin graph database. It is the fast preflight for analyzer and
canonical-model integrity.

## Datalevin persistence

Datalevin remains the canonical persistent graph.

Full and incremental analysis share the same preflight and replacement
invariants:

- the complete candidate snapshot is validated before graph mutation;
- an update-in-progress marker makes the graph unavailable to readers during a
  multi-transaction replacement;
- service reads and graph updates use the same project coordination lock;
- retained identities are updated in place;
- only removed identities are retracted;
- deleted files retract all owned graph facts;
- semantic dirty/reset state is written only after graph persistence;
- active graph metadata is changed last;
- an interrupted update remains unavailable until recovery completes.

Ordinary analysis detects an incomplete replacement marker and recovers with a
full rebuild. Queries fail closed rather than observing mixed graph revisions.

Incremental and full analysis must converge to the same exported canonical
facts.

## Semantic index

Graph format 3 uses semantic document format 3 and the versioned LateOn index
name `llm-context-v3`.

- Only explicitly indexable canonical top-level symbols produce documents.
- Namespace wrappers, topics, locals, and diagnostic references are excluded.
- Equivalent semantic documents are deduplicated deterministically.
- Conflicting document identities are rejected.
- Semantic watermarks record the graph revision.
- Search candidates are accepted only when fresh for the active graph revision.
- Missing dirty or reset markers trigger conservative reconciliation.
- Older index generations cannot leak candidates into graph-format-3 results.

## Query and context behavior

Graph navigation begins in Datalevin and uses exact graph facts.

- Callers, callees, relationships, and context traversal use exact project
  edges by default.
- Classified references are available through explicit diagnostic or external
  query paths.
- Topics bridge events, subscriptions, effects, coeffects, and statically
  established application-state keys.
- Context traversal is bounded, typed, deterministic, and performed through
  Datalevin frontier queries.
- External and unresolved observations do not consume the main relationship
  traversal budget.

## Implementation units

### Unit 1: Canonical analyzers and graph format 3

Commit: `9b2d841 feat(analysis): canonicalize graph format 3 semantic facts`

- introduced the canonical intermediate representation;
- added project-wide integrity validation and `analyze --check`;
- added graph-format-3 schema requirements;
- corrected Clojure declaration, protocol, scope, literal, topic, and byte-range
  handling;
- replaced Janet head-text inference with ordered lexical/module resolution;
- added conflict-aware identity handling and analyzer-completeness tests.

### Unit 2: Fail-closed Datalevin replacement

Commit: `65abaca refactor(store): make graph replacement fail closed`

- added snapshot preflight before mutation;
- coordinated service reads and graph writes;
- introduced update-in-progress recovery behavior;
- made ownership and foreign-key checks symmetric;
- corrected retained-identity replacement so incremental analysis converges
  with full analysis;
- added interruption and convergence coverage.

### Unit 3: Versioned semantic documents

Commit: `2b44b06 feat(semantic): version canonical index documents`

- introduced semantic document version 3;
- rotated the default index to `llm-context-v3`;
- tied freshness and watermarks to graph revisions;
- added deterministic document identity validation;
- added conservative worker reconciliation.

### Unit 4: Release quality gates and documentation

Commit: `543eee7 release: prepare llm-context 0.9.0`

- added a representative Clojure and Janet release corpus;
- added packaged-JAR graph-quality verification;
- verified full and incremental convergence;
- documented graph format 3 and the full-rebuild upgrade boundary;
- updated version and release metadata.

### Unit 5: Release runner compatibility

Commit: `83c4eb6 fix(release): make packaged quality gate executable`

- recorded the packaged quality verifier as executable for Linux CI runners.

## Validation and acceptance evidence

The implementation was accepted with:

- 178 tests and 640 assertions passing;
- zero test failures or errors;
- a non-mutating audit of 272 files from the reference project;
- 63,627 accepted canonical entities in that reference-project audit;
- no duplicate canonical identity failure;
- packaged-JAR full and incremental runs producing identical exports;
- packaged verification of provenance, UTF-8 byte ranges, exact edge integrity,
  graph format, semantic document version, and semantic index identity;
- successful Unix installer smoke testing;
- successful installed LateOn pipeline smoke testing;
- successful Linux x64, Windows x64, and macOS ARM runtime packaging;
- successful publication of release 0.9.0 with checksummed assets.

## Upgrade and recovery

Graph format 3 is intentionally incompatible with older generated graphs. There
is no legacy graph migration.

After installing 0.9.0, run:

```bash
llm-context analyze --full
```

The full analysis:

- validates the complete canonical snapshot;
- replaces old canonical graph facts;
- activates graph format 3 only after successful persistence;
- clears obsolete semantic queue metadata;
- queues version-3 semantic documents;
- uses `llm-context-v3`, so old vectors are ignored.

Manual deletion of `.llm-context` is not normally required. If a previous
replacement was interrupted, the update marker keeps queries unavailable and a
subsequent analysis performs the required recovery.

## Consequences

### Gains

- Graph traversal reflects exact semantic evidence rather than syntax-shaped
  guesses.
- Duplicate analyzer reports cannot silently overwrite one another.
- Legitimate language-level duplicate names remain representable.
- Full and incremental analysis have one canonical correctness boundary.
- Datalevin is used as the graph engine and query boundary rather than as a
  serialized cache behind raw in-memory traversal.
- Semantic search has an explicit graph-revision freshness contract.
- Release automation tests the packaged artifact and installed runtime, not only
  source-tree behavior.
- Failures occur before persistence with actionable identity, ownership, range,
  and provenance information.

### Costs and limitations

- Older generated graphs require one full rebuild.
- Whole-project semantic snapshots use more analysis work than isolated
  per-file syntax parsing, although clj-kondo caching and semantic fingerprints
  limit unnecessary persistence.
- Janet semantic behavior depends on the maintained resolver and pinned core
  catalog.
- Dynamic language constructs remain classified references instead of being
  promoted speculatively into graph edges.
- During a multi-transaction rebuild the graph is unavailable rather than
  serving the previous revision. Recovery is safe and explicit, but the current
  implementation does not retain a separately queryable sibling database for
  atomic revision switching.

## Rejected alternatives

### Preserve global unique-name heuristic resolution

Rejected because name uniqueness is not semantic evidence and changes as the
project grows. It creates false cross-scope and cross-module relationships.

### Persist unresolved calls as graph edges

Rejected because a targetless relationship cannot be traversed reliably.
Uncertainty belongs in classified diagnostic references.

### Parse literals with regexes

Rejected because strings, escapes, metadata, reader forms, whitespace, nested
collections, and Janet syntax cannot be handled reliably with text patterns.

### Execute project build tools or macros

Rejected because analysis must be safe, deterministic, and source-first.

### Repair conflicting identities during persistence

Rejected because Datalevin is too late to decide whether two observations are
equivalent, distinct, or erroneous. That decision requires analyzer provenance
and must occur at the canonical snapshot boundary.

### Migrate graph formats 1 or 2 in place

Rejected because generated graph and semantic-index data are reproducible. A
clean full rebuild is simpler and safer during the greenfield phase.
