# Asynchronous semantic indexing

## Status

This document defines the implementation contract for LateOn-Code retrieval.
It is intentionally narrower than the semantic graph model: LateOn improves
code discovery but does not infer graph entities or replace structural and SCIP
analysis.

## Ownership

Datalevin is authoritative for files, symbols, relationships, effects, content
hashes, and semantic freshness. NextPlaid is a derived, disposable index of
versioned symbol documents. JSON, JSONL, EDN, Markdown, and the NextPlaid index
are projections; none of them can make a stale symbol current.

Project state lives below `.llm-context/`:

```text
.llm-context/
  db/                         authoritative Datalevin database
  semantic/next-plaid/        derived LateOn index
  logs/                       coordinator and sidecar logs
  service.edn                 authenticated local service descriptor
```

NextPlaid API and ONNX Runtime are installed with the user-level launcher. The
immutable model files use a per-user cache. Source-derived graph and index
state remains project-local.

## Change flow

```text
file event or analyze command
             |
             v
structural analysis and edge reconciliation
             |
             v
Datalevin graph transaction + durable dirty marker
             |
             v
semantic reconciler creates coalesced jobs
             |
             v
resident worker encodes and updates NextPlaid
             |
             v
worker records indexed hash and freshness watermark
```

Structural analysis does not wait for model inference. A service outage can
increase semantic lag but cannot make graph analysis unavailable.

The filesystem watcher is only a change trigger. It delegates to the same
content-hash incremental analyzer used by `llm-context analyze`; it does not
parse or embed files independently.

## Semantic document identity

A semantic document is derived from one graph symbol and includes its language,
kind, names, signature, documentation, path, relationships, and source body.
Large symbols produce deterministic chunks that repeat identifying metadata.

The document hash covers:

1. the canonical document text;
2. the semantic document format version;
3. the model name and immutable model revision.

A model or document-format change therefore schedules a rebuild without a
persisted-data migration.

Each indexed chunk carries at least:

```clojure
{:symbol-id "symbol:..."
 :file-id "file:..."
 :document-hash "sha256:..."
 :model-revision "734b659a57935ef50562d79581c3ff1f8d825c93"
 :document-version 1
 :chunk-index 0}
```

## Durable state machine

Graph mutations atomically mark affected files dirty. A reconciler compares
the desired graph documents with recorded indexed state and coalesces work by
provider and symbol:

```text
dirty -> pending -> leased -> indexed
                    |   |
                    |   +-> retry -> pending
                    |
                    +-> failed

indexed symbol removed -> delete pending -> deleted
```

Leases expire so a terminated worker cannot strand work. Job completion is
conditional on the expected document hash: an old worker may finish expensive
encoding, but it cannot mark a newer document current. Replaying an upsert or
delete is safe.

A full graph replacement records a project-wide reconciliation marker before
bounded graph transactions begin. If analysis stops midway, the marker remains
and the next analysis or service start repairs the semantic plan.

## Query consistency

Hybrid search obtains candidates from Datalevin full-text search and the
resident LateOn service. It over-fetches semantic candidates, then validates
every candidate against current Datalevin state:

- the symbol must still exist;
- the document hash must match;
- the model revision must match;
- the document format version must match.

Invalid candidates are discarded before ranking or hydration. Multiple chunks
collapse to their symbol. Exact identifier matches retain priority and the
remaining lexical and semantic ranks are combined deterministically.

If NextPlaid is unavailable, loading, unhealthy, or exceeds its query timeout,
the command returns Datalevin results. Semantic retrieval is an enhancement,
not a command availability dependency.

## Process model

The first implementation uses one project-scoped `llm-context` coordinator and
one supervised NextPlaid child process. The coordinator serializes Datalevin
writes while permitting queries against immutable database values. It owns:

- submitted and watcher-triggered analysis;
- semantic reconciliation and job leasing;
- NextPlaid health and lifecycle;
- semantic freshness status.

Keeping the service project-scoped matches the existing authenticated
`.llm-context/service.edn` boundary and keeps derived data beside its project.
A future user-level multiplexer may share one loaded model across projects, but
must not change project data ownership or freshness rules.

## Failure behavior

- NextPlaid unavailable: retain pending jobs and use Datalevin search.
- Model/index revision mismatch: report degraded state and schedule rebuild.
- Source changed after graph analysis: do not index mismatched source; trigger
  incremental analysis again.
- Worker termination: recover expired leases and replay idempotently.
- Index corruption: preserve Datalevin, retain the corrupt index for diagnosis,
  and rebuild only through an explicit command.
- Queue poison record: retry with bounded exponential backoff, then mark failed
  without blocking other records.

Logs contain identifiers, timings, counts, and bounded error messages. They do
not contain complete source documents, service tokens, or model request bodies.

## Defaults and resource profile

The default model is `lightonai/LateOn-Code` at immutable revision
`734b659a57935ef50562d79581c3ff1f8d825c93`, using its INT8 ONNX artifact. The
default retrieval and ingestion settings reflect the repository benchmark:

- pool factor 2;
- one encoding session and batch size 1;
- update batches of 10 documents;
- 8 IVF probes;
- centroid score threshold disabled;
- 4096 full-score candidates;
- 50 candidates before Datalevin freshness filtering;
- 350 ms query timeout.

Constrained users may explicitly select the edge model later. Automatic model
selection is not part of the initial implementation because it would make
retrieval behavior dependent on implicit hardware detection.

## Non-goals

- generating structural graph edges with a language model;
- remote inference or uploading source code;
- distributed queues or multiple semantic writers;
- using LateOn embeddings as Datalevin vector values;
- silently truncating every code unit to a fixed 512-token limit;
- treating semantic similarity as compiler-accurate evidence.
