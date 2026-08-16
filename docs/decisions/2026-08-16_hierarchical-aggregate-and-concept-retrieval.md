# Hierarchical Aggregate and Concept Retrieval ARD

- Status: Accepted for phased implementation
- Date: 2026-08-16
- Scope: Repository-neutral indexing, retrieval, evidence planning, and answer
  sufficiency
- Compatibility boundary: graph format 4 and separately versioned derived
  semantic documents

## Implementation status

The first repository-neutral slice is implemented with graph format 4:

- safe Clojure-family literal aggregate and membership extraction;
- explicit complete versus partial evidence without project evaluation;
- atomic persistence, replacement, deletion, and incremental convergence;
- aggregate-enriched semantic documents and an independent aggregate lexical
  retrieval channel using the unchanged question;
- indexable namespace/module container documents backed by exact `contains`
  relationships;
- aggregate-aware learned candidate documents;
- evidence sufficiency metadata and a pre-answer abstention gate in the demo;
- removal of repository names and hand-authored concept phrase expansions from
  production query planning.

Learned repository-scoped aliases and generated subsystem summaries remain
behind the evaluation gate described below. They are not being simulated with
deterministic synonym rules: that would reintroduce the overfitting this
decision rejects.

## Decision summary

llm-context will supplement its exact symbol graph with deterministic aggregate
facts and provenance-bearing concept documents. Natural-language retrieval will
search symbols and higher-order evidence independently, then expand selected
concepts and aggregates back to exact source facts. Similarity may discover
evidence, but only canonical or deterministically derived facts may qualify an
answer. Questions requiring an inventory, ownership claim, or complete process
must clarify or abstain when the packet lacks the corresponding evidence.

This architecture is repository-neutral. No production extractor, concept,
alias, source path, symbol name, or ranking rule may mention Metabase or any
other qualification repository. Metabase remains one regression fixture among
at least three repositories with different languages or layouts.

## Context and problem

Graph format 3 provides a trustworthy project snapshot of files, symbols,
exact relationships, classified unresolved references, topics, and effects.
LateOn documents are derived from individual indexable symbols and include
names, kind, signature, documentation, source path, exact source body, and
selected outgoing relationships.

That representation is strong for exact lookup and local implementation
questions. It is incomplete for questions whose answer lives above one symbol:

- complete registries and supported-implementation lists;
- file, namespace, package, or subsystem responsibilities;
- repository-scoped terminology and aliases;
- capability declarations and authority relationships;
- workflows assembled from multiple entry points;
- ambiguous phrases with several valid meanings in the same repository.

An embedding can rank textual similarity. It cannot establish that a variable
is authoritative, that a collection is complete, or that one repository term
represents another. A generated summary can improve discovery, but cannot make
an unsupported relationship true.

## Goals

1. Represent statically provable aggregates and memberships with exact source
   provenance and explicit completeness.
2. Add repository-scoped concept and container documents without weakening the
   canonical symbol graph.
3. Retrieve at multiple granularities while preserving the unchanged question.
4. Rank answer-bearing authority separately from topical similarity.
5. Require evidence appropriate to lookup, set, flow, ownership, and capability
   questions.
6. Clarify or abstain instead of converting relevance-only packets into
   confident answers.
7. Preserve full/incremental convergence, generation freshness, bounded query
   latency, and inspectable provenance.

## Non-goals

- Hard-code repository names, paths, frameworks, or vocabulary.
- Treat global synonyms as exact graph relationships.
- Execute project code to discover runtime registries.
- Replace exact graph traversal with model exploration.
- Treat generated prose as answer evidence.
- Require a learned model for canonical indexing or command availability.
- Rewrite the user's question into a more favorable hidden query.

## First principles

The pipeline has five independent responsibilities:

1. **Representation** records what evidence exists and at what granularity.
2. **Candidate generation** maximizes recall across independent evidence types.
3. **Semantic ranking** estimates which candidates answer the unchanged query.
4. **Structural qualification** establishes authority, membership,
   completeness, ownership, and exact relationships.
5. **Evidence planning** selects a bounded packet or refuses an unsupported
   answer.

No one stage may silently substitute for another. In particular:

- a semantic score is not structural proof;
- structural rules are not general language understanding;
- query routing cannot recover facts absent from the index;
- answer generation cannot repair omitted evidence.

## Target architecture

```text
                         unchanged question
                                |
            +-------------------+-------------------+
            |                   |                   |
            v                   v                   v
      symbol retrieval   aggregate retrieval   concept retrieval
            |                   |                   |
            +-------------------+-------------------+
                                |
                                v
                    bounded learned reranking
                                |
                                v
                 provenance-bearing graph expansion
                                |
                                v
                   authority + completeness checks
                          |                 |
                     sufficient        insufficient
                          |                 |
                          v                 v
                        packet       clarify or abstain
```

