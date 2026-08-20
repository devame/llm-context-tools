# ARD: Queue-aware semantic ingestion and conservative enrichment

- Date: 2026-08-19
- Status: Proposed for phased implementation
- Scope: LateOn semantic document ingestion, NextPlaid 1.7.0 queue use,
  batching, visibility finalization, recovery, throughput telemetry, and the
  bounded evaluation of additional source-proven architecture facts
- Compatibility boundary: no graph-format, semantic-document-version, model,
  or provider-index-format change in the accepted implementation path
- Related decisions:
  [provider-native recovery and indexing](2026-08-16_provider-native-recovery-and-indexing.md),
  [NextPlaid accelerator selection](2026-08-16_next-plaid-accelerator-selection.md),
  [operational health and self-healing](2026-08-18_operational-health-and-self-healing.md),
  and
  [hierarchical aggregate and concept retrieval](2026-08-16_hierarchical-aggregate-and-concept-retrieval.md)

## Decision summary

CUDA is functioning on the qualification host, but the semantic ingestion path
does not keep the accelerator and provider writer continuously occupied. The
current CUDA profile uses one ONNX session, an encoder micro-batch of one, one
provider request at a time, and 32 provider documents per request. The worker
then waits for exact provider visibility before leasing another group.

NextPlaid 1.7.0 already supplies two bounded queues:

- an encoding queue that can collect up to 64 texts over a 10 ms window; and
- an index-update queue that can collect up to 300 provider documents over a
  100 ms window.

llm-context also already supplies the durable cross-provider outbox, leases,
accepted markers, exact metadata verification, provider generation sentinel,
and replay-safe delete-await-submit-verify recovery. This decision will first
make those existing facilities work together efficiently. It will not add a
second unbounded queue or treat provider acceptance as completion.

Implementation proceeds through two performance gates:

1. **Queue utilization gate.** Add trustworthy stage telemetry and a
   disposable qualification harness, then evaluate larger provider requests
   and bounded concurrent requests while preserving the current visibility
   barrier. This uses the existing worker and recovery model.
2. **Pipeline gate.** Add a separate bounded visibility finalizer only if the
   first gate still shows material accelerator idle time or visibility waiting.
   Accepted jobs remain leased and renewable; a process restart continues to
   recover through exact provider inventory rather than assuming that an HTTP
   202 was durable completion.

Encoder micro-batch size, model choice, and provider request size are separate
knobs. The existing workload qualification found that an encoder micro-batch of
two was slower than one, while a larger provider update materially improved
throughput. This decision therefore keeps the encoder micro-batch at one until
a disposable, model-specific benchmark proves otherwise. It does not infer a
safe micro-batch from GPU presence or copy NextPlaid's generic CUDA default.

Additional entity extraction is deliberately constrained. No generic
architectural entity, broad keyword-use relation, static-schema field,
denormalized call list, model switch, or namespace public-API array is added by
this decision. The only accepted enrichment work is a read-only qualification
slice for source-proven framework architecture facts. Production graph facts
require a later amendment after neutral fixtures and cross-repository queries
prove a useful, unambiguous identity and provenance contract.

## Context

### Observed workload

The live qualification workload is the Metabase checkout under WSL with:

- 61,628 desired semantic symbol documents;
- `lightonai/LateOn-Code` at pinned revision
  `ace431824f35db231178fc602e33296784762a2e`;
- NextPlaid 1.7.0;
- CUDA/FP32 inference on a GeForce GTX 1660 with 6 GB VRAM;
- one CUDA encoding session;
- encoder micro-batch size one;
- provider update size 32; and
- CUDA update concurrency one.

The runtime was not silently falling back to CPU. Runtime status reported CUDA,
the child command contained `--cuda`, the correct FP32 model was loaded, VRAM
use was approximately 3.9-4.5 GB, and sampled GPU utilization ranged from 1% to
95%. The ten-second sample averaged approximately 34%, showing bursty work
rather than continuous accelerator saturation.

At 13,408 completed documents, cumulative worker telemetry was:

| Stage | Time | Share of elapsed time |
| --- | ---: | ---: |
| Provider upload, encoding, and acceptance | 1,120,045 ms | 55.3% |
| Waiting for exact provider visibility | 432,588 ms | 21.4% |
| Document construction, queue/database work, and other time | 471,233 ms | 23.3% |
| Total | 2,023,866 ms | 100% |

The worker had submitted 13,739 provider chunks for 13,408 symbols in 544
upload batches. The average was therefore approximately 24.7 symbols and 25.3
provider chunks per upload batch, below the configured limit of 32 because a
lease can contain multi-chunk symbols and grouped preparation can produce an
underfilled final request.

Across 624 observed `update_with_encoding` requests:

- median response latency was 2,155 ms;
- p95 response latency was 3,320 ms;
- maximum response latency was 15,155 ms; and
- metadata lookup latency had a median of 4 ms and p95 of 11 ms.

The visibility cost is therefore not loopback HTTP latency. It is predominantly
the provider's asynchronous physical update plus the worker's 250 ms polling
interval and synchronous barrier.

The reported cumulative rate declined from 7.23 documents/second near 5,248
indexed documents to 6.59 documents/second near 15,328. Source-length variation
can contribute, but the provider's incremental update algorithm also performs
work proportional to existing mutable index structures.

### Provider write behavior

NextPlaid 1.7.0 stores each residual index chunk in complete NPY and JSON files.
When the newest chunk contains fewer than approximately 2,000 documents, an
incremental update:

1. reads the previous codes, residuals, inverse norms, and document lengths;
2. concatenates the new values;
3. atomically replaces the complete current chunk files;
4. loads and merges the global IVF arrays;
5. writes the updated metadata; and
6. removes merged files so they are regenerated consistently.

