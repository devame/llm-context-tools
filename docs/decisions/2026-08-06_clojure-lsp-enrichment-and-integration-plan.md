# ARD: Clojure LSP enrichment and integration plan

- Date: 2026-08-06
- Status: Proposed
- Target: Post-0.11.0 qualification; candidate for 0.11.x or 0.12.0
- Decision owners: llm-context maintainers
- Scope: Clojure, ClojureScript, CLJC, project dependency metadata, external
  library symbols, and the canonical graph/analyzer boundary

## Context

The current analyzer already has a source-level dependency graph. Embedded
clj-kondo supplies namespace definitions, namespace usages, var definitions,
var usages, locals, protocol implementations, Java usages, and instance
invocations. The materializer turns in-project namespace and var relationships
into exact graph edges and leaves external, ambiguous, dynamic, and unresolved
observations as classified references.

The phrase “dependency graph” is therefore overloaded:

1. The **source dependency graph** describes relationships between namespaces,
   vars, protocols, and calls in the discovered project sources. We already
   compute this.
2. The **build/classpath dependency graph** describes dependency coordinates,
   versions, aliases, source roots, transitive libraries, external namespaces,
   Java classes, and symbols supplied by jars or local dependencies. We do not
   currently resolve this graph.

That omission is deliberate. The product is source-first: it does not invoke
Leiningen, `clojure -Spath`, Shadow-CLJS, project macros, or other project build
commands during ordinary analysis. This makes full and incremental analysis
deterministic across arbitrary checkouts and keeps the canonical graph focused
on project-owned source facts.

Clojure LSP is a possible enrichment provider. It uses clj-kondo for most
static analysis, but also performs project/classpath discovery and exposes a
CLI/API surface for diagnostics, references, project symbols, dependency data,
and a currently experimental `dump` operation. The authoritative references
for this decision are:

