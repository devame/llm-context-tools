# Performance benchmarks

Run the repeatable in-process workload with:

```bash
clojure -M:bench 50
```

It measures a full structural index, an unchanged incremental scan, a
single-file incremental update, a Datalog statistics query, and a bounded
context query. SCIP is disabled so network/package-manager behavior does not
pollute structural-index measurements.

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

Measured on 2026-07-21 in the repository's WSL workspace with JDK 25:

| Workload | Result |
|---|---:|
| Cold `version` process, median of 3 | 1.88 s |
| Full structural index, 20 JavaScript files | 1,354 ms |
| Unchanged incremental scan, 20 files | 272 ms |
| Single-file incremental update | 296 ms |
| In-process Datalog statistics query | 12 ms |
| In-process bounded context query | 53 ms |

The cold-process median remains above the one-second threshold even after lazy
command loading reduced it from roughly 14 seconds, which is why the resident
service is included.
