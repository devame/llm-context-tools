# Provider-native recovery and indexing

Status: Accepted for phased implementation

Date: 2026-08-16

## Context

llm-context maintains two different kinds of durable state:

1. Datalevin stores the authoritative canonical code graph, graph metadata,
   and the durable semantic-work ledger.
2. NextPlaid stores a derived semantic search index whose vectors are produced
   by the configured LateOn-compatible encoder.

Recent Metabase-scale work exposed failures that small repositories do not:
large write amplification, native-process termination during a graph update,
stale resident-service state, interrupted semantic synchronization, and disk
growth that was difficult to attribute while the operation was running.

The first response to those failures added application-level safety: bounded
transactions, an unavailable graph marker, identity-convergent full
replacement, a durable semantic queue, storage headroom checks, stale-service
cleanup, and visible progress. Those protections remain valuable because they
express llm-context's domain contract. They must not, however, become a second
database, a second vector-index implementation, or a second process
supervisor.

This decision separates domain invariants from facilities already supplied by
Datalevin, NextPlaid, and the operating system. Provider features are adopted
only after their exact pinned versions pass fault-oriented qualification. A
feature documented by a newer provider release is evidence for a spike, not a
contract of the installed release.

## Problem statement

The current pipeline has six avoidable sources of cost and fragility:

- Full analysis parses every file and constructs every canonical entity before
  persistence can resume. Datalevin upserts then rewrite every desired entity,
  even when most of the graph is already identical.
- Application batches bound submitted entities and estimated values, but they
  cannot observe every LMDB page operation performed internally.
- The semantic worker correctly keeps a durable desired-work ledger, while
  also implementing batching, retries, and reconciliation around a provider
  that has its own asynchronous queue and physical repair behavior. The exact
  boundary is not yet qualified.
- Storage checks report free space but do not attribute graph, semantic index,
  staging, recovery, model-cache, or log growth to a phase.
- The resident service can recover stale descriptors, but lifecycle policy is
  still partly embedded in application code rather than delegated to a host
  supervisor when one is available.
- Recovery behavior has been tested mainly at Clojure process boundaries. It
  needs provider-level crash tests at accepted transaction and update points.

## First principles

### One authority per fact

Datalevin is authoritative for canonical graph facts and for the statement
that a semantic document should exist. NextPlaid is authoritative only for the
physical vector index it serves. The encoder is a pure transformation from a
versioned document to an embedding. The operating system is authoritative for
whether a long-lived process should be restarted.

### Rebuildable does not mean disposable during an operation

The semantic index is derived and may be rebuilt. The canonical graph is also
derivable from source, but a multi-hour rebuild still requires fail-closed
visibility, bounded resource use, and deterministic resumption.

### Provider guarantees need version-specific proof

The repository pins Datalevin 1.0.0 and NextPlaid 1.6.4. Public documentation
for later releases describes useful facilities, but llm-context will not rely
on them until an executable capability test passes against the packaged
binary or library version.

### Avoiding work is better than accelerating redundant work

The preferred optimization order is:

1. do not parse unchanged files;
2. do not submit unchanged graph entities;
3. do not encode unchanged semantic documents;
4. batch the remaining work using provider-supported mechanisms;
5. accelerate only the measured remaining bottleneck.

### Recovery must be idempotent and inspectable

After interruption, replaying the same desired state must converge. Every
recovery decision must be visible through durable status, including what is
being resumed, rebuilt, archived, compacted, or rejected.

## Ownership boundary

| Concern | Owner | llm-context responsibility |
| --- | --- | --- |
| Canonical graph transactions and indexes | Datalevin | Validate canonical input, choose bounded transaction units, preserve fail-closed activation |
| Canonical identity and graph revision | llm-context | Define stable identities, compatibility metadata, and activation order |
| Transaction atomicity | Datalevin | Never emulate rollback; use transaction reports and retry only whole idempotent units |
| Graph backup/compaction | Datalevin | Invoke supported copy/compact facilities and retain policy metadata |
| Semantic desired state | llm-context in Datalevin | Record deterministic document identity, hash, generation, status, and lease |
| Vector batching and physical index mutation | NextPlaid | Respect backpressure, observe accepted work, and verify eventual visibility |
| Vector physical consistency | NextPlaid | Trigger or trust only qualified provider repair; reconcile logical coverage afterward |
| Embedding calculation | Configured encoder | Supply versioned text and verify model package identity |
| Process restart | Host supervisor | Generate integration/configuration and keep in-process cleanup idempotent |
| Cross-provider completion | llm-context | Activate only when graph and semantic generation contracts are satisfied |

