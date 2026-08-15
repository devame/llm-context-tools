# Intent retrieval planning, structural reranking, and latency control

- Date: 2026-08-15
- Status: Accepted for implementation
- Scope: natural-language search, intent context, semantic query latency,
  multi-seed traversal, retrieval provenance, and evaluation
- Supersedes: no previous decision
- Extends: `2026-08-14_source-role-aware-intent-retrieval.md`

## Context

`llm-context context --intent` currently treats every natural-language request
as a request for one canonical symbol. Hybrid retrieval produces a ranked list,
source-role policy can reorder that list, and only the first result becomes a
graph traversal root. That works for symbol lookup and narrow implementation
questions. It is structurally unable to answer set-shaped questions such as
"what modules expose HTTP endpoints?" or broad workflow questions whose
evidence is distributed across several files.

The complete Metabase evaluation index illustrates four independent failure
modes:

1. A reasoning query can describe a set while the retrieval contract selects
   one symbol.
2. Semantic and lexical rank-one candidates receive equal reciprocal-rank
   scores when neither appears in the other list; qualified-name ordering can
   then decide the winner.
3. Exact syntax edges do not by themselves express higher-order repository
   concepts such as "HTTP API surface" or "email delivery pipeline".
4. The default 1.5 second semantic deadline is fast when warm but has
   insufficient margin for cold inference, process scheduling, or concurrent
   requests. A timeout silently changes hybrid retrieval into lexical fallback.

Index completeness is not at issue. Query-time model inference, candidate
scoring, transport, and graph hydration remain necessary after indexing.

## First principles

The retrieval system must keep five concerns separate:

- **Recall:** did candidate generation retrieve the evidence at all?
- **Qualification:** does a candidate satisfy structural constraints implied by
  the question, rather than merely sharing vocabulary?
- **Cardinality:** does the question ask for one location, a set, or a flow?
- **Expansion:** which exact graph relationships should be traversed after seed
  selection?
- **Serving:** did every stage complete within an explicit deadline, and is a
  degraded fallback visible to the caller?

No reranker can recover a candidate absent from the candidate pool. No graph
traversal can repair a wrong root. No larger timeout can repair a wrong ranking
objective. These stages therefore require independent metrics and tests.

## Decision

### 1. Introduce an intent query plan

Analyze every natural-language context request into an inspectable plan:

```clojure
{:shape :lookup | :set | :flow
 :seed-mode :single | :multi
 :max-seeds 4
 :concepts #{:code-concept/http-endpoint ...}
 :expanded-terms #{"api" "route" "handler" ...}
 :reason :explicit-set-language}
```

The analyzer is repository-neutral. It uses question grammar, common software
vocabulary, and explicit caller overrides; it never names Metabase paths or
symbols.

- `:lookup` selects one seed.
- `:set` selects several diverse qualifying seeds.
- `:flow` initially selects a small number of diverse seeds and relies on exact
  traversal; future data-flow-specific planners may refine this independently.
- Explicit CLI/RPC options override automatic shape detection.

Selecting every token match is prohibited. Query words are evidence for a
plan, not graph entities. Multi-seed selection remains bounded and deduplicates
same-file/same-namespace candidates unless an explicit mode requests otherwise.

### 2. Add task-aware structural reranking

After hybrid fusion and source-role preference, apply a score-preserving
reranking stage for intent context. It considers:

- normalized identifier and documentation terms;
- common code-vocabulary expansion (`endpoint` -> `api`, `route`, `handler`);
- symbol kind, file path, and source role;
- concept evidence such as route-like names and literal API paths;
- pre-rerank rank as the stable final tie-break.

The stage does not mutate LateOn or FTS scores. It emits separate relevance
and qualification channels: `:relevance-qualified?` and
`:relevance-reasons` describe vocabulary coverage, while
`:structurally-qualified?` and `:structural-reasons` describe evidence that a
candidate has the code role required by the task. It also emits
`:pre-rerank-rank`, `:intent-score`, and `:post-rerank-rank`. This separation
prevents a high lexical or semantic score from masquerading as structural
proof.

The built-in reranker is deliberately conservative: it only reorders when the
query analyzer identifies supported concepts or multiple candidates have
meaningful lexical coverage. Unknown questions retain fused ordering.

### 3. Select bounded, diverse traversal roots

For a multi-seed plan, choose at most `:intent-max-seeds` candidates using:

1. structural qualification;
2. intent score;
3. existing fused/source-role order;
4. file and namespace diversity.

