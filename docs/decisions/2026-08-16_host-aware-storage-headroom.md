# Host-aware storage headroom for generated indexes

Status: Accepted

Date: 2026-08-16

## Context

Graph replacement, analyzer caches, semantic indexes, model snapshots, and
recovery archives can coexist while a large repository is rebuilt. Checking
only the database filesystem is insufficient on thin-provisioned systems. In
particular, WSL reports the logical maximum of its ext4 VHDX even when the
Windows volume containing that VHDX has much less physical space.

LMDB and semantic-index writers perform durable work in many batches. A check
only at command startup cannot protect against another process consuming space
or an unexpectedly large index expansion during a long run.

## Decision

llm-context has one generated-storage safety contract:

- `:store/:minimum-free-space-bytes` is the hard reserve, defaulting to 10 GiB;
- `:store/:free-space-probe-path` selects the capacity-bearing filesystem;
- an explicit probe path may be absolute or project-relative;
- absent an override, native filesystems probe the nearest existing ancestor
  of the configured database path;
- absent an override under WSL, `/mnt/c` is probed because that is the usual
  backing volume and is safer than trusting the VHDX logical ceiling;
- WSL installations whose VHDX resides on another drive must configure that
  mounted Windows volume explicitly.

Full analysis checks headroom before parsing and before every canonical graph
transaction. The semantic worker checks before every leased ingestion batch.
Crossing the reserve raises a typed `:store/insufficient-space` error carrying
the operation, probe path, usable bytes, and required bytes.

The graph replacement marker remains unavailable when the guard stops a full
write. New replacement batches are idempotent, so a later run resumes safely.
Semantic jobs are durable and remain retryable when ingestion stops.

## Consequences

- Linux-reported VHDX capacity is no longer mistaken for host capacity on WSL.
- The reserve is enforceable throughout long-running writes, not merely at
  startup.
- Users can raise the reserve for large repositories or select another backing
  volume without changing code.
- The default is deliberately conservative and may stop otherwise successful
  writes on small disks. Setting the reserve to zero is an explicit opt-out.
- Capacity checks do not predict final index size. They bound damage by
  preserving a fixed amount of usable host storage as the index grows.

## Rejected alternatives

### Trust `FileStore` for the database path everywhere

This reports the VHDX logical limit under WSL and caused a false impression of
roughly 903 GiB available while the Windows host had roughly 95 GiB free.

### Check only before starting analysis

A startup check cannot react to concurrent disk use or write amplification
during thousands of persistence batches.

### Automatically delete recovery archives

Deletion would make failure evidence and the previous graph unrecoverable.
Archive retention remains an explicit operator decision.