The durable semantic ledger is not duplicated NextPlaid queue state. It is the
cross-provider outbox that records llm-context's desired document generation.
It survives a provider losing accepted-but-not-yet-materialized work and lets
reconciliation prove complete coverage.

## Provider facilities and qualification status

### Datalevin 1.0.0

Already relied upon:

- ACID transactions;
- unique identity attributes and lookup references;
- immutable database values for reads;
- durable `transact!` behavior;
- indexed AVE scans and pull/query APIs.

Candidates requiring a pinned-version spike:

- `transact-async` for adaptive write batching;
- transaction reports as the source of committed database state;
- `copy` with compaction for online maintenance and recoverable backups;
- WAL snapshots, retention, and transaction-log garbage collection;
- `init-db` or `fill-db` for a validated shadow-database bulk build.

WAL is not enabled by default merely because it exists. Current provider
documentation describes public WAL operations over a KV handle, and default
retention can itself consume substantial disk. It is accepted only if a
Datalog-store crash/restore test proves the exact API, retention can be
bounded, and the result preserves llm-context metadata and indexes.

Long-lived Datalevin read values are treated as a storage hazard. LMDB cannot
reuse pages still visible to an old reader, so code must not retain immutable
database snapshots across file parsing, model inference, HTTP calls, sleeps,
or progress watches.

### NextPlaid 1.6.4

Already relied upon:

- asynchronous document mutation;
- metadata inventory and visibility checks;
- generation sentinel documents;
- deterministic document identifiers stored in provider metadata.

Candidates requiring a packaged-binary spike:

- provider-native queue batching limits and accepted-response semantics;
- backpressure and retry behavior when the queue is full;
- atomic update/reload during concurrent reads;
- automatic vector/metadata repair after a killed update;
- health and per-index metadata sufficient for startup qualification;
- whether a bulk-only FastPlaid build can be imported without weakening
  incremental NextPlaid semantics.

An HTTP 202 is not completion. llm-context retains the lease until the provider
accepts the request, then verifies document visibility and generation before
marking the durable job complete. Pinned-binary qualification proved that a
direct re-submit is not idempotent: the metadata chunk ID is not a physical
provider upsert key. After an ambiguous acceptance or restart, reconciliation
must delete all chunks for the affected symbol, await absence, submit the
desired chunks, and verify exact visible metadata before completing the job.

### Host supervisor

systemd, launchd, Windows Task Scheduler/service hosting, and container restart
policies are preferred for automatic process restart and log rotation. The CLI
continues to clean stale descriptors and sockets because those are
project-specific rendezvous artifacts, but it does not implement an unbounded
generic restart loop.

## Decision

Implementation proceeds through independently releasable gates.

### Phase 0: capability qualification harness

Add executable, version-reporting provider checks that run in isolated
temporary directories and never against a user index. Each check reports
`supported`, `unsupported`, or `failed`, plus the provider artifact identity.

Datalevin checks:

- synchronous and asynchronous transaction durability;
- transaction-report shape;
- copy/open/query equivalence and compact-copy size;
- crash after commit and crash before commit;
- optional WAL snapshot/restore/retention only if the Datalog connection can
  use the public contract safely.

NextPlaid checks:

- accepted update followed by visibility;
- bounded queue saturation and 503/backpressure behavior;
- kill during update, restart, provider repair, and visibility reconciliation;
- measure whether direct re-submit of the same document identifier is
  idempotent;
- generation-sentinel survival and stale-document deletion.

No production path switches to an optional facility merely because the check
exists. A checked-in compatibility matrix records the exact qualified
versions and test date.

### Phase 1: eliminate redundant canonical writes

Before each full-replacement batch, compare each desired, derived canonical
entity with its currently stored datoms. Reference EIDs are normalized back to
their canonical identity values, and cardinality-many values are compared as
sets. Exact matches are omitted from the Datalevin transaction.

The comparison is schema-driven; it contains no Metabase names or language-
specific concepts. Changed entities still use stable identity upserts and
explicit stale-attribute retractions. Empty batches do not invoke Datalevin.