The query is passed unchanged to each retrieval channel. An advisory query
frame may describe expected evidence, but does not replace the query text.

## Evidence layers

### Canonical layer

The existing file, symbol, exact edge, reference, topic, and effect entities
remain authoritative. Existing identities and traversal semantics are
preserved.

### Deterministic aggregate layer

Graph format 4 adds aggregate and membership entities produced without
executing project code:

```clojure
{:entity/type :entity.type/aggregate
 :aggregate/id "aggregate:..."
 :aggregate/name "provider-registry"
 :aggregate/kind :aggregate.kind/literal-map
 :aggregate/owner "symbol:..."
 :aggregate/file "file:..."
 :aggregate/completeness :complete-static
 :aggregate/member-count 4
 :aggregate/member-kind :literal-or-symbol
 :aggregate/analyzer :clojure-literal-aggregate
 :source/start-byte 120
 :source/end-byte 260}

{:entity/type :entity.type/membership
 :membership/id "membership:..."
 :membership/aggregate "aggregate:..."
 :membership/value "postgres"
 :membership/value-kind :string
 :membership/ordinal 0
 :membership/evidence :literal
 :source/start-byte 180
 :source/end-byte 190}
```

Completeness is one of:

- `:complete-static`: every member is a literal in the source range;
- `:complete-resolved`: every member is a resolved canonical symbol;
- `:partial-static`: static members exist but at least one expression is
  unresolved;
- `:dynamic`: runtime construction is visible;
- `:unknown`: the producer cannot establish completeness.

Only complete aggregates can qualify claims such as "all", "which are
supported", or "list every". Partial aggregates remain useful discovery
evidence.

Initial producers cover literal top-level collections that are safe to parse.
Language and framework providers use one common output contract. Unsupported
or dynamic constructs produce diagnostics rather than guessed memberships.

### Derived concept and container layer

Concept, file, namespace, and subsystem documents improve discovery. They are
not canonical code identities and do not create exact execution edges.

Each derived document records:

- stable derived identity and kind;
- repository scope;
- producer name and version;
- graph revision and content hash;
- contributing canonical symbols or aggregates;
- exact facts versus retrieval-only associations;
- generated versus deterministic text.

Repository-scoped aliases may connect terms such as a product's provider,
adapter, connector, or driver vocabulary. Such associations guide retrieval
only. They do not qualify an answer unless exact source or manifest evidence
supports the resulting claim.

## Semantic document strategy

The current symbol document contract remains independently versioned.
Deterministic aggregate documents include:

- aggregate kind and completeness;
- owner name, qualified name, documentation, and source role;
- member type, count, and bounded members;
- exact source path and provenance;
- explicit wording that the unit is a collection or registry.

Container summaries are assembled from public symbols, documentation, owned
aggregates, and exact incoming/outgoing relationships. Model-generated prose is
deferred until deterministic summaries are evaluated. If introduced later, it
is retrieval-only, hash-addressed, and rejected when it mentions identifiers
outside its contributing facts.

Logical retrieval channels remain independently bounded even if one physical
vector service hosts them. This prevents numerous summaries from crowding out
exact symbols and permits channel-level latency and quality measurements.

## Hierarchical retrieval

1. Search lexical symbols, semantic symbols, aggregates, and concepts in
   parallel with independent top-k limits.
2. Preserve exact identifier priority and source-role policy.
3. Fuse candidates by evidence class rather than one undifferentiated list.
4. Rerank a bounded, hydrated candidate set using the unchanged question.
5. Expand concept anchors to contributing aggregates and symbols.
6. Expand aggregates to memberships and their exact owner source.
7. Traverse only exact canonical graph relationships for execution evidence.
8. Fit the resulting facts into one bounded packet.

A reranker receives candidate type, completeness, member count, definition
versus mention, source role, documentation, path, and relationship summaries.
It may reorder candidates but cannot create qualification.

## Query frame and evidence requirements

The advisory query frame evolves from only lookup/set/flow to:

```clojure
{:answer-shape :set
 :target-concepts ["database" "data source"]
 :qualifiers ["supported"]
 :scope :repository
 :completeness-required? true
 :authority-required? true
 :confidence 0.72}
```

The frame is model-owned, confidence-bearing, and never filters the initial
candidate pool. Fixed phrase tables are not authoritative. Caller overrides
remain explicit and authoritative.

Evidence requirements are generic:

