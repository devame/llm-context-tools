# ARD: Clojure LSP enrichment and integration plan

- Date: 2026-08-06
- Status: Unusable — rejected after architecture review; do not implement
- Review date: 2026-08-09
- Reviewer: 5.6 Sol (Medium)
- Target: Post-0.11.0 qualification; candidate for 0.11.x or 0.12.0
- Decision owners: llm-context maintainers
- Scope: Clojure, ClojureScript, CLJC, project dependency metadata, external
  library symbols, and the canonical graph/analyzer boundary

## Context

> Review: The context correctly separates source relationships from build dependencies but later loses that distinction.
> Review: It describes Clojure LSP as exposing generic “dependency data” without verifying the public output contract.
> Review: The current API exports classpath paths and a namespace-use graph, not resolved dependency coordinates.

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

> Review: The problem is framed as “richer context” rather than a concrete user question or missing operation.
> Review: No required external fact, expected answer, or measurable success criterion is identified before choosing a provider.
> Review: This solution-first framing allows speculative provider capabilities to become assumed requirements.

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

> Review: The decision commits to Clojure LSP before Phase 0 proves that its public API supplies the required facts.
> Review: It assumes dependency coordinates, stable external identities, and retrieval-ready records that `dump` does not expose.
> Review: This should have been a go/no-go spike decision, not authorization for a multi-phase integration.

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

> Review: The table mixes verified Clojure LSP capabilities with hoped-for dependency and documentation capabilities.
> Review: Its coordinate and transitive-graph delegation is unsupported, while several project analyses duplicate embedded clj-kondo.
> Review: Disposition should be decided from one user-facing use case and observed provider output, not feature names.

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

> Review: Provider substitution is unrelated to proving useful external enrichment and creates a second strategic project.
> Review: Replacing embedded clj-kondo with Clojure LSP would usually add process, cache, and classpath cost around the same analyzer.
> Review: This work has no stated maintenance or product payoff and requires a separate future decision if ever justified.

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

> Review: The contract is designed before the provider schema and the consuming query contract are known.
> Review: It combines project observations, external symbols, build dependencies, diagnostics, and lifecycle state prematurely.
> Review: A minimal external-navigation result should define the required contract before persistence concerns are introduced.

### Provider boundary

> Review: The provisional model contains `:dependencies` even though Clojure LSP does not export resolved dependency records.
> Review: Its source fingerprint duplicates existing source inventory, graph revision, and semantic hash mechanisms.
> Review: The boundary is too broad; an initial spike should return only filtered observed facts and bounded operational metrics.

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

> Review: Including provider/version in external identity makes every provider upgrade churn otherwise unchanged symbols.
> Review: Identity should describe the dependency artifact and qualified symbol; provider/version belongs in provenance.
> Review: Stable artifact identity cannot be established from classpath paths alone, so the proposed join is not yet implementable.

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

> Review: The section designs future indexing before any external-symbol question set demonstrates retrieval value.
> Review: Keeping observations non-indexable while promising retrieval postpones the only user-visible benefit until late in the plan.
> Review: External indexing deserves a separate decision after bounded navigation proves useful and licensing inputs are available.

The default semantic corpus remains project-owned top-level symbols. External
dependency symbols are not sent to LateOn by default. An explicit future mode
may index a bounded external corpus, but it must define:

- dependency allowlists and maximum symbol/document counts;
- license and source-provenance handling;
- cache invalidation on dependency/version changes; and
- query behavior that clearly labels external results.

### Failure and fallback

> Review: Preserving last-good bytes is sound, but the section does not prohibit normal queries from serving stale enrichment.
> Review: Fingerprint mismatch must immediately make the old epoch non-queryable even when retained for recovery.
> Review: Optional enrichment does not need to inherit the canonical graph's full replacement semantics or dirty-document behavior.

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

> Review: Project-controlled opt-in configuration is not a sufficient trust boundary for commands read from an untrusted checkout.
> Review: The settings omit cache/log relocation, environment policy, output-byte limits, process-tree cleanup, and side-effect controls.
> Review: `max-external-symbols` is applied after potentially unbounded classpath scanning and dump materialization.

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

