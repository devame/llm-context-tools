# Architecture and tradeoffs

## Runtime shape

The CLI resolves the project root and validated EDN configuration once. Full or
incremental analysis discovers source files, parses supported files through the
JTreeSitter provider, converts syntax into canonical entities, classifies
effects, and transacts file-owned facts into Datalevin. Relationship resolution
then starts from an immutable Datalevin snapshot. Optional SCIP TypeScript
evidence is joined to focused database point lookups; it never creates a second
application-owned graph.

```text
source files ──> Tree-sitter ──> canonical facts/effects ──> Datalevin
                                                               │
                      ┌────────────────────────────────────────┤
JS/TS ──> SCIP ──────┤ focused source-point evidence           │
                      └─> Datalog candidate selection ──> edge transactions
                                                               │
                              ┌────────────────────────────────┤
                              ├─> focused Datalog queries
                              ├─> bounded context traversal
                              ├─> semantic indexing jobs
                              └─> explicit full exports/repairs
```

Three boundaries keep the implementation replaceable:

- `ParserProvider` converts language source into provider-neutral syntax data.
- `SemanticIndexer` converts one file into canonical graph entities.
- `GraphStore` owns validation, transactions, replacement, deletion, and
  Datalog execution.
- `graph.read` owns snapshot-consistent selection, joins, aggregates, and
  bounded graph projections used across commands and background work.

## Datalevin-first execution rules

Datalevin is the graph execution engine, not merely the final persistence
format:

- focused commands resolve an exact identity first, traverse only the requested
  frontier, and pull records only after Datalevin has selected their IDs;
- incremental analysis records changed file and symbol identities, asks
  Datalevin for edges affected by those identities, and reconciles that set in
  one batched transaction;
- full analysis explicitly persists unresolved canonical edges before selecting
  all edge IDs for database-backed resolution;
- semantic reconciliation reads jobs, indexed state, and symbols by dirty file
  or requested symbol; only an explicit full repair enumerates all files;
- summaries use aggregates and database limits. Whole-graph exports and full
  repair are named full operations and use one immutable snapshot.

Production code must not rebuild global `group-by` indexes from parser output,
pull every symbol before applying a focus, or issue one scalar database query
per edge. In-memory operations are limited to already-selected bounded
candidates, deterministic formatting, and external SCIP/embedding evidence.

The optional service retains a warm JVM and one project Datalevin connection.
It accepts sockets continuously and dispatches requests through a bounded
executor, so a slow query does not block unrelated clients. Datalevin queries
read immutable database values; graph and semantic-state mutations retain their
single-writer coordination. Analysis deliberately runs in the invoking CLI
process so stage and transaction progress remain observable. Once a service
descriptor exists, connection failures and timeouts are explicit errors rather
than permission to fall back to a second direct database connection.

The control transport is an owner-only Unix-domain socket on Linux and macOS,
which remains usable across network namespaces sharing the project filesystem.
Windows uses authenticated loopback TCP. A process-held file lock defines
service ownership independently of either transport and makes stale Unix socket
cleanup safe after a crash.

Full replacement sorts canonical entities by dependency layer (files, symbols,
edges, effects), retracts the previous graph in bounded transactions, and then
asserts the replacement in transactions of at most 100 records. This avoids
Datalevin's pathological cost when resolving many forward temporary-ID
references in one large transaction. The tradeoff is that a process interrupted
during persistence can leave a partial graph; the recovery operation is another
full analysis.

Context breadth is also bounded before record pulls. The token budget supplies
a conservative symbol ceiling, and each Datalog neighbor query has a limit.
This matters for hub symbols: token truncation after an unbounded traversal
would still pay the cost of reading the whole connected component.

## What Clojure and Datalevin gain

- Datalevin is called through its native Clojure API without a Node/JVM bridge.
- The schema, facts, Datalog rules, configuration, context packets, and exports
  share one immutable-data vocabulary.
- File replacement and deletion are explicit transactions, including inbound
  relationships owned by unchanged callers.
- Recursive reachability and reverse graph questions are database queries, not
  rebuilt in-memory JSON indexes.
- Indexed derived attributes such as `:edge/target-name` turn changed symbol
  identities into focused incremental edge queries.
- Aggregates, anti-joins, full-text search, immutable snapshots, pull patterns,
  and bounded ordered queries replace repeated whole-table scans.
- REPL-oriented development makes extraction and query behavior independently
  testable.
- EDN keeps configuration expressive without adding executable configuration.

## What the pivot costs

- JTreeSitter requires JDK 23+, which is a higher runtime floor than the former
  Node CLI.
- Cold JVM startup is visible. Lazy command loading and the optional resident
  service mitigate it but add operational choices.
- The current npm tarball contains a roughly 50 MB uberjar and native grammar
  libraries for multiple platforms.
- Native grammar packaging must be tested for every supported OS/architecture;
  the Janet grammar is pinned and cross-compiled into all supported packages.
- `scip-typescript` still requires Node when compiler-backed JS/TS evidence is
  desired.
- The Clojure contributor pool is smaller than the JavaScript contributor pool.
- Tree-sitter, Clojure, and Datalevin do not automatically provide compiler
  symbol resolution. Unsupported semantics remain heuristic or unresolved.
- TSX is detected but has no compatible packaged structural grammar in this
  release. Janet is structurally analyzed, but—like other languages without a
  compiler-backed provider—cross-file resolution remains explicitly heuristic
  or unresolved.

These costs are accepted because the project premise is a persistent semantic
graph and query engine, not a transient JSON document generator.
