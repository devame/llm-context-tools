# Learned candidate reranking with structural verification

- Date: 2026-08-15
- Status: Accepted for implementation
- Scope: natural-language candidate ordering, structural qualification,
  degraded operation, provenance, latency, and evaluation
- Supersedes: the deterministic ordering decision in section 2 of
  `2026-08-15_intent-retrieval-planning-and-latency.md`
- Extends: the provider boundary in section 5 of that decision

## Decision summary

Semantic relevance and structural validity are different decisions and must
not share one hand-written score.

The original question remains unchanged. Lexical and LateOn retrieval generate
a broad candidate pool. The already pinned 32M Mixedbread ColBERT model then
reranks a bounded prefix by scoring the question against evidence-rich
candidate documents. Deterministic code subsequently annotates structural
facts, applies caller and source constraints, selects diverse roots, and checks
whether the resulting evidence is sufficient for the requested answer shape.

The previous deterministic intent score no longer changes candidate order. It
is retained only as inspectable qualification metadata. If learned reranking is
disabled, unavailable, times out, or returns an invalid response, the fused
retrieval order is preserved and the degraded state is reported.

## Context and measured failure

The existing pipeline combines two concerns:

1. query/candidate relevance inferred from literal term overlap and a small
   fixed concept vocabulary;
2. structural evidence such as route-like identifiers, production source role,
   and exact graph relationships.

That combination is brittle. For the question "what are the supported
databases", `metabase.driver.util/official-drivers` reached hybrid rank 13 but
the deterministic intent scorer moved it to rank 77. Terms such as
"supported" also reward `unsupported-builtin-functions`, and a repository
concept outside the fixed vocabulary receives no useful semantic treatment.

The failure is not specific to Metabase. Literal overlap cannot reliably
resolve synonymy, negation, paraphrase, domain terminology, or the distinction
between a function that mentions an entity and an aggregate that answers a
question about that entity.

## First principles

The query pipeline has four independent responsibilities:

1. **Candidate generation** maximizes recall using lexical and semantic
   retrieval. A reranker cannot recover missing evidence.
2. **Semantic reranking** estimates which retrieved candidates best answer the
   unchanged question. This is a learned relevance problem.
3. **Structural verification** establishes exact facts about source role,
   symbol kind, aggregate membership, and graph relationships. This is a
   deterministic evidence problem.
4. **Evidence planning** chooses bounded, diverse roots and refuses to claim an
   answer shape that the packet cannot support.

A model score is not structural proof. Conversely, a structural predicate is
not a general language-understanding mechanism.

## Architecture

```text
unchanged question
       |
       v
lexical retrieval + LateOn retrieval
       |
       v
source-role policy and caller constraints
       |
       v
bounded learned ColBERT reranking
       |
       v
deterministic structural annotation
       |
       v
shape resolution, diverse seed selection, exact traversal
       |
       v
evidence sufficiency gate and answer packet
```

### Candidate generation

Hybrid fusion remains responsible for producing candidates. The learned stage
reranks only the first configured number of candidates and appends the
remaining candidates in their existing order. This bounds inference cost and
preserves recall beyond the reranked prefix.

### Learned scorer

The first provider reuses the resident query-router NextPlaid process and its
pinned `mixedbread-ai/mxbai-edge-colbert-v0-32m` ONNX artifact. NextPlaid's
`/encode` endpoint produces query and document token embeddings. llm-context
computes the model-declared MaxSim score:

```text
score(query, document)
  = average over query tokens(
      maximum dot product with any document token)
```

The scorer never rewrites the question. Query and document inputs use the
model's separate input types so the exported prefixes and masking contract are
respected.

The 32M model is already pinned, checksum-verified, INT8-quantized, and served
by the supervised loopback-only runtime. Reusing it avoids a second model
process and avoids a second repository index. The previously considered
Reason-mxbai artifact remains an experiment until its exact ONNX contract and
effective license are independently qualified; it is not silently substituted
for the shipped artifact.

### Candidate document contract

Each document is generated from repository-neutral evidence, in this order:

- qualified and short symbol names;
- symbol kind and signature;
- documentation;
- source path and classified source role.

Fields are labelled to prevent accidental concatenation ambiguity. Blank
fields are omitted. Candidate IDs remain authoritative; model output can only
reorder IDs already present in the input pool.

Future graph-derived summaries such as aggregate membership may be added only
when they are exact, bounded, provenance-bearing facts. Generated guesses are
not candidate metadata.

### Structural verification

The deterministic stage continues to emit:

- `:relevance-qualified?` and inspectable term evidence for diagnostics;
- `:structurally-qualified?` and exact qualification reasons;
- concept evidence and source-role metadata.

It does not sort. Seed selection consumes candidates in learned order and may
restrict the pool to structurally qualified candidates when such evidence
exists. Exact relationship checks remain authoritative for flow planning.

### Provider and fallback contract

A candidate reranker returns:

```clojure
{:results [...same candidate IDs...]
 :provider :mixedbread-32m
 :status :applied | :disabled | :unavailable | :failed | :timed-out
 :model "..."
 :model-revision "..."
 :candidate-count 50
 :latency-ms 42
 :reordered? true}
```