> Review: The plan builds lifecycle and persistence infrastructure before delivering or testing a user-visible operation.
> Review: Its phases depend on unsupported coordinate data and repeatedly process project facts already supplied by clj-kondo.
> Review: Replace it with one gated vertical experiment whose failure explicitly ends the integration effort.

The work is intentionally staged so that every step is independently
reviewable and the current release remains unaffected.

### Phase 0: Contract spike and inventory

> Review: A contract spike is appropriate, but the surrounding ARD has already committed to outcomes the spike may disprove.
> Review: Full project-field parity is unnecessary unless provider substitution is separately justified.
> Review: The spike needs explicit go/no-go gates, external-specific questions, side-effect auditing, and output-size measurements.

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

> Review: “Read-only” ignores `.lsp` caches, global caches, copied configs, Java decompilation, and executed build commands.
> Review: Implementing a production adapter before the spike proves useful output risks creating a tested wrapper with no product value.
> Review: Any experiment must force state below `.llm-context`, sanitize execution, and hard-bound output before this phase exists.

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

> Review: This phase models dependency coordinates the chosen provider does not return.
> Review: It duplicates existing content, semantic, configuration, and graph-revision fingerprint mechanisms.
> Review: A single atomically replaced ignored snapshot is sufficient until a concrete query proves a persistence requirement.

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

> Review: Joining Clojure LSP project facts back to embedded clj-kondo entities largely compares the analyzer with itself.
> Review: Project-symbol parity adds cost without advancing the external-navigation use case.
> Review: Only unresolved project references needed by an explicit external query should be considered for an initial join.

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

> Review: This is the first phase with possible user value, so placing it after three infrastructure phases reverses the proper order.
> Review: The existing public corpus cannot score external answers because all judgments resolve to project-owned symbols.
> Review: An external-specific corpus and explicit query behavior must be created before building the earlier phases.

1. Add explicit query options for external/dependency results rather than
   silently mixing them into ordinary project search.
2. Add bounded external result hydration and clear provenance labels.
3. Measure whether external metadata improves public evaluation recall, MRR,
   context recall, or query latency.
4. Keep project-only retrieval as the default and preserve FTS-only behavior
   when Clojure LSP is unavailable.

Deliverable: a measurable product benefit, not merely a larger graph.

### Phase 5: Provider-substitution decision

> Review: Provider substitution does not follow from successful enrichment and should not share its scope or acceptance path.
> Review: Clojure LSP still embeds clj-kondo, so substitution may increase operational complexity without reducing semantic duplication.
> Review: Remove this phase; revisit it only under a separate ARD with a quantified maintenance or product benefit.

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

> Review: The acceptance suite heavily protects existing behavior but does not first define the new behavior worth accepting.
> Review: Several criteria validate hypothetical persistence and indexing paths rather than a minimal external-navigation outcome.
> Review: Acceptance should begin with answer coverage, precision, latency, memory, side effects, and explicit user value.

### Correctness

> Review: Byte-for-byte project graph stability is appropriate, but most other criteria merely restate existing analyzer invariants.
> Review: Full/incremental enrichment convergence is premature when Clojure LSP performs its own whole-project cache lifecycle.
> Review: Missing are external-answer correctness, dependency provenance accuracy, and explicit stale-result rejection tests.

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

> Review: Determinism is undefined across machines because classpath output contains machine-specific absolute paths and cache state.
> Review: Timeout alone does not bound stdout, parser allocation, cache growth, descendants, or peak subprocess memory.
> Review: Operational acceptance must include forced cache locations, process-tree termination, byte limits, and side-effect inventories.

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

> Review: Descriptive measurements without initial budgets permit expensive infrastructure to proceed despite weak benefit.
> Review: Symbol-count bounds do not constrain the full dependency scan or the dump before normalization.
> Review: The spike needs stop thresholds for wall time, RSS, output bytes, cache bytes, and repeated source-edit cost.

Record cold and warm wall time, peak RSS, output cardinality, classpath size,
and incremental invalidation cost for clojure-lsp, re-frame, and Metabase.
These measurements are initially descriptive. Integration is not accepted if
it causes unbounded external-symbol growth, repeated whole-classpath work on
ordinary source edits, or memory pressure that threatens the existing
semantic worker.