| Request | Required evidence |
| --- | --- |
| One implementation | Exact definition |
| Complete inventory | Complete aggregate and memberships |
| Process | Entry points and exact relationships |
| Ownership | Exact container or ownership facts |
| Capability support | Capability declaration or authoritative registry |
| Tests | Production symbol plus test evidence |

When required evidence is absent, the packet returns a structured insufficiency
reason. Applications may ask a clarification or show an abstention; they must
not ask an answer model to invent the missing structure.

## Freshness and incremental convergence

Aggregate and membership facts participate in canonical snapshot hashing and
file ownership. Full and incremental analysis of the same source must export
identical graph-format-4 facts.

Derived semantic work is invalidated by stable dependency hashes:

- a symbol document changes only with that symbol's facts;
- an aggregate changes only with its owner or memberships;
- a container summary changes only with its public child fact hashes;
- a subsystem summary changes only with child-summary hashes.

Old graph or derived-document generations cannot contribute candidates.
Interrupted indexing remains recoverable through the existing durable dirty,
lease, indexed, and watermark state machine.

## Evaluation

The frozen corpus includes exact lookup, conceptual lookup, complete
inventories, ownership, workflows, ambiguity, hard lexical negatives, dynamic
registries, and unanswerable questions. Gold judgments identify expected
concepts, aggregates, symbols, memberships, acceptable clarifications, and
whether completeness is required.

Report:

- concept and aggregate Recall@5;
- symbol Recall@10/50, MRR, and nDCG@10;
- selected-root hit rate;
- membership precision/recall and complete-inventory accuracy;
- clarification accuracy and unsupported-answer rate;
- warm/cold p50/p95 latency and fallback causes;
- index growth and incremental invalidation fan-out.

Activation requires at least 30% relative improvement in MRR or selected-root
hit rate on conceptual/inventory queries, no exact-lookup regression greater
than two percentage points, and no increase in unsupported-answer rate. The
five-second demo deadline remains a service-level gate, not a relevance fix.

## Repository-neutral qualification

Production behavior is rejected if it contains qualification-repository names,
paths, symbols, or hand-authored aliases. Tests must demonstrate the same
aggregate and evidence contracts with synthetic neutral fixtures and at least
three real repositories. Every feature is ablated against symbol-only
retrieval.

Metabase's supported-database question is a regression case only. A passing
result either selects a complete source-backed aggregate for the intended
meaning or asks which meaning the user intended. It may not define the generic
extractor, ontology, or ranker.

## Delivery slices

1. Freeze multi-granularity evaluation and insufficiency regressions.
2. Add the answer-sufficiency gate without changing candidate order.
3. Introduce graph-format-4 aggregate/membership schema and canonical audit.
4. Add generic safe literal-aggregate producers and full/incremental tests.
5. Add deterministic aggregate documents and freshness reconciliation.
6. Add independent aggregate retrieval and evidence-class fusion.
7. Expand aggregate memberships into bounded context packets.
8. Add deterministic namespace/file summaries and concept-provider boundary.
9. Evaluate and promote an aggregate-aware learned reranker only after it
   passes held-out gates.
10. Consider generated summaries or fine-tuning only after representation and
    retrieval ablations identify a remaining gap.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Generated summaries hallucinate architecture | Retrieval-only status and exact contributor validation |
| Dynamic registries appear complete | Explicit completeness states and fail-closed qualification |
| Summary documents crowd out symbols | Independent channels and per-class quotas |
| Global aliases create false relationships | Repository scope and non-authoritative associations |
| Incremental changes rebuild the repository | Dependency hashes and bounded ancestor invalidation |
| Model relevance is mistaken for authority | Deterministic qualification after reranking |
| One demo drives production logic | Synthetic and cross-repository qualification gates |

## Rejected alternatives

- Query rewriting as the primary fix: it can hide missing representation and
  makes evaluation less honest.
- A larger answer model: it cannot recover omitted evidence and may produce a
  more persuasive unsupported answer.
- A global driver/database synonym list: terminology is repository-scoped and
  polysemous.
- Only enabling the current reranker: its live qualification moved an important
  aggregate in the wrong direction.
- Persisting generated concept edges as canonical truth: provenance and exact
  semantics would be lost.
- Replacing symbol documents with summaries: exact lookup and source fidelity
  would regress.

## Definition of done

- Aggregate and membership facts are deterministic, source-provenanced, and
  convergent under full and incremental analysis.
- Original questions are disclosed and sent unchanged to retrieval channels.
- Concept and aggregate candidates can lead back to exact source evidence.
- Complete inventories require complete aggregate evidence.
- Ambiguous or unsupported requests clarify or abstain before answer
  generation.
- Cross-repository benchmarks pass the quality, regression, latency, freshness,
  and index-growth gates.