This policy avoids a large number of tiny chunks and gives readers complete,
atomically replaced files. Its tradeoff is write amplification when a large
cold build arrives as many small updates. A 32-document request can cause the
same growing chunk and global IVF to be rewritten dozens of times before the
chunk reaches 2,000 documents.

The relevant pinned-provider sources are:

- [encoding queue](https://github.com/lightonai/next-plaid/blob/v1.7.0/next-plaid-api/src/handlers/encode.rs);
- [provider update queue](https://github.com/lightonai/next-plaid/blob/v1.7.0/next-plaid-api/src/handlers/documents.rs); and
- [incremental physical update](https://github.com/lightonai/next-plaid/blob/v1.7.0/next-plaid/src/update.rs).

### Existing llm-context behavior

The current worker already:

- leases `update-batch-size * update-concurrency` durable jobs;
- builds symbols from each source file together;
- partitions rendered chunks into provider requests;
- can submit several requests on a bounded executor;
- records `accepted-at` only after every request returns successfully;
- waits for the exact complete chunk set to become visible;
- completes jobs and indexed records atomically in Datalevin;
- renews leases during long provider and visibility operations;
- recovers expired leases to pending;
- reuses exact provider documents when all identity, hash, model, version, and
  chunk facts match; and
- deletes all provider chunks for a symbol before replacing missing, stale,
  partial, or duplicate state.

The concurrency mechanism is therefore implemented. The CUDA-specific
configuration currently selects concurrency one, so it does not exercise the
provider's ability to overlap encoding and physical updates.

### Existing qualification evidence

The provider-native indexing decision recorded two important earlier results:

- increasing provider update size from 32 to 512 improved a pinned Metabase
  workload from approximately 41 to 344-376 documents/minute; and
- increasing the CUDA encoder micro-batch from one to two increased memory and
  reduced throughput.

Those results are useful design inputs, not universal defaults. They were
collected against a prior pinned-provider qualification path and must be
repeated against packaged NextPlaid 1.7.0 with the current model, document
version, hardware, and project snapshot before changing defaults.

## Problem statement

The system has queues but currently realizes little pipeline parallelism on
CUDA:

```text
lease and build up to 32 chunks
             |
             v
encode one provider request
             |
             v
queue and physically update the index
             |
             v
poll until all chunks are visible
             |
             v
complete durable jobs, then lease again
```

This produces five related problems:

1. Encoder micro-batch, provider request batch, provider physical batch, and
   durable lease window are described with similar names even though they have
   different memory, latency, and recovery effects.
2. Small provider requests amplify rewriting of the mutable current chunk and
   global IVF.
3. CUDA concurrency one prevents the provider encoding queue from collecting
   separate requests and prevents encoding of later work from overlapping a
   prior physical index update.
4. A visibility barrier after every small lease leaves no later work available
   to the encoder while the provider writer and metadata store finish.
5. Cumulative documents/second hides current degradation, document-length
   variation, provider queue depth, physical batch size, and stage duty cycle.

Queue capacity alone does not solve these problems. An unbounded queue would
move latency into memory, enlarge ambiguous recovery windows, and make shutdown
slower. Throughput improves only when bounded queuing combines physical work or
overlaps independent stages.

## Goals

1. Increase cold-build throughput without weakening exact final coverage.
2. Reduce physical index updates and bytes written per visible provider chunk.
3. Keep encoding active while the provider writes a previous accepted batch,
   when the pinned provider and available memory permit it.
4. Preserve bounded memory, leases, backpressure, cancellation, supersession,
   and restart convergence.
5. Separate encoder micro-batch, request size, request concurrency, physical
   provider batch, and visibility-finalization batch in configuration and
   telemetry.
6. Make tuning reproducible on a disposable index and representative project
   documents; never experiment against the only live index.
7. Preserve existing retrieval quality by keeping document text, model,
   quantization, index compression, and query behavior unchanged.
8. Add no new canonical architecture claim until exact identity, provenance,
   and user-visible retrieval value are demonstrated independently.
9. Deliver the change as independently testable and revertible commits.

## Non-goals

- Treating HTTP 202 as visible or durable provider completion.
- Removing the Datalevin semantic outbox, generation sentinel, leases, or
  indexed records.
- Making NextPlaid queue state authoritative for desired semantic coverage.
- Adding an unbounded JVM queue or retaining entire-project rendered documents
  in memory.
- Increasing ONNX sessions or model pool size on the 6 GB qualification GPU.
- Automatically switching from `LateOn-Code` to `LateOn-Code-edge`.
- Automatically increasing encoder micro-batch size because CUDA is present.
- Changing source chunking, overlap, maximum document bytes, document text,
  model revision, quantization, PLAID compression, or search parameters.
- Importing a FastPlaid index in this implementation path.
- Executing project code, loading project namespaces, or evaluating forms to
  discover architecture.
- Adding generic `:arch/*`, `:var/calls`, `:var/uses-keys`, `:var/schema`, or
  `:ns/public-api` persisted fields.
- Treating every keyword occurrence as a state, schema, or architecture fact.
- Using Metabase-specific paths, symbols, frameworks, or terms in production
  tuning or extraction logic.

## Terminology

The implementation and status output use these distinct terms:

| Term | Meaning |
| --- | --- |
| Symbol job | One durable desired symbol operation in Datalevin |
| Provider document | One rendered semantic chunk submitted to NextPlaid |
| Lease window | Maximum symbol jobs owned by one worker operation |
| Request batch | Provider documents in one `update_with_encoding` request |
| Request concurrency | Maximum provider HTTP requests in flight together |
| Encoder micro-batch | Texts processed by one ONNX execution batch inside NextPlaid |
| Encoding queue | NextPlaid queue that coalesces compatible encoding requests |
| Physical update batch | Embeddings combined by NextPlaid for one index mutation |
| Accepted | Every request for a leased group returned successfully; not yet visible |
| Visible | Exact expected metadata and chunk set can be read from NextPlaid |
| Finalization batch | Accepted jobs checked and completed together |

Metrics and configuration must not label all of these as `batch-size` without
the qualifying noun.

## Invariants

### Authority

1. Datalevin remains authoritative for desired symbol documents and their
   current document hashes.
2. NextPlaid remains authoritative for the physical index it serves, but its
   queue is not a durable desired-work ledger.
3. A job is complete only when the exact provider chunk set is visible and the
   matching indexed record is committed.
4. Provider acceptance does not advance semantic coverage.

### Boundedness

1. Leased jobs, rendered provider documents, requests in flight, accepted jobs
   awaiting finalization, and metadata returned per poll all have explicit
   bounds.
2. Bounds are based on provider documents and estimated bytes as well as symbol
   count because one symbol may produce several chunks.
3. The worker never accumulates the entire project corpus in memory.
4. Backpressure reduces or pauses submission; it never creates another
   unbounded retry queue.

### Recovery

1. Direct resubmission is not assumed idempotent.
2. Missing, partial, stale, duplicate, or ambiguous provider state uses
   delete-await-submit-verify.
3. Exact visible provider state can reconstruct a missing Datalevin indexed
   record without re-encoding.
4. Superseded jobs cannot complete with an old document hash.
5. Provider-wide failures do not consume document-scoped terminal retry
   budgets.

### Compatibility

1. The first performance gate does not change graph format, document version,
   provider index format, or model package.
2. Existing project configuration retains its behavior until a new option is
   explicitly enabled or a qualified default is promoted in a later commit.
3. Disabling queue-aware ingestion restores the current synchronous worker
   path without rebuilding the graph or semantic index.

## Decision

### 1. Establish a disposable qualification harness first

Add a benchmark command or developer alias that:

1. opens the project graph read-only through the normal graph owner;
2. deterministically selects representative desired semantic documents by
   source role, language, byte-size bucket, and chunk count;
3. renders those documents with the production document builder;
4. creates a temporary sibling NextPlaid index with the pinned model and
   effective accelerator profile;
5. runs a declared matrix of request batch and concurrency settings;
6. waits for exact final visibility;
7. records wall time, stage timings, RSS, VRAM, provider-index growth, process
   write bytes, failures, retries, and duplicate metadata; and
8. removes only its marked temporary directory after successful verification,
   or preserves it with an explicit diagnostic path after failure.

The harness must never point at `.llm-context/semantic/next-plaid` and must
reject a destination equal to, inside, or containing the active index path.
Normal operation continues while the spike is read-only only if available RAM,
VRAM, and storage pass a separate safety check; otherwise the operator must
stop the project service before qualification.

The first matrix keeps model, encoder micro-batch, ONNX sessions, document
length, pool factor, and index configuration fixed. It evaluates provider
request batches `32`, `128`, `300`, and `512` at request concurrency `1` and
`2`. A test case may be skipped when the storage or memory guard says it is
unsafe; skipping is reported, not silently converted into a smaller case.

### 2. Add stage and queue telemetry without changing execution

The worker progress snapshot gains recent-window and cumulative counters for:

```clojure
{:prepared-symbol-jobs n
 :prepared-provider-documents n
 :prepared-text-bytes n
 :leased-symbol-jobs n
 :request-count n
 :request-provider-documents n
 :request-text-bytes n
 :request-concurrency-effective n
 :accepted-symbol-jobs n
 :visible-symbol-jobs n
 :reused-symbol-jobs n
 :prepare-ms n
 :submit-ms n
 :visibility-ms n
 :completion-ms n
 :provider-backpressure-count n
 :provider-retry-count n}
```

Where provider status exposes them, record physical update document count,
encoding queue depth, update queue depth, encode time, index-write time, and
metadata-write time. If the pinned API does not expose a field, status reports
it as unavailable; llm-context does not scrape human log text into canonical
metrics.

Concise status continues to show documents/second for compatibility but uses a
recent completed-work window when available. Verbose status distinguishes
symbols from provider chunks and includes stage percentages. The benchmark
also reports bytes/second and provider chunks/second. Tokens/second is not
invented from UTF-8 bytes; it is reported only if the provider exposes actual
token counts.

### 3. Correct storage attribution before comparing write cost

Storage sampling must probe the filesystem containing the actual semantic
index path. The observed status described `/mnt/c` and `C:\` while the active
index was under `/home` on ext4. That mismatch does not explain current
throughput, but it makes reserve and operation-growth evidence unreliable.

The fix resolves the configured index path, identifies its nearest existing
ancestor before initial creation, and samples that filesystem. Tests cover WSL
home, `/mnt/c`, a missing final index directory, and paths containing symbolic
links. Existing path-containment and no-follow safety rules remain in force.

### 4. Select a queue profile with a pure planner

Introduce a pure ingestion planner that receives:

```clojure
{:pending-symbol-jobs n
 :pending-provider-documents-estimate n
 :operation-mix {:upserts n :deletes n}
 :accelerator :cpu|:cuda
 :provider-version "1.7.0"
 :configured-request-batch n
 :configured-request-concurrency n
 :configured-max-inflight-documents n
 :profile :steady|:cold|:auto}
```

and returns explicit bounds:

```clojure
{:profile :steady|:cold
 :lease-symbol-limit n
 :request-provider-document-limit n
 :request-concurrency-limit n
 :max-inflight-provider-documents n
 :reason keyword}
```

The planner performs no I/O and has exhaustive table tests. It does not inspect
GPU names, guess VRAM capacity, or mutate configuration.

Profiles have these semantics:

- `:steady` preserves low incremental latency and the current configured
  request bounds.
- `:cold` uses a separately qualified larger request and bounded concurrency
  for a substantial backlog dominated by upserts.
- `:auto` selects `:cold` only above a configured backlog threshold and returns
  to `:steady` with hysteresis so the worker does not oscillate every lease.

The qualification result recommends values; it does not silently write
`llm-context.edn`. Default promotion is a separate release decision after the
cross-repository gate.

### 5. Exercise the existing provider queues before adding a finalizer

Implementation correction (2026-08-19): live qualification showed that a 202
response only acknowledged queueing. Releasing the next request immediately
allowed NextPlaid to coalesce nominally bounded 32-document requests into
33- and 64-document physical CUDA compression batches. The submitter therefore
uses bounded waves: requests within one wave remain concurrent, but the next
wave is not released until every document in the current wave has exact
provider visibility. The configured in-flight bound now covers accepted but
not yet visible documents. This is a synchronous safety barrier, not the
separate background visibility finalizer described in Gate 2.

The first execution change uses the planner's lease, request, and concurrency
bounds. Bounded executor submission keeps requests within a wave in flight;
the implementation correction above adds visibility barriers between waves
while retaining the final `process-once!` exact-visibility check.

For a cold profile with request concurrency two:

```text
request A: encode ----------> provider update queue -> physical write
request B:        encode ---------------------------> provider update queue
```

With one provider model worker, encoding remains serialized. The useful
overlap is between encoding request B and physically writing request A. No
second CUDA model instance is created.

All requests in a lease group must return successfully before the group gets an
accepted marker. If one request fails or has ambiguous acceptance, the group
remains leased and follows the existing provider-failure recovery path. Exact
visibility is checked for every prepared symbol before completion.

This gate is promoted only when the disposable benchmark demonstrates all of:

- no duplicate provider documents for a symbol;
- zero missing or stale completed documents;
- no terminal document failures;
- bounded host RSS and VRAM;
- no storage-headroom regression;
- lower physical updates or write bytes per visible provider document; and
- either at least 20% higher recent-window throughput or at least 30% lower
  combined submit-plus-visibility time on the same sample.

The percentage gate is comparative, not a universal machine-independent speed
requirement.

### 6. Treat provider saturation as backpressure

HTTP 429 and 503, a full provider encoding queue, and a full provider update
queue are provider backpressure. They:

- do not consume a document terminal retry attempt;
- return affected work to pending through the existing provider-failure path;
- publish a bounded health transition and recommended action;
- apply configured exponential delay; and
- temporarily reduce request concurrency to one for the cooldown interval.

Concurrency returns to its configured bound only after a bounded number of
successful request groups. The current effective limit and last reduction
reason appear in verbose status. This is additive-increase/multiplicative-
decrease behavior over a bound of one or two, not a general adaptive optimizer.

### 7. Add a visibility finalizer only if Gate 1 is insufficient

After queue-aware requests are qualified, inspect the stage telemetry. A
separate finalizer is implemented only if either condition holds on at least
two representative repositories:

- visibility waiting remains more than 15% of cold-build elapsed time; or
- sampled GPU utilization repeatedly drops below 50% while accepted work is
  waiting on physical index mutation and pending work remains.

The finalizer introduces two bounded in-memory roles under one worker owner:

```text
durable pending jobs
        |
        v
prepare + submitter ---- accepted batch registry
                              |
                              v
                     visibility finalizer
                              |
                              v
                   atomic Datalevin completion
```

An accepted batch registry entry contains only bounded identifiers and expected
metadata, not source bodies or embeddings:

```clojure
{:batch-id uuid
 :job-ids [...]
 :symbol-ids [...]
 :expected [{:symbol-id string
             :document-hash string
             :model-revision string
             :document-version integer
             :chunk-count integer}]
 :accepted-at epoch-ms
 :next-poll-at epoch-ms
 :poll-attempts n}
```

The registry is reconstructible, not authoritative. Durable jobs remain
`:leased` with `:semantic.job/accepted-at`; a lease-renewal task renews all
accepted batches owned by the worker. The registry is bounded by configured
accepted symbol count, provider document count, and age. When any bound is
reached, submission stops until finalization creates capacity.

The finalizer groups metadata reads up to the existing inventory limit, applies
exponential polling bounded by the visibility timeout, and completes visible
jobs in one conditional Datalevin operation. A superseded job is isolated and
cannot block completion of unchanged siblings.

No new durable `:accepted` status is introduced in this decision. A process
crash loses only the in-memory registry. Existing accepted markers expire with
their leases and return to pending. The replacement worker then performs exact
provider inventory recovery; it never blindly resubmits accepted work.

### 8. Define failure behavior at every boundary

| Failure point | Durable observation | Recovery |
| --- | --- | --- |
| Before lease | Job remains pending | Ordinary later lease |
| During document preparation | Leased, not accepted | Source race or document retry classification |
| Provider rejects before acceptance | Leased, not accepted | Backpressure or provider recovery; no completion |
| Some concurrent requests return 202 and another fails | Leased, no group accepted marker | Exact inventory; reuse exact symbols, delete-await-submit others |
| All requests return 202 | Leased with accepted marker | Finalize only after exact visibility |
| Worker crashes after acceptance | Lease and marker persist until expiry | Expire to pending; exact inventory reconstruction |
| Provider crashes during physical update | Accepted may be partial or absent | Supervisor restart, generation check, exact inventory, delete-await-submit |
| Source changes while accepted | New desired hash supersedes old job | Old owner cannot complete; replacement removes stale provider chunks |
| Visibility timeout | Leased accepted work becomes retryable provider failure | Circuit recovery and exact inventory before submission |
| Datalevin completion fails | Provider may be exact; indexed record absent | Reconstruct indexed record from exact provider metadata |
| Shutdown requested | Stop new leases, finish or release bounded work | Renew during graceful timeout, then allow expiry recovery |

Fault tests must inject each transition. A successful HTTP response is never
the assertion for a successful test; exact visible metadata and durable
completion are the assertions.

### 9. Keep document ordering deterministic

The planner may group jobs by file to avoid repeated source reads and may
partition rendered chunks into larger requests. For identical graph state and
configuration, request membership and order remain deterministic:

1. jobs ordered by file ID and semantic job ID;
2. symbols ordered by canonical source range and symbol ID;
3. chunks ordered by symbol ID and chunk index; and
4. requests formed by stable partitioning.

Length-aware bucketing is deferred. The JVM does not own the pinned model's
tokenizer, and UTF-8 bytes are not a reliable token count for code. It may be
added only if NextPlaid exposes actual pre-encoding token lengths or a later
benchmark proves that a deterministic byte bucket improves throughput without
duplicating tokenizer behavior.

### 10. Keep the current model and encoder micro-batch

The production model remains the full pinned `LateOn-Code` model. The edge
model may be benchmarked as an explicit retrieval-quality experiment, but it is
not a throughput fix within this decision because changing the model requires a
new provider generation and may change Recall, MRR, and ranking behavior.

CUDA encoding sessions remain one on the qualification GPU. Encoder
micro-batch remains one by default because the existing workload measurement
found two slower and more memory-intensive. The qualification harness may test
other micro-batches only in a separate declared matrix after request batching
is optimized; those runs cannot mutate the live default automatically.

### 11. Conservatively limit additional extraction

The proposed namespace, var, call, key, schema, and architecture maps combine
canonical facts with denormalized retrieval projections. Most proposed fields
already exist as symbols, exact edges, source ranges, topics, aggregates, or
document hashes. Copying them into arrays would increase semantic text,
invalidation fan-out, and cold-build work while discarding edge-level evidence.

This decision classifies the candidates as follows:

| Candidate | Decision | Reason |
| --- | --- | --- |
| Namespace name, path, requires, public API | Do not add | Namespace symbols, import/contains edges, private flags, and source already exist; a stored array is derived duplication |
| Var name, args, docs, private/macro flags | Do not add | Existing canonical symbol fields already carry them |
| Var call list | Do not add | Exact call edges preserve location, resolution, evidence, and traversal |
| Separate call-edge map | Do not add | Existing exact edges already implement it |
| Per-var source hash | Do not add | Versioned semantic document hash already covers rendered source and graph-derived text |
| Generic `uses-keys` | Do not add | Lexical keyword occurrence is too noisy; proven state reads/writes already use typed topic edges |
| Dedicated static schema field | Defer | Malli/spec/schema forms can be computed or macro-generated; existing safe aggregates retain literal structures without claiming contract semantics |
| Namespace retrieval projection | Defer | It changes semantic text and needs a separate retrieval-quality and freshness decision |
| `LateOn-Code-edge` default | Do not add | It changes retrieval quality and requires a complete model-generation evaluation |
| Framework architecture facts | Qualification only | Potentially valuable, but identity, ownership, dependency direction, and dynamic configuration need proof |

The accepted enrichment unit is therefore read-only qualification, not a graph
format change. It will:

1. define neutral Integrant-style fixtures containing literal component keys,
   resolved lifecycle forms, literal references, aliases, duplicate keys,
   reader conditionals, and dynamic negative cases;
2. record what embedded clj-kondo and safe reader forms can prove without
   loading project code;
3. define at least ten architecture questions whose answers require component
   definition or dependency evidence;
4. compare the proposed facts with existing symbols, calls, topics, aggregates,
   and source context on at least three unrelated public repositories; and
5. produce a go/no-go note covering identity, provenance, full/incremental
   convergence, graph growth, semantic-document impact, and query value.

No production extractor, schema enum, entity type, edge kind, semantic label,
or document version is added until that note is accepted as an amendment. A
failed or ambiguous spike ends the extraction effort without affecting the
queue-aware ingestion work.

## Configuration

Configuration is introduced only after the pure planner and disposable harness
exist. The provisional shape is:

```clojure
{:semantic
 {:lateon-code
  {:ingestion-profile :steady
   :update-batch-size 32
   :update-concurrency 4
   :cuda-update-concurrency 1
   :cold-ingestion
   {:enabled false
    :backlog-threshold 2048
    :exit-threshold 1024
    :update-batch-size 512
    :update-concurrency 2
    :max-inflight-provider-documents 1024}
   :visibility-finalizer
   {:enabled false
    :max-accepted-symbols 1024
    :max-accepted-provider-documents 2048
    :initial-poll-ms 250
    :max-poll-ms 2000}}}}
```

These values are candidates for qualification, not accepted universal defaults.
The checked-in defaults keep both new features disabled until their promotion
commits satisfy the gates. Existing `update-batch-size` and accelerator-specific
concurrency remain authoritative in `:steady` mode.

Validation requires positive bounds, `exit-threshold <= backlog-threshold`, and
accepted/finalizer bounds no smaller than one request. Unknown profiles and a
finalizer enabled without queue-aware ingestion fail configuration validation.

Qualification amendment (2026-08-20): a source-redacted 512-symbol run on
`cl-viz-cljs` rendered 1,052 provider documents with NextPlaid 1.7.0 on a
6 GB GTX 1660. A concurrency-one fine sweep measured `128`, `160`, `192`,
`224`, and `256` provider documents per request. `192x1` was the practical
knee: compared with `32x1`, symbol throughput increased 8.8%, process writes
fell 83.4%, and exact-visibility time fell 52.5%. `224x1` was 0.5% slower and
reached approximately 5.35 GB VRAM; `256x1` timed out. The project may therefore
opt into `192x1`, but this does not pass the cross-repository promotion gate and
does not change the checked-in global defaults. Gate 2 remains separate.

## Commit-sized implementation plan

Each unit below is intended to be one reviewable commit. A unit may update
implementation, focused tests, and directly corresponding documentation; it
must not absorb the next unit merely because the files overlap.

### Commit 1: Record the decision and pinned evidence

**Change**

- Add this ARD.
- Link it from the architecture and benchmark documentation.
- Record NextPlaid 1.7.0 queue and update behavior in the dependency registry,
  including the exact tag and artifact identity.

**Tests/checks**

- Documentation link check.
- Dependency drift check continues to pass.

**No behavior change.**

### Commit 2: Separate throughput metric names

**Change**

- Rename or supplement ambiguous internal counters with symbol-job, provider-
  document, request, and finalization terminology.
- Preserve existing verbose keys for one compatibility release where public.
- Add cumulative prepare and completion timing around existing code.

**Tests/checks**

- Worker tests assert symbols and chunks independently.
- Status fixtures assert old compatibility fields and new explicit fields.

**No scheduling change.**

### Commit 3: Add recent-window progress telemetry

**Change**

- Add a bounded ring of progress samples or constant-space rolling buckets.
- Report recent completed symbols/second, provider documents/second, text
  bytes/second, and stage percentages.
- Reset recent rate to zero or unavailable after a defined idle interval while
  retaining cumulative totals.

**Tests/checks**

- Deterministic clock tests for active, idle, stalled, resumed, and completed
  queues.
- Memory remains constant as sample count grows.

**No scheduling change.**

### Commit 4: Probe the semantic index filesystem correctly

**Change**

- Resolve storage sampling from the configured semantic index path.
- Use the nearest existing ancestor before first index creation.
- Preserve no-follow and project containment rules.

**Tests/checks**

- Native WSL ext4, `/mnt/c`, missing leaf, symlink, and outside-root fixtures.
- Existing storage guards remain fail-closed.

**No queue change.**

### Commit 5: Build the isolated NextPlaid workload driver

**Change**

- Add a developer-only driver that starts the packaged NextPlaid binary in a
  marked temporary directory and submits generated neutral documents.
- Capture request, visibility, process I/O, index size, RSS, and accelerator
  metrics.
- Validate exact metadata and duplicate absence before cleanup.

**Tests/checks**

- Fake executable tests for startup, timeout, malformed status, and cleanup.
- A smoke test runs only when the packaged provider artifact is available.

**No project source or live index access.**

### Commit 6: Add representative project-document sampling

**Change**

- Read a bounded deterministic sample through the normal graph owner.
- Stratify by language, source role, rendered byte bucket, and chunk count.
- Feed rendered text to the isolated driver without persisting source in
  benchmark output.

**Tests/checks**

- Stable sample selection independent of Datalevin EIDs.
- Redaction tests prohibit source text and absolute user paths in reports.
- The active provider index path is rejected as a destination.

**No production worker change.**

### Commit 7: Add the request/concurrency matrix reporter

**Change**

- Run request batches `32`, `128`, `300`, and `512` at concurrency `1` and `2`
  with encoder settings held fixed.
- Emit a machine-readable report and a concise comparison table.
- Mark unsafe, unsupported, failed, and completed cases distinctly.

**Tests/checks**

- Report schema and deterministic ranking tests.
- Failure in one case does not erase completed case evidence.

**Gate output**

- Select candidate cold request and concurrency bounds, or stop if no case
  satisfies correctness and resource gates.

### Commit 8: Introduce the pure ingestion planner

**Change**

- Add `:steady`, `:cold`, and `:auto` plan calculation with no side effects.
- Keep checked-in runtime behavior equivalent to `:steady`.
- Add configuration validation for disabled cold-profile candidate settings.

**Tests/checks**

- Exhaustive threshold, hysteresis, CPU/CUDA, delete-heavy, and bound tests.
- Property test that in-flight documents never exceed the configured maximum.

**No production scheduling change.**

### Commit 9: Route existing leasing through the planner

**Change**

- Replace direct `update-batch-size * update-concurrency` lease calculation
  with the planner result.
- With cold mode disabled, produce exactly the existing lease and request
  sequence.

**Tests/checks**

- Golden operation-sequence tests prove default equivalence.
- Multi-chunk symbols cannot exceed request or in-flight document bounds.

**Default behavior remains unchanged.**

### Commit 10: Classify provider backpressure

**Change**

- Treat qualified 429/503 queue saturation as provider backpressure.
- Add cooldown, effective-concurrency reporting, and bounded recovery to one.
- Do not consume document terminal retry attempts.

**Tests/checks**

- Saturation, recovery, repeated saturation, provider death, and poison-
  document distinctions.
- Health transitions clear only after verified success.

**Behavior change is limited to provider saturation.**

### Commit 11: Enable an opt-in cold profile

**Change**

- Honor explicitly enabled cold-profile request and concurrency bounds selected
  by Commit 7.
- Retain the existing single visibility barrier for the whole lease group.
- Report effective profile and reason.

**Tests/checks**

- Bounded concurrent-request test against the fake provider.
- Partial acceptance, timeout, supersession, exact reuse, duplicate cleanup,
  and lease-renewal tests.

**Rollback**

- Disable `:cold-ingestion/:enabled`; no graph or provider rebuild required.

### Commit 12: Qualify cold mode across repositories

**Change**

- Run the disposable project-document matrix on the maintained Clojure,
  ClojureScript, and Janet corpus plus the public clojure-lsp, re-frame, and
  Metabase checkouts.
- Check in aggregate-only benchmark evidence without source or private paths.

**Gate**

- Correctness and recovery gates must all pass.
- At least two representative repositories must meet the relative throughput
  or stage-time gate.
- A regression on small steady-state updates blocks automatic promotion but
  does not block retaining explicit cold mode.

**No code behavior change beyond evidence.**

### Commit 13: Promote or retain explicit cold mode

**Change**

- If Commit 12 passes, enable `:auto` only for newly generated configuration
  and document the release behavior.
- Existing project configuration is not rewritten automatically.
- If the gate fails, retain explicit cold mode or remove it in this commit.

**Tests/checks**

- Configuration migration and old-project behavior tests.
- Release notes identify the exact qualified provider/model/hardware matrix.

### Commit 14: Add finalizer qualification telemetry

**Change**

- Add no finalizer yet.
- Calculate the Gate 2 visibility share and accelerator-idle evidence from
  existing metrics and reports.
- Emit an explicit `recommended`, `not-needed`, or `insufficient-evidence`
  result.

**Gate output**

- Stop here when a finalizer is not justified.

### Commit 15: Extract an in-memory accepted-batch registry

**Conditional on Commit 14.**

**Change**

- Introduce the bounded registry data type and pure capacity operations.
- Do not connect it to the worker yet.

**Tests/checks**

- Symbol, provider-document, age, and duplicate batch-ID bounds.
- Superseded-job removal and deterministic iteration.

### Commit 16: Extract lease renewal for accepted batches

**Conditional on Commit 14.**

**Change**

- Add one lifecycle-owned renewal task for registry jobs.
- Failure to renew removes only lost jobs and publishes a recovery transition.

**Tests/checks**

- Clock-controlled renewal, owner loss, partial supersession, shutdown, and
  provider-stall tests.

### Commit 17: Add the visibility finalizer

**Conditional on Commit 14.**

**Change**

- Poll bounded accepted batches independently of submission.
- Complete visible siblings atomically while isolating superseded jobs.
- Apply bounded exponential visibility polling.

**Tests/checks**

- Immediate, delayed, partial, duplicate, stale, timeout, and metadata-error
  visibility tests.
- No completion before exact chunk-set equality.

### Commit 18: Pipeline submission and finalization

**Conditional on Commit 14.**

**Change**

- Permit new leasing while registry capacity exists.
- Stop submission under backpressure, capacity limits, shutdown, or health
  degradation while allowing finalization to drain.

**Tests/checks**

- Deterministic fake provider proves encoding of a later request overlaps an
  earlier physical update.
- Peak in-flight work never exceeds any configured bound.
- Graceful and forced restart converge through exact inventory.

**Rollback**

- Disable the finalizer; the cold-profile barrier path remains available.

### Commit 19: Add process-boundary fault qualification

**Conditional on Commit 14.**

**Change**

- Kill the worker, JVM, and NextPlaid at pre-submit, partial acceptance,
  accepted, physical-write, visible, and pre-Datalevin-completion boundaries in
  isolated directories.

**Tests/checks**

- Every run ends with exact desired coverage, no duplicate symbol chunks, no
  terminal infrastructure failures, and an inspectable recovery history.

### Commit 20: Run the architecture-fact qualification spike

**Independent of finalizer commits.**

**Change**

- Add neutral source fixtures and a read-only report comparing provable
  Integrant-style component facts with existing graph facts.
- Add the architecture query set and cross-repository aggregate results.
- Do not change graph schema or production analyzers.

**Gate**

- Continue only if component identity, dependency direction, source evidence,
  and at least one bounded query consumer are unambiguous.
- Otherwise record rejection and remove experimental runtime code while keeping
  fixtures/reporting evidence where useful.

### Commit 21: Amend or close architecture enrichment

**Change**

- If the spike passes, add a separate ARD amendment defining exact entities,
  graph format, incremental convergence, export behavior, query commands,
  semantic-document impact, and migration cost before production code.
- If it fails, mark architecture extraction rejected and retain the current
  symbols, edges, topics, and aggregates unchanged.

**No production extraction is authorized by this commit plan alone.**

## Verification strategy

### Focused unit tests

- Planner bounds and hysteresis.
- Explicit symbol/job/chunk/request metric semantics.
- Accepted marker ownership and expiry.
- Backpressure classification and cooldown.
- Finalizer capacity, polling, completion, and supersession if implemented.
- Storage filesystem selection.
- Configuration validation and backward compatibility.

### Provider contract tests

- Packaged NextPlaid version and artifact identity.
- Concurrent raw-text request acceptance.
- Encoding and update queue saturation.
- Background visibility after 202.
- Physical batch behavior for request sizes above and below 300.
- Kill and restart at update stages.
- Duplicate behavior after ambiguous resubmission.

### End-to-end correctness

For every tested profile:

1. desired equals indexed at completion;
2. pending, leased, accepted, failed, and dirty are zero;
3. every indexed record matches exactly one complete provider chunk set;
4. every provider chunk belongs to the current generation and desired symbol;
5. no source, graph, model, or document version changed during the comparison;
6. lexical and semantic retrieval corpus metrics do not regress because the
   indexed document set and text must be identical; and
7. restart during any qualified boundary converges without manual deletion.

### Performance comparison

Compare on the same host, provider artifact, model revision, source commit,
document version, sample IDs, and idle-system policy. Report:

- recent and cumulative symbols/second;
- provider documents/second and text bytes/second;
- p50/p95 request acceptance latency;
- prepare, submit, visibility, and completion share;
- GPU utilization and VRAM high-water mark;
- JVM and provider RSS;
- physical writes and provider-index growth per visible document;
- provider request and physical update counts; and
- backpressure, retry, timeout, and duplicate counts.

The result is invalid if sample membership differs, source changes, the
accelerator falls back, or another run uses a warm provider index while its
comparison uses a cold one.

## Rollout

1. Ship telemetry, storage attribution, and the benchmark harness with no
   scheduling change.
2. Ship planner/configuration with cold mode disabled.
3. Enable cold mode explicitly on qualification projects.
4. Publish aggregate cross-repository evidence.
5. Promote auto-selection only for new configurations if the gate passes.
6. Evaluate the finalizer gate and stop if queue-aware batching is sufficient.
7. If needed, ship the finalizer disabled, qualify faults, then promote it
   independently.
8. Run architecture-fact qualification independently; it cannot block or
   weaken ingestion improvements.

Every release reports the effective profile and selected bounds in verbose
status. An operator can return to `:steady` without rebuilding any state.

## Risks and tradeoffs

| Risk | Mitigation |
| --- | --- |
| Larger request exceeds timeout | Disposable matrix, explicit timeout evidence, bounded candidate values |
| Larger request raises host memory | RSS/VRAM gates and max in-flight provider documents |
| Two requests duplicate model memory | Keep one session and one model worker; concurrency is requests, not models |
| Provider queue fills | Backpressure classification, cooldown, and bounded retry |
| Accepted work is lost on JVM crash | Durable lease/accepted marker, expiry, and exact provider inventory recovery |
| Ambiguous acceptance creates duplicates | Never direct-resubmit; delete-await-submit-verify on non-exact state |
| Larger batch increases retry blast radius | Exact per-symbol inventory reuse and conditional sibling completion |
| Cold profile harms edit latency | Separate steady profile and hysteresis |
| Dynamic tuning makes behavior unpredictable | Pure planner, explicit bounds, no GPU-name heuristics, report effective plan |
| Metrics themselves add allocation or I/O | Constant-space windows and progress publication at bounded intervals |
| Architecture enrichment expands scope | Qualification only; separate amendment required for schema changes |
| More semantic text worsens cold builds | No new semantic projection in this decision |

## Alternatives considered

### Increase CUDA encoder micro-batch immediately

Rejected. The installed provider advertises a larger generic CUDA default, but
the actual full model, 2,048-token document limit, 6 GB GPU, and prior workload
measurement make that unsafe and unproven. The previous micro-batch-two test was
slower.

### Add more CUDA sessions or model workers

Rejected for the qualification host. Each session or worker can duplicate the
model and consume scarce VRAM. Request concurrency can overlap encoding and
index writing without duplicating the model.

### Add only a larger JVM queue

Rejected. It does not guarantee provider coalescing, physical-write reduction,
or stage overlap, and it weakens bounded memory and shutdown behavior.

### Mark jobs complete at HTTP 202

Rejected. NextPlaid performs the physical mutation asynchronously, and direct
resubmission is not a physical upsert. Completion requires exact visibility.

### Persist the NextPlaid queue in Datalevin

Rejected. The durable outbox records desired state, not a duplicate of provider
implementation state. Exact reconciliation bridges the two authorities.

### Use FastPlaid for every cold build

Deferred. It may reduce bulk-build cost, but import compatibility, metadata,
generation, incremental handoff, and fault recovery need a separate provider-
format decision. Queue-aware NextPlaid ingestion is lower risk.

### Switch to the edge model

Rejected as an ingestion optimization. It changes retrieval semantics and
requires a complete quality evaluation and provider generation rebuild.

### Persist the proposed namespace/var maps

Rejected. They duplicate normalized graph facts, lose edge-level provenance,
increase invalidation fan-out, and enlarge the semantic corpus.

### Add generic architecture entities now

Rejected. Framework keys can be repeated across configurations, dependency
references may be dynamic, ownership may belong to a config aggregate rather
than one var, and current edges require symbol ownership. A qualification spike
must establish the contract first.

## Definition of done

The decision is complete when:

1. stage and recent-window telemetry distinguish symbols, chunks, requests,
   acceptance, visibility, and completion;
2. storage status samples the actual semantic-index filesystem;
3. a disposable, source-redacted qualification harness reproduces request and
   concurrency comparisons without touching the live index;
4. the pure planner and default-equivalent integration are tested;
5. provider backpressure is bounded and does not exhaust document retries;
6. an opt-in cold profile passes exact coverage, duplicate, fault, memory,
   storage, and relative throughput gates on the pinned provider;
7. automatic promotion, if any, is backed by checked-in aggregate cross-
   repository evidence and leaves existing configurations unchanged;
8. the finalizer is either rejected as unnecessary with evidence or passes all
   accepted-job, lease, crash, and exact-visibility tests;
9. disabling new scheduling returns to the current steady worker without graph
   or provider rebuild;
10. document text, model, graph facts, and retrieval metrics remain unchanged;
11. architecture-fact qualification produces an explicit go/no-go result; and
12. no production architecture extraction exists without a separately accepted
   schema and retrieval amendment.