### Public evaluation

> Review: The existing judgments exclusively target canonical project symbols and cannot establish external retrieval recall.
> Review: External candidates are unjudged and may lower ranking metrics even when they are useful answers.
> Review: Build a separate dependency-oriented corpus before claiming that these evaluation modes validate enrichment.

Run all public queries in FTS-only, LateOn-only, and hybrid modes with
enrichment off and on. Report project-only and external-enabled results
separately. A retrieval improvement must be accompanied by unchanged graph
correctness, deterministic repeats, and acceptable latency/memory behavior.

## Risks and mitigations

> Review: The table notices duplication and command execution but mitigates them only after committing to the integration.
> Review: It omits the primary risk that the provider does not expose coordinates or a build dependency graph at all.
> Review: It also understates repository/global writes, untrusted configuration, unbounded output, and stale-result serving.

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

> Review: The alternatives compare Clojure LSP mostly with unrelated parser/editor choices rather than simpler enrichment paths.
> Review: They omit explicit classpath discovery followed by the already embedded clj-kondo analyzer.
> Review: They also omit a no-persistence external-navigation spike and build-tool-native structured dependency exports.

### Keep the current analyzer only

> Review: This baseline is dismissed without testing whether explicit classpath input could extend the existing analyzer directly.
> Review: It should remain one arm of the provider experiment rather than merely the no-feature alternative.
> Review: The relevant comparison is total external-answer value per operational cost, not feature-list breadth.

This remains the default and release-safe baseline. It provides strong
project-owned semantics, but no resolved build/classpath dependency context.

### Add a Clojure Tree-sitter grammar

> Review: Rejecting Tree-sitter is reasonable but does not help choose between plausible dependency-enrichment mechanisms.
> Review: It is a weak comparison because syntax parsing cannot address the stated classpath problem.
> Review: Keeping it here makes the alternatives appear broader while omitting the strongest simpler candidate.

Rejected as the first enrichment path. It would provide syntax structure but
not classpath resolution, external symbols, dependency coordinates, or the
project-aware semantic context that motivated this work.

### Read Clojure LSP's private cache

> Review: Rejecting a private cache is correct, but the public dump still reflects cache behavior and unstable internal shapes.
> Review: This alternative does not address whether the public output contains the required facts.
> Review: Public API use is necessary but insufficient for a stable, useful enrichment contract.

Rejected. The cache is an implementation detail with no stable interchange
contract and may contain paths, external source state, or version-specific
records.

### Make Clojure LSP mandatory

> Review: Rejecting mandatory installation is correct but says nothing about whether optional integration creates enough value.
> Review: Optionality reduces blast radius; it does not cure an unsupported provider contract or unsafe execution model.
> Review: The decision must first pass a value and trust gate, regardless of whether installation is optional.

Rejected for the first integration. It would turn an optional enrichment into
a packaging, classpath, platform, and reproducibility requirement before its
benefit is measured.

### Use the full LSP JSON-RPC server for every analysis

> Review: Rejecting a long-lived server is reasonable for reproducible batch analysis.
> Review: The real alternative is a minimal filtered batch export versus direct embedded clj-kondo with explicit classpath input.
> Review: Server lifecycle choices should follow a proven unsaved-buffer use case and are not material to this ARD.

Rejected initially. A batch API/dump adapter is easier to pin, bound, test,
and run outside an editor. A long-lived LSP server may be revisited if editor
buffer or incremental unsaved-document support becomes a product requirement.

## Release and migration consequences

> Review: The non-blocking release posture is sound, but the section still describes the integration as an optimization track.
> Review: No measured baseline shows that it optimizes anything or supplies a missing product capability.
> Review: The only valid consequence is to stop implementation pending a replacement ARD grounded in spike evidence.

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

> Review: These are foundational decision inputs, not questions that can remain open after selecting the architecture.
> Review: Questions about data types, storage, bounds, commands, and version pairing determine whether the proposal is viable at all.
> Review: Their presence confirms that this document should have authorized only a bounded investigation, not implementation.

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