Progress distinguishes examined, written, skipped-unchanged, and retracted
entities. The optimization is rejected if a second identical replacement does
not produce zero canonical upsert transactions or if it changes the resulting
datom set.

### Phase 2: provider-native graph maintenance

Add an explicit maintenance command that creates a Datalevin compact copy in a
sibling staging directory, verifies graph metadata and representative counts,
then leaves activation to a separately tested atomic handoff. The first release
ships copy-and-verify only; it does not replace a live database automatically.

Evaluate `transact-async` with real graph distributions. Adopt it only if it
improves wall-clock throughput without increasing peak disk, RSS, recovery
time, or transaction ambiguity. A faster microbenchmark alone is insufficient.

The copy-and-verify portion is implemented as
`llm-context maintenance compact-copy [--output PATH]`. It executes through the
resident graph owner when present, uses Datalevin's qualified compact-copy
primitive, opens the result with the pinned schema, and compares graph metadata
plus canonical and semantic operational identity counts. The destination must
be empty and separate from the live database. Activation and deletion remain
outside this command, so a maintenance failure cannot replace the live graph or
erase diagnostic evidence. Async transaction adoption remains a separate
measured gate.

### Phase 3: simplify semantic ingestion around provider behavior

Keep the Datalevin outbox, deterministic document IDs, leases, generation
sentinel, and final coverage proof. Remove application-level batching or repair
logic only where the exact NextPlaid version demonstrates equivalent behavior.

The worker submits bounded groups according to provider limits, honors 503 as
backpressure rather than failure, and records separately:

- waiting in the durable outbox;
- leased by a worker;
- accepted by NextPlaid;
- visible in NextPlaid;
- failed or retryable.

The accepted boundary is persisted as `:semantic.job/accepted-at` only after
every provider request for the leased batch returns successfully. Status counts
accepted jobs separately while they await exact metadata visibility. Lease
expiry, retry, or supersession retracts the marker; it never implies durable
provider completion. A partial or ambiguous provider submission remains an
ordinary leased job and is recovered through delete-await-submit-verify.

GPU acceleration remains an encoder concern. It does not change queue,
generation, or visibility semantics.

### Phase 4: host-native supervision and cleanup

Provide generated supervisor examples with restart backoff, resource limits,
and log rotation. On startup, llm-context validates descriptor ownership,
process identity, endpoint reachability, active operation state, and child
provider identity before removing stale artifacts or adopting a process.

Cleanup is allowlisted and project-scoped. Recovery archives, staging copies,
logs, and semantic generations expose age and size. Automatic deletion occurs
only under an explicit retention policy and never removes the only verified
generation or the newest interrupted archive.

The read-only inventory slice is implemented as `llm-context maintenance
status`. It measures only explicit project-owned graph, provider-index,
staging, recovery, maintenance, and log locations, does not follow symbolic
links, and performs no cleanup. Retention and deletion remain a separate
explicit-policy slice.

The retention slice is implemented as `llm-context maintenance cleanup
--older-than-days DAYS [--apply]`. Planning is the default. Application is
restricted to directly nested recovery archives and verified compact copies
with exact llm-context markers, and preserves the newest artifact in each
category. Provider indexes, active logs, symbolic links, and unmarked paths
are outside the deletion allowlist.

Supervisor generation is implemented for systemd, launchd, and Windows Task
Scheduler. Definitions are project-specific and include restart throttling,
single-instance behavior, bounded task/file-descriptor controls where the host
supports them, and host-managed logging. llm-context only renders the artifact;
installation and activation remain explicit administrator actions.

### Phase 5: resumable analysis before persistence

Persist analyzer outputs per file under an immutable staging generation keyed
by:

- normalized repository-relative path;
- source content hash;
- analyzer name and version;
- analyzer configuration fingerprint;
- canonical graph format.

On restart, matching files are reused and only missing or incompatible files
are parsed. Finalization builds project-wide exact relationships from the
complete staged file set, validates the candidate, and then invokes the
existing identity-convergent persistence. Partial staging data is never
queryable as the active graph.

This phase is intentionally later: it adds a new durable format owned by
llm-context, whereas redundant-write elimination and provider batching reuse
existing contracts.

The staging storage slice uses immutable, content-addressed generation
directories and compressed per-file output shards. It publishes `index.edn`
last, fails closed on missing or invalid shards, never follows an index-provided
path outside the generation, and enforces a configurable per-generation byte
limit. Integration remains a separate slice so the storage contract can be
qualified independently.

