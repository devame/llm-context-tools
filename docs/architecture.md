# Architecture and tradeoffs

## Runtime shape

The CLI resolves the project root and validated EDN configuration once. Full or
incremental analysis discovers source files, parses supported files through the
JTreeSitter provider, converts syntax into canonical entities, optionally runs
SCIP TypeScript, resolves edges, classifies effects, and transacts file-owned
facts into Datalevin.

```text
source files ──> Tree-sitter ──> structural facts ──┐
                                                   ├─> resolution/effects ─> Datalevin
JS/TS project ──> SCIP TypeScript ─> exact evidence┘                         │
                                                                               ├─> Datalog queries
                                                                               ├─> context packets
                                                                               └─> exports/summaries
```

Three boundaries keep the implementation replaceable:

- `ParserProvider` converts language source into provider-neutral syntax data.
- `SemanticIndexer` converts one file into canonical graph entities.
- `GraphStore` owns validation, transactions, replacement, deletion, and
  Datalog execution.

The optional service retains a warm JVM but not a permanently open database
connection. Query, context, and export requests open and close the project
store. Analysis deliberately runs in the invoking CLI process: this keeps
stage and transaction progress observable and prevents a service socket timeout
from falling back to a second concurrent writer.

Full replacement sorts canonical entities by dependency layer (files, symbols,
edges, effects), retracts the previous graph in bounded transactions, and then
asserts the replacement in transactions of at most 100 records. This avoids
Datalevin's pathological cost when resolving many forward temporary-ID
references in one large transaction. The tradeoff is that a process interrupted
during persistence can leave a partial graph; the recovery operation is another
full analysis.

## What Clojure and Datalevin gain

- Datalevin is called through its native Clojure API without a Node/JVM bridge.
- The schema, facts, Datalog rules, configuration, context packets, and exports
  share one immutable-data vocabulary.
- File replacement and deletion are explicit transactions, including inbound
  relationships owned by unchanged callers.
- Recursive reachability and reverse graph questions are database queries, not
  rebuilt in-memory JSON indexes.
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