All selected roots share one graph traversal and one token budget. Symbols,
topics, relationships, and effects are deduplicated. Alternatives remain
provenance only.

### 4. Preserve an aggregate-concept extension boundary

Higher-order repository concepts must be represented by evidence-backed facts,
not guessed edges. The canonical future representation is:

```text
concept:http-api-surface
  assembled-by  -> registry symbol
  exposes       -> route/handler symbol
```

Concept producers may be language analyzers, framework providers, or explicit
project manifests. Every fact must carry analyzer identity, source range or
manifest provenance, and confidence. Dynamic/computed membership remains a
diagnostic reference.

This decision does not hard-code framework names into the generic graph. The
first implementation provides query-time concept facets and a stable provider
boundary. Persisted aggregate nodes require a separately versioned graph-format
change and full analyzer qualification before activation.

### 5. Add a pluggable learned-reranker boundary

The built-in structural reranker remains dependency-free. An optional provider
may rerank the bounded candidate pool and return candidate IDs plus scores.
Provider output is advisory and must preserve candidate identity and freshness.
Timeout or provider failure falls back to built-in reranking with explicit
provenance.

The `reason-mxbai-colbert-v0-32m-onnx` model is a candidate experiment, not a
default dependency. It is a feature extractor requiring the exact exported
query/document contract, token masking, and ColBERT MaxSim. It may rerank
retrieved documents without a new whole-corpus index, but cannot recover
missing candidates or infer structural facts absent from candidate text.

