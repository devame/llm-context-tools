# Datalevin access invariants

Date: 2026-08-05

## Decision

Database efficiency is defined by the amount of work performed, not by the
number of public functions or the fact that work is expressed as Datalog.
Focused operations must select through indexed attributes, bound their result
cardinality, and issue a constant number of database operations as the number
of matching entities grows. Bulk operations may be proportional to the graph,
but must use set-oriented projections and bounded transactions rather than
querying or pulling one entity at a time.

Immutable Datalevin database values are safe to read without acquiring the
project graph monitor. The monitor is reserved for graph mutations, semantic
state transitions, and compare-and-swap ownership changes. Filesystem access,
model inference, HTTP calls, and visibility polling must never occur while the
monitor is held.

Traversal must declare its permitted edge kinds and directions, maintain a
visited set, use deterministic ordering, and enforce explicit depth and result
limits. A recursive Datalog rule is not considered bounded merely because it
is one query.

## Production access audit

The audit covers every direct `d/q`, `d/entity`, `d/pull`, `d/pull-many`, and
`store/query` call below `src/llm_context`. The disposition is by owning
operation; helper calls inherit the disposition of their caller.

| Subsystem and operations | Classification | Required behavior |
|---|---|---|
| `graph.read`: entity/group counts, file lookup, exact symbol lookup, batched symbol/topic lookup, references/effects for selected symbols | Indexed, fixed-query, or frontier-bounded | Retain set-oriented queries and `pull-many`; callers must supply bounds for focused candidate queries |
| `graph.read/adjacent-exact` | Indexed and frontier-bounded | Reverse-reference reads only; no graph monitor and no per-node Datalog joins |
| `graph.read`: entry points and bounded summary samples | Intentionally proportional set query | Retain database anti-join; benchmark separately from focused traversal |
| `graph.read/semantic-counts` | Fixed aggregate set | Consolidate related aggregates, but do not replace them with a Cartesian aggregate |
| `query`: exact/FTS search, callers, callees, unresolved, topics | Indexed and result-bounded | Keep selection and limits in Datalevin; bounded hydration only |
| `query/symbol-suggestions` and compatibility substring lookup | Correction required | Generate an indexed bounded candidate pool before application predicates |
| `query/transitive-callees` | Correction required | Call-only, cycle-safe breadth-first traversal with depth/result bounds |
| `context` traversal and packet projections | Frontier and token bounded | Reads remain lock-free; each frontier is selected through exact reverse references |
| `store`: relationship validation | Fixed number of indexed membership queries | Retain batched identity validation |
| `store`: incremental replacement planning, stale attributes, owned-identity recovery, dirty markers | Correction required | Preload identity/eid/attribute state once; planning loops perform no database calls |
| `store`: full replacement batches and legacy search backfill | Intentionally proportional migration/bulk work | Bounded transactions with progress; no per-row reads |
| `semantic.document`: graph revision | Intentionally proportional deterministic projection | Two set projections are acceptable because the contract is a whole-graph revision |
| `semantic.document`: indexable symbols and file relationships | Correction required | Select format-3 indexability in Datalevin and fetch relationships once per requested set |
| `semantic.reconcile`: dirty-file planning and job changes | Correction required | Plan from one immutable snapshot and apply one state transaction per file |
| `semantic.state`: marker/job/indexed/watermark point operations | Indexed point operations | Retain single-record semantics |
| `semantic.state`: lease, renew, complete, retry, expired-lease recovery | Concurrency-sensitive | Preserve per-job CAS ownership; remove only redundant result pulls |
| `semantic.state`: record listings and failure/dirty details | Intentionally proportional operator views | Query IDs once and hydrate with `pull-many` |
| `semantic.hybrid` | Bounded candidate hydration | Candidate count is the cardinality limit |
| `export` | Intentionally proportional whole-graph operation | Query entity IDs by type and hydrate in bounded groups; never use it for focused reads |
| `runtime.doctor` and graph metadata/state probes | Indexed or existence probes | Constant point/existence queries |

## Regression policy

Tests instrument Datalevin at the public function vars and count queries,
entity reads, pulls, `pull-many` calls, and transactions. High-cardinality
fixtures compare a small and large input and assert constant operation counts
for focused or batched behavior. Wall-clock timings remain descriptive because
JVM warmup, filesystem placement, and host load make them unsuitable as stable
CI gates.

Any new production database access must be added to this audit and classified.
Exceptions require an explicit whole-graph or concurrency contract and a
bounded-output or bounded-batch test.
