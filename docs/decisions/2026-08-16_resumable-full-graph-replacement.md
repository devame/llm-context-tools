# Resumable full graph replacement

Status: Accepted

Date: 2026-08-16

## Context

Full analysis previously replaced the canonical graph by submitting
`:db/retractEntity` for every existing canonical entity, followed by assertions
for the complete new snapshot. Both phases used batches measured in entities.

At Metabase scale, the old graph contained 884,599 canonical entities.
Datalevin expands `retractEntity` into the entity's datoms and index updates
inside the native transaction, so a 1,000-entity application batch did not
bound the actual LMDB transaction. During the 104th deletion batch, LMDB
aborted the JVM at `mdb_page_dirty` (`rc == 0`). The transaction itself rolled
back and the graph remained explicitly unavailable, but 103 earlier batches
had committed. Retrying would repeat the same high-risk erase-first strategy.

The failure was not caused by capacity or filesystem placement: the graph was
on native ext4 with ample free space, reopened successfully, and retained a
consistent 781,599-entity fail-closed snapshot.

## Decision

Complete canonical snapshots converge by stable canonical identity instead of
erasing the old graph first.

1. Validate the complete candidate before mutation.
2. Persist the unavailable marker before the first mutation.
3. Dependency-order the candidate and upsert it in bounded batches. Bound both
   entity count and estimated transaction weight. The weight includes derived
   cardinality-many search grams and the size of full-text documents, because
   one symbol can expand into far more native index work than one simple edge.
4. For retained identities, preserve the Datalevin entity ID and explicitly
   retract attributes omitted by the new canonical entity, including obsolete
   full-text character grams.
5. After every desired identity has landed, find canonical identities absent
   from the candidate.
6. Remove only those stale identities. Read their concrete datoms and submit
   small batches of explicit `:db/retract` operations; do not use
   `:db/retractEntity` for whole-graph cleanup.
7. Preserve operational semantic entities, which intentionally have no
   `:entity/type`.
8. Reset or reconcile semantic state according to the existing compatibility
   contract, then activate graph metadata last.

## Recovery invariant

Every committed upsert batch is idempotent. If the process stops after any
batch, the graph remains unavailable and the next full analysis may repeat the
same complete candidate:

- desired identities already written are updated in place;
- desired identities not yet written are restored;
- stale identities remain harmless while metadata is unavailable;
- stale cleanup starts only after all desired identities exist;
- activation occurs only after both phases finish.

The durable analysis progress file labels a previous `:running` operation as
interrupted when either a local process or resident service restarts.

Databases interrupted by the former erase-first implementation have an
`update-in-progress` marker but no replacement-strategy marker. Their
partially erased secondary indexes are not used as convergence inputs. On the
next full analysis or resident-service startup, llm-context closes the
database, atomically moves its directory into a sibling `recovery/` archive,
reports the archive path, and rebuilds the configured database path from
scratch. The archive is retained for inspection; recovery never destroys it.

Every replacement started by this decision records
`identity-convergence-v1`. If one of those replacements is interrupted, the
database remains at its configured path and the next full analysis resumes the
idempotent convergence algorithm in place. This makes archival a one-time
migration boundary rather than a recurring response to ordinary interruption.

## Consequences

- Full replacement no longer creates a database-wide deletion storm.
- `:store/:max-transaction-weight` is configurable and defaults to `4000`;
  lowering it trades throughput for smaller native transactions.
- A partially deleted graph from the previous algorithm is archived
  automatically and rebuilt; no manual deletion of the database is required.
- Stable entity IDs reduce index churn and preserve compatible semantic
  operational state.
- Replacement performs identity and retained-attribute lookups per batch and a
  final canonical-identity scan. This is additional read work, accepted in
  exchange for restart safety and much lower write amplification.
- The graph remains fail-closed during replacement; queries never observe a
  mixed revision.

## Rejected alternatives

### Retry erase-first with a smaller entity batch

This reduces probability but does not bound native work because entity datom
counts vary. It also preserves the highest-write-amplification path.

### Delete the generated database and rebuild

This can recover one project but discards the previous graph and compatible
semantic state. It does not repair the general failure mode.

### Build and atomically swap a second Datalevin directory

This gives strong isolation but requires connection handoff, cross-platform
directory activation, semantic-state migration, and orphan-staging cleanup.
It remains a future option if identity convergence proves insufficient; it is
not required to make committed batches safely repeatable.

## Validation requirements

- interruption after a committed upsert batch converges on retry;
- retained identities keep their entity IDs and lose obsolete attributes;
- stale identities are removed only after desired upserts complete;
- cleanup emits explicit datom retractions in bounded batches and never
  `retractEntity`;
- semantic operational entities survive full replacement;
- graph metadata remains unavailable until final activation;
- legacy interrupted databases are moved intact and rebuilt from an empty
  configured path;
- current-strategy interrupted databases remain in place for retry;
- the complete automated suite passes before resuming the Metabase graph.