The upstream [Reason-mxbai-colbert-v0-32m model
card](https://huggingface.co/DataScience-UIBK/Reason-mxbai-colbert-v0-32m)
reports a 32M-parameter, 128-dimensional PyLate checkpoint and contains
conflicting Apache-2.0 and inherited CC-BY-NC-4.0 license statements. A later
[v0.1 checkpoint](https://huggingface.co/DataScience-UIBK/Reason-mxbai-colbert-v0.1-32m)
repairs a residual-configuration issue and reports small regressions on its two
code-oriented BRIGHT splits. The separately published base-model ONNX export
uses a different 64-dimensional contract. Therefore the exact ONNX artifact,
architecture metadata, tokenizer behavior, output dimension, and effective
license must be pinned and reproduced before an llm-context adapter can be
qualified. A similarly named artifact is not interchangeable.

Fine-tuning is not authorized until held-out evaluation shows that candidate
recall is adequate and zero-shot reranking fails systematically after
structural metadata is present.

### 6. Make semantic deadlines caller-controllable and observable

The project configuration continues to define the default semantic timeout.
CLI and resident-service requests may provide a positive per-request override.
The demo defaults to 5,000 ms and exposes the value in its UI.

Every response reports:

- requested and effective timeout;
- semantic latency and status;
- whether fallback occurred;
- query plan and seed count;
- reranker provider/status.

Timeout is a retrieval status, not an empty successful result.

## Latency reduction strategy

Increasing the deadline protects correctness but does not reduce latency. Work
should follow the query critical path:

1. **Warm readiness:** readiness must include one representative query encode
   and search, not only process health. Keep model pages resident.
2. **Resident ownership:** retain one supervised model/index process instead of
   per-request startup or model loading.
3. **In-flight coalescing:** identical concurrent queries share one semantic
   request.
4. **Bounded result cache:** cache recent immutable query candidate packets by
   query, model/index generation, and retrieval parameters; freshness validation
   still occurs before graph use.
5. **Concurrency backpressure:** bound model inference concurrency to measured
   capacity rather than allowing request threads to create tail-latency
   collapse.
6. **Two-stage candidate cascade:** use inexpensive ANN/lexical recall first,
   then full MaxSim or learned reranking only on the bounded pool.
7. **Adaptive candidate work:** lookup questions need fewer candidates than set
   discovery. Candidate count is a plan output, not a universal constant.
8. **Deadline propagation:** divide a caller deadline across semantic search,
   optional reranking, graph hydration, and answer generation; do not stack
   independent unbounded waits.
9. **Index tuning:** qualify `n_ivf_probe`, `n_full_scores`, quantization, and
   candidate count against recall and p95 latency together.
10. **Hardware-aware defaults:** publish measured x86 and ARM profiles; avoid
    deriving CPU count from indexing throughput because serving has a different
    bottleneck.
11. **Compact documents:** remove repeated low-value boilerplate before
    encoding while preserving identifiers, signatures, literals, and exact
    relationship summaries.
12. **Telemetry:** record queue delay, query encoding, ANN lookup, full scoring,
    hydration, reranking, and traversal separately. Optimize measured dominant
    stages only.

The initial implementation delivers per-request deadlines, warm-up support at
the demo boundary, query-shape-aware lexical candidate breadth, an independently
bounded semantic top-k, and explicit fallback telemetry.
Coalescing/cache/backpressure require NextPlaid concurrency measurements before
changing service scheduling semantics.

## Semantic preprocessing candidates

The following preprocessing changes are candidates for controlled evaluation,
not an instruction to mutate all documents at once:

1. Split camelCase, kebab-case, snake_case, qualified names, and path segments
   while retaining the original identifier.
2. Add normalized singular/plural forms for code nouns.
3. Expand a small versioned software vocabulary: route/endpoint/handler,
   module/namespace/package, caller/invoker, persistence/database/store, and
   authentication/login/session.
4. Emit symbol-kind sentences such as "This symbol is a function" rather than
   relying only on terse labels.
5. Preserve literal paths, methods, topics, table names, environment keys, and
   command names as high-signal fields.
6. Summarize exact incoming and outgoing relationships separately.
7. Distinguish production, test, generated, vendor, and unknown source roles in
   the semantic text.
8. Add framework-derived facets only when a provider proves them from syntax or
   manifests.
9. Generate namespace/file summaries from contained public symbols.
10. Generate registry summaries from literal map/vector members and resolved
    references.
11. De-emphasize repeated license headers, generic macro boilerplate, generated
    comments, and examples unrelated to the symbol body.
12. Keep comments/docstrings but label examples separately from behavioral
    claims.
13. Preserve negation and modality; do not flatten "does not approve" into the
    same bag as "approves".
14. Encode source chunks with stable parent-symbol and chunk-role metadata.
15. Add query-side software-domain instructions only for models trained to
    consume them.
16. Use language-specific token normalization without destroying case-sensitive
    identifiers.
17. Add compact unresolved/dynamic-reference summaries as diagnostics, not
    exact relationships.
18. Evaluate separate field encodings or weighted MaxSim for identifier,
    documentation, source, and relationships.

Any document-text change increments semantic document version and requires a
new index generation. Each candidate must be ablated against the frozen public
suite before adoption.

## Configuration and interfaces

### Implemented query-routing amendment — 2026-08-15

The phrase-based automatic shape detector described earlier in this decision
has been removed from the production path. Its 41.7% balanced-corpus accuracy
made a default-to-lookup policy unsafe for unfamiliar wording.

Automatic requests now begin with an `:adaptive` multi-root retrieval plan and
the full configured candidate budget. In parallel with repository retrieval,
the resident `mixedbread-ai/mxbai-edge-colbert-v0-32m` INT8 ONNX model scores
three immutable route descriptions. The result is a prior, not a gate:

- all candidates are generated and fused before shape resolution;
- a set prior requires at least two structurally qualified candidates;
- a flow prior requires an exact canonical graph edge among bounded leading
  candidates;
- the top-two model score margin must meet the configurable 0.02 default;
- unsupported, timed-out, unavailable, or invalid advice leaves the plan
  adaptive;
- explicit single or multi caller options remain authoritative.

NextPlaid hosts the 33 MB router model in a second resident process and a
three-document disposable index. Retrieval and routing execute concurrently,
so warm router latency is normally hidden beneath repository retrieval. The
plan records all route scores, score margin, model revision, router latency,
structural support, and final planning authority.

Calibration with the exact NextPlaid ONNX runtime accepted 35 of the 72 frozen
questions at the 0.02 margin and classified 34 of those correctly (97.1%). A
0.06 margin accepted 12 of 72 correctly, but its low coverage was not useful as
the default. These are routing-only development measurements, not final-answer
quality claims.

Proposed project defaults:

```edn
{:semantic
 {:lateon-code
  {:query-timeout-ms 1500}}
 :context
 {:intent-seed-mode :auto
  :intent-max-seeds 4
  :intent-rerank true}}
```

The library default remains conservative for compatibility; applications may
choose a larger value. The demo default is 5,000 ms.

Proposed CLI:

```text
llm-context context --intent QUERY
  [--semantic-timeout-ms N]
  [--seed-mode auto|single|multi]
  [--max-seeds N]
```

RPC carries the same fields in context options. Core requests require a
positive integer; applications may impose a deployment-specific upper bound.
The demo accepts 100 through 30,000 ms and rejects invalid values before model
I/O.

## Evaluation gates

Report recall and selection separately:

- candidate Recall@50;
- selected-root Recall@1 and Recall@k;
- MRR and nDCG@10;
- set coverage and duplicate-file rate;
- exact graph evidence coverage;
- semantic timeout/fallback rate;
- warm p50/p95/p99 and cold-first-query latency;
- answer support and unsupported-claim rate.

The corpus must include:

- exact symbol lookup;
- singular implementation questions;
- production-versus-test questions;
- set discovery;
- cross-cutting workflows;
- negative and adversarial lexical overlap;
- timeout and unavailable-provider behavior.

Metabase is one qualification repository, not a source of hard-coded rules.
At least two smaller repositories with different layouts must exercise the same
planner and reranker behavior.

## Compatibility and rollout

- `query search` retains corpus-wide fused ordering unless intent reranking is
  explicitly requested.
- Exact symbol context remains single-root.
- Natural-language context defaults to automatic planning.
- Callers can force single-root behavior during rollout.
- Existing semantic indexes remain valid for query planning, built-in
  reranking, and multi-seed traversal.
- Persisted concept nodes or changed semantic documents require a future graph
  or document-version migration.

## Implementation slices

This decision is intentionally split so correctness work does not acquire an
unqualified model dependency:

1. **Delivered:** query-shape planning, normalized vocabulary expansion,
   structural qualification, score-preserving reranking, bounded diverse roots,
   and compact set inventories.
2. **Delivered:** independent semantic/lexical candidate budgets, per-request
   deadline overrides, fallback telemetry, and the demo's 5,000 ms default plus
   user control.
3. **Deferred behind evaluation:** an external learned-reranker adapter. Its
   provider contract is specified above, but no model is loaded until artifact,
   license, code-domain quality, latency, and held-out lift pass the gates.
4. **Deferred behind graph-format design:** persisted aggregate concept nodes.
   Query-time inventories provide bounded utility without inventing canonical
   relationships.

## Rejected alternatives

- **Always traverse the top N:** amplifies irrelevant candidates and hides
  cardinality errors.
- **Treat every query token as a required graph node:** natural-language words
  are not canonical code identities.
- **Only increase the timeout:** improves availability but not relevance.
- **Only fine-tune a model:** cannot fix absent candidates, missing structural
  facts, or a single-root contract.
- **Metabase-specific route heuristics:** would improve one demo while weakening
  the product architecture.
- **Replace exact graph traversal with LLM exploration:** loses deterministic
  provenance and bounded behavior.

## Consequences

Intent retrieval becomes a visible planning pipeline rather than one opaque
ranked list. Set questions can return several evidence roots, while lookup
questions retain their current precision. Applications can trade latency
against fallback explicitly. Structural preprocessing and learned reranking
remain independently measurable and replaceable.

The implementation is more complex, so provenance and frozen evaluations are
release gates rather than optional diagnostics.

## Qualification integrity amendment — 2026-08-15

An implementation review found that query-term matches, concept hints, and
structural qualification were accumulated into one reason set. As a result, a
candidate such as `set-role-if-supported!` could be relevant to the words
"supported databases" and then be treated as if it proved membership in the
requested database set. If a router advised `:set` or `:flow`, this relevance
could authorize the wrong retrieval shape and amplify the original miss.

The implementation therefore adopts the following invariants:

1. Lexical and semantic relevance may generate and reorder candidates, but
   cannot structurally qualify them.
2. Automatic `:lookup` requires exactly one structurally qualified candidate.
   Automatic `:set` requires at least two. Automatic `:flow` requires at least
   two plus an exact execution edge between qualified candidates.
3. Only exact `calls` and `macro-invokes` edges currently establish execution
   flow. Containment, imports, implementation, topic, and generic reference
   edges do not.
4. Set inventories contain only structurally qualified candidates.
5. When no candidate qualifies, bounded relevance-based roots may still form a
   best-effort packet, but the plan stays `:adaptive` and reports
   `:evidence-status :relevance-only` plus
   `:seed-selection-authority :relevance-fallback`. If there is not even
   query-term relevance, it reports `:no-evidence` and `:rank-fallback`.
6. Explicit caller seed-mode overrides remain authoritative. Their provenance
   still discloses whether structural evidence supported the selected roots.

These rules are repository-neutral. Concept qualification may later be
extended by analyzers or framework providers, but every extension must emit a
named, testable structural reason rather than promote retrieval score into
proof.