- [Clojure LSP CLI](https://clojure-lsp.io/api/cli/)
- [Clojure LSP settings and classpath scan](https://clojure-lsp.io/settings/)
- [Clojure LSP features](https://clojure-lsp.io/features/)
- [Calva and Clojure LSP](https://calva.io/clojure-lsp/)

## Problem statement

We need richer project and dependency context without creating a competing
semantic source of truth. In particular, we want to know which computations
can be delegated to Clojure LSP, which computations remain mandatory for the
llm-context graph contract, and how to add the integration without making the
release depend on a local editor installation or an unpinned classpath.

The existing `external-symbols` argument in the project analyzer is not yet a
library-integration boundary. In incremental analysis it currently carries
symbols from unaffected project files so that an affected-file re-analysis can
resolve against the retained project snapshot. It must not be interpreted as
already-normalized external dependency symbols.

## Decision

Add Clojure LSP as an **optional, pinned enrichment provider** after the
0.11.0 release qualification. Do not replace the canonical source analyzer in
the first integration.

The first implementation will:

- retain embedded clj-kondo as the authoritative provider for project-owned
  Clojure/ClojureScript/CLJC facts;
- invoke a pinned Clojure LSP CLI/API only when explicitly enabled;
- consume its public API or `dump` output, never its private `.lsp` cache
  database;
- normalize dependency/classpath/external-symbol observations into a separate
  enrichment model with explicit provenance and fingerprints;
- use existing canonical project identities when an enrichment observation can
  be joined to a project entity;
- keep external dependency observations non-traversable and non-indexable by
  default; and
- allow base source analysis to complete when enrichment is disabled,
  unavailable, stale, timed out, or malformed.

This keeps the release-safe graph contract intact while creating a measured
path to richer navigation and retrieval. A later provider-substitution phase
may remove duplicated direct clj-kondo orchestration only after graph parity
and output-fidelity evidence proves that Clojure LSP can supply every required
source fact.

Janet remains unchanged. Its Tree-sitter parser and ordered lexical/module
resolver remain the authoritative Janet pipeline. This decision does not add a
Clojure Tree-sitter grammar.

## Computation disposition

The table below distinguishes computations that can be delegated, computations
that are duplicated only in a future implementation, and computations that
must remain local because they enforce the llm-context contract.

| Computation | Current state | Disposition | Reason |
| --- | --- | --- | --- |
| Source-file discovery, extension policy, exclusions, size and binary checks | Local | Keep | Defines the project source contract and protects analysis from unsupported or unsafe files. |
| UTF-8 decoding, malformed-input diagnostics, line/column-to-byte mapping | Local | Keep | Clojure LSP ranges must still be normalized to the graph's UTF-8 range contract. |
| File/content hashes and semantic fingerprints | Local | Keep | Drives graph identity, incremental invalidation, semantic freshness, and source-change checks. |
| Project namespace definitions and usages | Embedded clj-kondo | Keep in phase 1; possible later delegation | These facts already feed canonical IDs and exact project edges. A replacement must prove complete parity. |
| Var definitions, usages, locals, protocols, Java usages, instance invocations | Embedded clj-kondo | Keep in phase 1; possible later delegation | Clojure LSP obtains much of this from clj-kondo, but its normalized output/version/config behavior must be compared before removing our adapter. |
| In-project namespace/import and var relationship materialization | Local | Keep | LSP observations still need canonical ownership, identity, ambiguity handling, evidence, and exact-edge validation. |
| Custom source-level call/name heuristics | Avoided by current design | Do not add | Clojure LSP is a better source of semantic facts than another syntax or string heuristic. |
| Clojure Tree-sitter syntax parser for semantic extraction | Not present | Discard as a proposed direction | It would duplicate syntax parsing without supplying namespace, classpath, or semantic ownership. |
| Reader-disabled Clojure literal/topic extraction | `tools.reader` | Keep | Re-frame topics require reader semantics, reader conditionals, metadata, and static-value checks; LSP does not remove this contract. |
| Janet Tree-sitter parsing and lexical/module resolution | Local | Keep | Outside this decision's scope and required for Janet semantics. |
| Build-tool/classpath discovery | Not currently performed | Delegate to Clojure LSP when enabled | This is the main new value: project specs, source roots, resolved classpath, and dependency context. |
| Maven/Git/local dependency coordinates and transitive dependency graph | Not currently persisted | Delegate, then normalize | Avoid reimplementing dependency resolution; preserve coordinates and a stable dependency fingerprint. |
| External namespace, Java class/member, documentation, and library symbols | Limited external references only | Delegate as bounded enrichment | These can improve navigation and retrieval, but must not become project-owned exact graph facts by default. |
| Call hierarchy, implementation lists, and editor navigation projections | Partial local graph support | Delegate for external/dependency scope; retain local graph queries | Project traversal must remain Datalevin-backed and bounded; LSP can fill gaps outside the project. |
| Diagnostics and built-in project linters | Only source-integrity diagnostics are canonical | Optional enrichment | Useful for reporting, but diagnostics are not graph edges and should not block a valid source snapshot unless explicitly configured. |
| Semantic tokens, formatting, refactoring, code actions, completion | Not part of product graph | Do not integrate initially | These are editor features, not required retrieval or graph facts. |
| REPL/dynamic runtime values from Calva | Not present | Keep out of canonical analysis | Runtime state is environment-specific, non-deterministic, and unsuitable for reproducible graph snapshots. |
| Canonicalization, duplicate/conflict detection, ownership and foreign-key audit | Local | Keep | Clojure LSP output is an observation source, not the persistence contract. |
| Full/incremental convergence and fail-closed replacement | Local | Keep | External enrichment must not weaken graph availability, recovery, or semantic freshness invariants. |
| Semantic document construction and LateOn reconciliation | Local | Keep | External dependency documents would change retrieval scope and privacy behavior; project symbols remain the default corpus. |

### What can eventually be discarded

Only a later, explicitly approved provider-substitution phase may discard:

- the direct embedded clj-kondo invocation;
- the clj-kondo output normalization that is exactly duplicated by a stable
  Clojure LSP export; and
- any local implementation of build/classpath/dependency resolution added
  solely to compensate for the absence of Clojure LSP.

The following must not be discarded even after substitution:

- source inventory, hashes, and UTF-8 range normalization;
- project-owned canonical IDs and exact-edge validation;
- topic extraction and static/dynamic classification;
- canonicalization, graph persistence, semantic freshness, and convergence
  checks; and
- the Janet analyzer.

## Integration contract

### Provider boundary

Introduce a provider boundary conceptually equivalent to:

```clojure
{:provider :clojure-lsp
 :version "pinned-version"
 :project-root "/opaque-or-redacted-root"
 :source-fingerprint "sha256:..."
 :classpath-fingerprint "sha256:..."
 :config-fingerprint "sha256:..."
 :status :complete
 :project-symbols []
 :external-symbols []
 :dependencies []
 :diagnostics []}
```

The exact EDN shape is deliberately provisional until the spike records the
actual Clojure LSP API/dump output. The normalized model must distinguish:

- project-owned symbols versus external symbols;
- source namespaces versus dependency coordinates;
- exact project relationships versus external or ambiguous observations;
- current data versus stale data from an earlier classpath/configuration; and
- provider failure versus a valid empty result.

### Identity and ownership

Project symbols must continue to use the existing canonical identity policy.
External symbols and dependency records require a separate identity namespace
that includes provider/version and dependency coordinate or external URI. They
must not claim ownership of a project file or silently collide with a
project-owned symbol.

An LSP observation may upgrade an existing external reference only when:

1. its source range belongs to the same source content hash;
2. its qualified target maps to exactly one canonical project identity or one
   stable external identity;
3. the provider reports enough provenance to reproduce the mapping; and
4. the canonical audit accepts the resulting entity/edge.

Otherwise it remains a diagnostic or external reference.

### Scope and indexing

The default semantic corpus remains project-owned top-level symbols. External
dependency symbols are not sent to LateOn by default. An explicit future mode
may index a bounded external corpus, but it must define:

- dependency allowlists and maximum symbol/document counts;
- license and source-provenance handling;
- cache invalidation on dependency/version changes; and
- query behavior that clearly labels external results.

### Failure and fallback

The following must leave the base graph usable and must preserve the previous
last-good enrichment state unless a replacement is complete:

- executable missing or wrong version;
- classpath command failure;
- Clojure LSP timeout or process crash;
- malformed or unsupported dump/API output;
- dependency source unavailable; and
- classpath/configuration fingerprint mismatch.

Enrichment failure is a visible diagnostic and status field, not an excuse to
replace valid project-owned facts with partial output.

## Configuration proposal

The first public configuration should be opt-in and explicit:

```clojure
{:analysis
 {:clojure-lsp
  {:enabled? false
   :executable nil
   :version "pinned-version"
   :mode :dump
   :classpath? true
   :external-symbols? false
   :timeout-ms 120000
   :max-external-symbols 10000}}}
```

The implementation must validate the configured executable, version, timeout,
and bounds. `enabled? false` remains the default until the integration has
passed public-corpus qualification. A future packaged distribution may ship a
pinned Clojure LSP runtime, but that is a packaging decision and not assumed by
this ARD.

## Implementation plan

The work is intentionally staged so that every step is independently
reviewable and the current release remains unaffected.

### Phase 0: Contract spike and inventory

1. Select a pinned Clojure LSP release and record its bundled clj-kondo
   version, Java requirements, platform artifacts, and license.
2. Run the CLI/API against the three public checkouts already used by the
   evaluation suite: clojure-lsp, re-frame, and Metabase.
3. Capture only ignored local output: dump/API keys, project/source paths,
   dependency graph shape, external symbols, diagnostics, runtime, wall time,
   RSS, and classpath command behavior.
4. Run the existing embedded clj-kondo analyzer over the same checkouts and
   compare definitions, usages, ranges, platforms, aliases, protocols, Java
   records, and diagnostics.
5. Decide whether the public dump/API output is sufficient or whether a small
   pinned Clojure LSP library adapter is required.

Deliverable: a checked-in schema note or test fixture containing no source
code, private paths, dependency cache contents, or generated graph data.

### Phase 1: Read-only provider adapter

1. Add `src/llm_context/analysis/clojure_lsp.clj` with process/API lifecycle,
   timeout, version validation, and normalized output.
2. Add tests for successful output, missing executable, timeout, malformed
   output, path normalization, version mismatch, and deterministic sorting.
3. Keep the provider read-only: it may inspect a checkout and its classpath,
   but it must not mutate the Datalevin graph or project source.
4. Add a CLI or developer command that prints aggregate provider status while
   redacting project roots, user paths, and source content.

Deliverable: repeatable local provider snapshots and focused tests with the
base analyzer untouched.

### Phase 2: Enrichment model and fingerprints

1. Define normalized dependency, classpath, external-symbol, and diagnostic
   records with provider/version/config/classpath provenance.
2. Decide whether dependency metadata belongs in graph format 3 metadata,
   ignored sidecar state, or a new graph entity type. Do not add persistent
   entities without ownership, identity, and export rules.
3. Add content, project-configuration, classpath, and provider fingerprints.
4. Add bounded counts and redacted status to `doctor`/analysis progress.

Deliverable: an enrichment snapshot that can be compared and invalidated
without changing project graph facts.

### Phase 3: Canonical join for project facts

1. Join LSP observations to existing canonical project symbols by qualified
   name, platform, file, and source range only when the match is unique.
2. Use LSP data to enrich unresolved/external observations or provide external
   navigation metadata; never replace an exact clj-kondo project edge merely
   because LSP produced a different candidate.
3. Keep external symbols and dependency records out of ordinary semantic
   documents and graph traversal.
4. Add full-versus-incremental tests where a dependency/configuration change
   invalidates enrichment but not unrelated project identities.

Deliverable: opt-in enrichment with byte-for-byte project graph convergence
when compared with enrichment disabled.

### Phase 4: Optional retrieval/navigation use

1. Add explicit query options for external/dependency results rather than
   silently mixing them into ordinary project search.
2. Add bounded external result hydration and clear provenance labels.
3. Measure whether external metadata improves public evaluation recall, MRR,
   context recall, or query latency.
4. Keep project-only retrieval as the default and preserve FTS-only behavior
   when Clojure LSP is unavailable.

Deliverable: a measurable product benefit, not merely a larger graph.

### Phase 5: Provider-substitution decision

Only after Phases 0–4 pass may the project consider replacing direct clj-kondo
orchestration with Clojure LSP output. That decision requires:

- complete project-source field parity;
- identical full/incremental canonical graph exports;
- identical malformed-source and fail-closed behavior;
- equivalent CLJ/CLJS/CLJC platform and protocol identity handling;
- no unacceptable startup, memory, or classpath side effects; and
- a stable non-experimental output contract or a pinned adapter test suite.

If any condition fails, retain clj-kondo as canonical and keep Clojure LSP as
optional enrichment.

## Validation and acceptance

### Correctness

- Enrichment disabled produces the current graph and semantic documents
  byte-for-byte.
- Enrichment enabled does not change project-owned exact-edge identities or
  ranges unless an explicit, separately approved migration changes the graph
  contract.
- Full and incremental analysis converge with the same source and dependency
  fingerprints.
- External, ambiguous, dynamic, and unresolved observations remain
  non-traversable.
- Clojure, ClojureScript, and CLJC platform identities remain distinct.
- Reader conditionals, metadata, tagged literals, syntax quote, and malformed
  forms retain current topic and diagnostic behavior.

### Determinism and operations

- Repeated provider runs with identical checkout, config, classpath, and
  provider version produce identical normalized hashes and sorted records.
- Provider failures preserve the last valid project graph and do not create
  dirty semantic documents solely because enrichment is unavailable.
- Timeouts and subprocesses are bounded, observable, and cleaned up.
- No LSP cache, dependency source, raw dump, private path, or source snippet
  enters the repository or public evaluation artifacts.
- The service remains responsive while provider I/O occurs; no graph lock is
  held during subprocess execution or classpath scanning.

### Performance and memory

Record cold and warm wall time, peak RSS, output cardinality, classpath size,
and incremental invalidation cost for clojure-lsp, re-frame, and Metabase.
These measurements are initially descriptive. Integration is not accepted if
it causes unbounded external-symbol growth, repeated whole-classpath work on
ordinary source edits, or memory pressure that threatens the existing
semantic worker.

### Public evaluation

Run all public queries in FTS-only, LateOn-only, and hybrid modes with
enrichment off and on. Report project-only and external-enabled results
separately. A retrieval improvement must be accompanied by unchanged graph
correctness, deterministic repeats, and acceptable latency/memory behavior.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Clojure LSP dump is experimental or changes shape | Pin the version, normalize behind an adapter, and keep the provider optional. |
| Clojure LSP and embedded clj-kondo disagree | Keep clj-kondo canonical in phase 1; compare snapshots and fail closed on conflicting project identities. |
| Classpath discovery executes unexpected project tooling | Make it opt-in, use an explicit executable/timeout policy, log the command class without source output, and document the trust boundary. |
| External symbols overwhelm graph and LateOn | Store them separately, bound counts, and keep them non-indexable by default. |
| Dependency paths or source leak into exports | Redact paths, keep raw output ignored, and add staged-diff/privacy tests. |
| External facts become stale after dependency changes | Fingerprint provider, config, classpath, and dependency coordinates; invalidate conservatively. |
| LSP runtime increases packaged size and memory | Start as an external optional provider; make packaging a separate decision. |
| Runtime REPL data is mistaken for static truth | Keep Calva/nREPL observations out of the canonical graph. |
| The integration duplicates existing local graph work without user value | Require public-evaluation or navigation improvements before enabling external retrieval. |

## Alternatives considered

### Keep the current analyzer only

This remains the default and release-safe baseline. It provides strong
project-owned semantics, but no resolved build/classpath dependency context.

### Add a Clojure Tree-sitter grammar

Rejected as the first enrichment path. It would provide syntax structure but
not classpath resolution, external symbols, dependency coordinates, or the
project-aware semantic context that motivated this work.

### Read Clojure LSP's private cache

Rejected. The cache is an implementation detail with no stable interchange
contract and may contain paths, external source state, or version-specific
records.

### Make Clojure LSP mandatory

Rejected for the first integration. It would turn an optional enrichment into
a packaging, classpath, platform, and reproducibility requirement before its
benefit is measured.

### Use the full LSP JSON-RPC server for every analysis

Rejected initially. A batch API/dump adapter is easier to pin, bound, test,
and run outside an editor. A long-lived LSP server may be revisited if editor
buffer or incremental unsaved-document support becomes a product requirement.

## Release and migration consequences

This ARD does not block `0.11.0`. The current release should finish Metabase
qualification, public evaluation, and release validation using the existing
canonical analyzer.

Phase 1–2 should not change graph format or semantic document version. If a
future phase persists new dependency or external-symbol entities, it must
define ownership/export/index behavior and likely require a graph-format and
semantic-document compatibility decision before release.

The integration is therefore a post-release optimization and capability track,
not a reason to delay the current release candidate.

## Open questions for Phase 0

1. Is the public `dump`/API output complete enough for the required external
   symbols and dependency graph, or is a Clojure LSP library adapter needed?
2. Which dependency types should be represented: coordinates only, external
   namespaces, Java classes/members, documentation, or all of them?
3. Should dependency metadata live in Datalevin, an ignored sidecar, or both?
4. What maximum external symbol/document count improves retrieval without
   creating a second large indexing job?
5. Which classpath commands are acceptable for an opt-in local run, and how
   should Windows/macOS/Linux differences be reported?
6. Does any useful enrichment require a separate Clojure LSP version from the
   bundled clj-kondo version, and how will that version pair be fingerprinted?