The adapter validates identity conservation: no missing, duplicate, or unknown
candidate IDs are accepted. Invalid output is a provider failure.

Fallback is fail-open for retrieval availability but fail-visible:

- preserve the fused/source-preferred order exactly;
- continue structural verification and packet construction;
- report provider status and bounded failure detail;
- never invoke the old deterministic relevance ordering.

## Configuration

The learned stage is configured independently from the query-shape router even
though both initially share one model process:

```clojure
:candidate-reranker
{:enabled true
 :mode :shadow
 :candidate-count 50
 :query-timeout-ms 5000
 :document-cache-size 2048}
```

`mode` is `:shadow` or `:enforce`. Shadow mode computes scores and reports the
ordering it would choose while preserving fused order. `candidate-count`
bounds the reranked prefix. `query-timeout-ms` is the total
deadline for query encoding, missing-document encoding, and MaxSim scoring.
`document-cache-size` bounds resident candidate embeddings using access-order
eviction. A zero-sized cache is prohibited; disabling is explicit.

## Concurrency, caching, and latency

Candidate embeddings are immutable for a specific candidate document string.
The cache key includes candidate identity and the complete generated document,
so documentation or signature changes cannot reuse stale vectors. The cache is
process-local, bounded, synchronized, and discarded on service restart.

Query embeddings are not cached initially because natural-language queries
have higher cardinality and are cheap relative to candidate encoding. Identical
request coalescing may be added after measurement.

The entire reranking operation observes one deadline. It must not assign the
full timeout independently to multiple sequential operations. A timeout
preserves original order. Provider latency is reported separately from LateOn
retrieval and graph traversal latency.

## Observability

Search and context provenance report:

- provider and pinned model revision;
- status and failure reason;
- bounded candidate count;
- cache hits and misses;
- latency;
- whether ordering changed;
- per-candidate learned score, pre-rerank rank, and post-rerank rank.

Deterministic intent scores are removed as ordering authority. Structural and
lexical diagnostic reasons remain distinguishable from learned scores.

## Evaluation and acceptance

Evaluation must separate candidate recall, reranking quality, structural
correctness, answer support, and latency.

### Frozen query suite

Add at least 60 repository-neutral natural-language cases across:

- lookup, set, and flow questions;
- paraphrases and unusual wording;
- negation and lexical hard negatives;
- production versus test targets;
- aggregate registries and individual functions;
- under-specified questions;
- candidates absent from the generated pool.

Metabase examples may be included, but no Metabase symbol or path may appear in
production ranking rules.

### Metrics

Record before/after:

- Recall at 10 and 50 for candidate generation;
- MRR and nDCG at 10 for reranking;
- selected-root hit rate;
- structurally unsupported root rate;
- packet evidence hit rate;
- unsupported-answer rate;
- warm and cold p50/p95 reranking latency;
- fallback frequency and cause.

The initial target is at least a 30% relative improvement in MRR or selected
root hit rate on the weak/paraphrase slice, no regression greater than two
percentage points on exact lookup cases, and no increase in unsupported-answer
rate. This is a measured target, not a claim made before running the suite.

### Required regression cases

- A learned score may reorder semantically relevant candidates.
- Structural annotation never changes learned order.
- A high learned score cannot create structural qualification.
- Timeout, unavailable provider, duplicate IDs, and malformed embeddings retain
  fused order and expose degraded provenance.
- A changed candidate document misses the embedding cache.
- The unchanged user question is the only query text encoded.

## Rollout

1. Land the provider protocol, direct encoding adapter, MaxSim scorer, cache,
   configuration, provenance, and deterministic fallback tests.
2. Run the scorer in shadow mode by default because its model/runtime are
   already pinned; preserve an explicit disable switch and require measured
   acceptance before `:enforce` becomes the default.
3. Run focused Metabase diagnostics and the frozen cross-repository suite.
4. Keep the feature enabled only if acceptance thresholds and latency budgets
   pass. Configuration rollback requires no reindexing.
5. Consider model replacement or fine-tuning only after failure slices show
   what the zero-shot model cannot distinguish.

## Consequences

The pipeline gains a model-derived semantic ordering without query rewriting or
repository-specific rules. Structural claims remain inspectable and exact.
Warm repeated queries become cheaper through document caching, while cold
requests perform additional bounded inference. Availability remains robust
because reranker failure cannot erase the original retrieval results.

The change does not fix missing candidate evidence, unindexed documentation,
or absent aggregate relationships. Those remain separate candidate-generation
and canonical-evidence workstreams.

## Implementation qualification amendment

The first live Metabase diagnostic encoded the unchanged question "what are
the supported databases" and the first 50 source-preferred hybrid candidates.
The adapter completed in 1,585 ms, but the zero-shot Mixedbread model moved
`metabase.driver.util/official-drivers` from rank 13 to rank 46. That is the
same important failure slice that motivated this decision, so default learned
authority is rejected for now. The implementation therefore ships in shadow
mode. This result does not invalidate the provider boundary; it demonstrates
why model selection must pass reranking evaluation rather than inheriting
trust from query-router or retrieval benchmarks.
