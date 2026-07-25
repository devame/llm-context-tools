# Performance benchmarks

Run the repeatable in-process workload with:

```bash
clojure -M:bench 50
```

It measures a full exact semantic graph, an unchanged incremental scan, a
single-file incremental update, a Datalog statistics query, a bounded Markdown
summary, and a bounded context query. Every non-root fixture function calls the
root function, deliberately creating a high-degree graph. The benchmark fails
if incremental analysis does not remain single-file or if context construction
violates its token-derived symbol ceiling. The fixture consists only of
Clojure namespaces with exact cross-namespace calls.

Run at two sizes when reviewing graph-read changes:

```bash
clojure -M:bench 50
clojure -M:bench 500
```

Compare the printed full, incremental, statistics, summary, and context
latencies on the same machine. Focused query latency should be driven by the
selected frontier and its degree, not by disconnected project size. CI also
contains disconnected-symbol and 200-neighbor fan-out regressions; those are
deterministic correctness gates rather than fragile wall-clock assertions.

Measure JVM process startup separately:

```bash
./bench/cold-start.sh 5
```

Cold JVM measurements in the development WSL workspace exceeded the one-second
plan threshold by a wide margin, so the project includes an authenticated,
loopback-only resident service. `llm-context service start` launches it as a
detached project coordinator; query, context, and export commands discover it
through `.llm-context/service.edn` and fall back to direct execution when it is
absent.

For a repository-specific semantic query set, use:

```bash
clojure -M:semantic-bench /path/to/project query-set.edn
```

The EDN input is a vector of natural-language queries and acceptable symbol
IDs/names:

```clojure
[{:query "where is a user session authenticated?"
  :expected ["authenticate-user" "auth.core/authenticate-user"]}]
```

The harness requires a running, synchronized project service and reports
recall-at-k, the fraction of queries with LateOn candidates, mean/p50/p95/max
latency, and misses. Keep the same graph, model revision, query set, and
hardware when comparing changes.

Benchmark output is descriptive rather than a universal release gate because
filesystem location, JDK, native architecture, and project language mix have
large effects. Regression comparisons should use the same machine and fixture
size.

## Development baseline

Measured on 2026-07-25 in the repository's WSL workspace with JDK 25.0.1
after the Datalevin-first read rewrite:

| High-fan-out workload | 50 files | 500 files |
|---|---:|---:|
| Full semantic graph (historical pre-0.8 implementation) | 1,524 ms | 4,039 ms |
| Unchanged incremental scan | 70 ms | 182 ms |
| Single-file incremental update and affected-edge reconciliation | 210 ms | 455 ms |
| In-process Datalog statistics query | 12 ms | 29 ms |
| In-process bounded summary | 30 ms | 69 ms |
| In-process bounded context query | 140 ms | 269 ms |
| Symbols returned within 2,000-token budget | 14 | 14 |

Both runs passed the benchmark's correctness gates. The 500-file graph held
2,501 canonical entities, while bounded context output remained constant at 14
symbols.

For historical comparison, the earlier disconnected fixture measured on
2026-07-21 with JDK 25 produced:

| Workload | Result |
|---|---:|
| Cold `version` process, median of 3 | 1.88 s |
| Full legacy graph, 20 JavaScript files | 1,354 ms |
| Unchanged incremental scan, 20 files | 272 ms |
| Single-file incremental update | 296 ms |
| In-process Datalog statistics query | 12 ms |
| In-process bounded context query | 53 ms |

The cold-process median remains above the one-second threshold even after lazy
command loading reduced it from roughly 14 seconds, which is why the resident
service is included. These historical numbers predate the high-fan-out
benchmark fixture; use current benchmark output for comparisons rather than
treating this table as a release threshold.