The preparation integration keys reuse to the complete source inventory and
application/analyzer/canonicalization contract. A complete match skips analyzer
execution and refreshes only non-semantic file metadata before the ordinary
whole-project validation and persistence path. A partial generation or any
contract/content mismatch runs the authoritative analyzers again; no mixture of
old and new shards can reach Datalevin.

Explicit retention now also recognizes exact content-addressed staging
generation directories, including incomplete generations. It preserves the
newest generation and applies the same age-gated dry-run-first cleanup. This
prevents resumability from becoming an unbounded second store while retaining
the latest interrupted work for diagnosis or retry.

### Phase 6: optional shadow bulk build

Only after qualification, a brand-new graph may be built with Datalevin's bulk
loader in a staging directory. Input must already be fully validated and
dependency ordered because bulk facilities may bypass integrity and WAL work.
The staged database is opened normally, checked for graph invariants, and
activated atomically. Incremental and recovery paths continue to use ordinary
transactions.

Pinned Datalevin 1.0.0 qualification proves that `init-db` can build, reopen,
type-validate, and query a shadow database containing raw numeric datoms and
exact reference relationships. Production adoption is rejected for this
release. The provider contract requires trusted pre-resolved entity numbers,
while llm-context's authoritative candidate uses transaction maps, unique
identities, and lookup references. Converting it would duplicate Datalevin's
transaction identity/reference resolution and create a second correctness
path. Ordinary bounded transactions already converge after interruption, so
the unmeasured speed opportunity does not justify that semantic risk.

## Disk and latency observability

Every long-running phase records a durable sample at a bounded interval:

- timestamp and operation generation;
- phase and completed/total units;
- elapsed time and throughput;
- filesystem usable bytes;
- byte sizes for canonical database, semantic index, analyzer staging,
  recovery archives, model cache, and logs;
- bytes grown since operation start and since the previous sample;
- Datalevin entities examined, written, skipped, and retracted;
- semantic jobs pending, leased, accepted, visible, retryable, and failed;
- provider process PID, exit state, and artifact identity.

Sampling must not retain a Datalevin database value. Directory measurement is
rate-limited and performed outside graph transactions. Safety limits are
evaluated before a write unit and include both minimum free space and maximum
operation growth. Crossing a limit stops before the next unit, records the
reason durably, releases renewable leases, and leaves state resumable.

The operation-growth guard is implemented for graph replacement transactions
and semantic provider batches. It checks filesystem headroom before each write,
rate-limits recursive size sampling, and compares graph/recovery or semantic
index growth with the configurable 32 GiB default. The limit is evaluated
before the next unit, so existing identity convergence and lease expiry remain
the recovery mechanisms.

Sampled component sizes, per-component growth, aggregate operation growth,
free space, and the configured cap are attached to the durable analysis
progress record and to live semantic-worker progress. Checks between sampling
intervals still enforce the cheap filesystem reserve without recursively
walking generated directories.

## Failure model

| Failure point | Required outcome |
| --- | --- |
| Before a Datalevin transaction | No graph mutation; same unit may retry |
| After a committed Datalevin transaction | Transaction report/progress may lag, but identity comparison skips the committed state on retry |
| During stale cleanup | Explicit datom retractions already committed remain valid; remaining stale datoms retry |
| Encoder process dies | Durable semantic job lease expires and another worker retries |
| NextPlaid dies before accepting | Job remains retryable |
| NextPlaid dies after accepting but before acknowledgment | The durable job retries delete-await-submit-verify reconciliation; direct re-submit is forbidden because it creates duplicates in 1.6.4 |
| NextPlaid restarts with physical inconsistency | Qualified provider repair runs, then llm-context proves logical generation coverage |
| Resident service dies | Host supervisor restarts it; startup classifies and cleans only stale project-owned artifacts |
| Disk safety limit trips | Operation stops before another write and reports measured component growth |
| Analyzer staging is partial | Matching staged files resume; active graph remains unavailable until complete validation and activation |

## Configuration contract

Provider-specific options remain namespaced and validated at startup. Planned
settings include:

- `:store/:write-mode` with qualified values `:sync` and `:async`;
- `:store/:max-transaction-weight`;
- `:store/:maintenance-copy-directory`;
- `:store/:minimum-free-space-bytes`;
- `:store/:maximum-operation-growth-bytes`;
- `:store/:sample-interval-ms`;
- `:semantic/:provider-queue-limit` and retry/backoff controls;
- `:analysis/:staging-directory` and retention controls;
- supervisor generation options, without silently installing a service.

