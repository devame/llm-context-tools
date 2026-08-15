# Architecture and tradeoffs

## Runtime shape

```text
Clojure files ─> embedded clj-kondo ─┐
Janet files  ─> parser + resolver ───┼─> exact facts/references ─> Datalevin
selected EDN ─> data facts ──────────┘                              │
                         ┌───────────────────────────────────────────┤
                         ├─> bounded Datalog queries and traversal
                         ├─> durable LateOn indexing jobs
                         ├─> advisory 32M query-shape scoring
                         └─> deterministic exports
```

Clj-kondo runs once over the explicit Clojure-family source set. It uses its
own cache but no inferred build classpath, project command, or macro execution.
Janet uses a two-pass analyzer: collect modules, definitions, imports, exports,
and lexical scopes, then resolve uses against those facts and the pinned Janet
1.41.2 catalog. Focused source readers inspect arguments only after an API has
already been resolved, enabling re-frame and literal application-state topics.

Analyzer adapters emit their final exact edges and classified references.
There is no generic syntax-to-call index, global `by-name` promotion, or
post-persistence relationship resolver.

## Datalevin-first execution

Datalevin is the graph execution engine, not a serialized backing file:

- query filters, joins, ordering, limits, and aggregates stay in Datalog;
- callers and callees traverse only exact project edges;
- references remain independently queryable but cannot become neighbours;
- context selects each bounded frontier from one immutable snapshot and ranks
  typed paths by cost and evidence;
- file replacement, deletion, dirty marking, and semantic job transitions are
  transactions;
- only explicit export, full rebuild, and desired-state reconciliation
  enumerate project-wide facts.

The resident project service owns one warm Datalevin connection. Clients use
an owner-only Unix socket on Linux/macOS and authenticated loopback TCP on
Windows. Analysis is delegated to the owner, and graph plus semantic mutations
synchronize on that shared store, preventing competing writers. Requests use a
bounded pool so overload is explicit.

Full replacement retracts and asserts graph facts in batches of 100. A rebuild
also resets obsolete semantic operational state, writes graph-format metadata,
and queues documents for a versioned NextPlaid index.

## Gains

- One Clojure data vocabulary spans analyzer snapshots, schema, transactions,
  Datalog queries, jobs, packets, configuration, and exports.
- Clj-kondo provides authoritative namespaces, aliases, vars, locals, macros,
  protocols, and CLJC platform realms without another process.
- Exact-edge invariants make reverse traversal and context paths trustworthy.
- Typed event/state topics cross asynchronous ClojureScript boundaries.
- Stable identities reduce semantic re-embedding after ordinary edits.
- Embedded FTS remains fast and model-free when LateOn is absent.

## Costs

- The focused product intentionally ignores non-Clojure/Janet source.
- JDK 23+ and packaged Janet native grammar binaries are required.
- Cold JVM startup is visible; the resident service adds lifecycle machinery.
- Source-first clj-kondo analysis cannot know dependencies that require a
  project build classpath, and macros are never executed.
- Dynamic Clojure and Janet calls stay diagnostic instead of being guessed.
- LateOn improves retrieval but adds local model disk, memory, and background
  indexing cost. The advisory 32M router adds a second small resident inference
  process; it runs concurrently with retrieval and may be disabled independently.

These constraints favor a smaller graph with defensible evidence over broad
language coverage and noisy apparent connectivity.