Defaults preserve the currently qualified synchronous Datalevin path and
durable semantic worker. Experimental settings fail closed when their
capability is not qualified.

## Validation gates

Each phase must pass:

1. focused unit tests for normalization, batching, and status reporting;
2. property tests showing replay converges to the same canonical datoms;
3. child-process kill tests at provider boundaries;
4. storage-growth measurements on native ext4;
5. complete Metabase analysis with no available mixed graph;
6. semantic coverage of all desired documents with zero pending, leased, or
   failed jobs and a valid generation watermark;
7. repeated public retrieval evaluation to ensure throughput work does not
   reduce answer quality;
8. a clean shutdown with no project-owned orphan provider process.

Promotion criteria are outcome-based. A performance optimization must improve
the measured target while preserving peak disk, peak RSS, complete coverage,
and recovery invariants. Provider-native behavior replaces application logic
only after fault injection demonstrates equivalent or stronger guarantees.

## Initial implementation slice

The first implementation in this decision is Phase 1:

- schema-driven exact comparison of desired and stored canonical entities;
- omission of unchanged entities from full-replacement transactions;
- no Datalevin call for an empty write batch;
- progress counters for examined, written, and skipped entities;
- regression tests for identical replay, changed references, removed
  attributes, and interrupted replay.

This slice has high leverage and low provider risk. It uses Datalevin's indexed
identity lookups and ACID transaction boundary, reduces LMDB work before any
batching experiment, and does not alter graph semantics or the semantic
provider contract.

Phase 0 also begins with `clojure -M:qualify-providers`. The initial harness
proves synchronous transaction reports, asynchronous commit, and compact-copy
round trips against the pinned Datalevin artifact in an isolated temporary
database. It also abruptly halts child JVMs before and immediately after a
synchronous commit and proves on reopen that only the acknowledged transaction
survives. `clojure -M:qualify-providers --next-plaid` additionally exercises
the packaged NextPlaid binary and verified model through a real child-process
crash, restart, direct-resubmission measurement, delete-then-submit
reconciliation, and a second clean restart.

Pinned NextPlaid 1.6.4 qualification on 2026-08-16 used binary SHA-256
`0eb7ce59c063b79d51564623c05fa96674ba3ed0dbf193f2b7ba7919603b3b76`
and the verified LateOn revision declared by this release. The accepted update
had zero documents visible immediately after forced termination, showing that
the provider's accepted queue is not a durable completion record. The index
opened after restart, the sentinel survived, direct resubmission created
duplicates, and delete-then-submit reconciliation converged to exactly one
visible desired document per symbol. Therefore the Datalevin outbox and the
worker's delete-await-submit-verify sequence remain required; they are not
duplicate provider functionality.

## Rejected alternatives

### Reimplement a WAL in application files

Rejected. Datalevin owns transaction durability. llm-context needs an operation
state machine and semantic outbox, not a second record-level database log.

### Treat NextPlaid's in-memory queue as the durable source of truth

Rejected. Provider acceptance is asynchronous and does not encode the desired
graph/model/document generation contract.

### Turn on every provider performance option by default

Rejected. Async writes, WAL retention, large batches, model pools, and bulk
loaders can increase disk, memory, or failure ambiguity. Each needs a measured
pinned-version qualification.

### Resume by exposing a partially rebuilt graph

Rejected. Query correctness is more important than partial availability. The
active graph remains fail-closed until complete validation and activation.

### Build repository-specific shortcuts

Rejected. Identity comparison, staging keys, progress, provider capabilities,
and recovery states are defined from schema and provider contracts. No phase
contains Metabase-specific names, paths, languages, or query rules; Metabase is
the scale qualification corpus, not the implementation contract.

## Consequences

- Recovery logic becomes smaller where providers already supply the primitive,
  while llm-context retains cross-provider and domain-specific guarantees.
- Exact provider versions and qualification evidence become part of release
  readiness.
- Repeated full analysis performs additional bounded reads but dramatically
  fewer writes when most entities are unchanged.
- Some advanced features remain deliberately unavailable until fault tests
  pass; this is safer than assuming documentation for another version applies.
- Analyzer-stage resumption remains substantial future work, but its contract
  is now separated from database transaction recovery.
