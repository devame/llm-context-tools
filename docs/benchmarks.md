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

Run the maintained Clojure and Janet retrieval corpus with:

```bash
clojure -M:validate-semantic-corpus
llm-context -C bench/retrieval-corpus/project analyze --full
llm-context -C bench/retrieval-corpus/project service start
clojure -M:semantic-bench \
  bench/retrieval-corpus/project \
  bench/retrieval-corpus/queries.edn
```

The checked-in corpus is a stable benchmark project with 24 graded queries.
It includes identifier, behavior, state-flow, graph-flow, framework-convention,
hard-negative, and cross-language slices. See
[`bench/retrieval-corpus/README.md`](../bench/retrieval-corpus/README.md) for
the judgment contract and maintenance rules.

For a repository-specific semantic query set, use:

```bash
clojure -M:semantic-bench /path/to/project query-set.edn
```

Corpus format 2 uses structured selectors so judgments can distinguish symbols
that share a qualified name across platforms or files. Every selector must
contain `:id` or `:qualified-name`; optional `:platform`, `:file`, and `:kind`
fields are matched together with that identity:

```clojure
{:corpus/version 2
 :queries
 [{:id :synthetic/authenticate-session
   :language :clojurescript
   :query-type :behavior
   :domain :sessions
   :query "where is a user session authenticated?"
   :relevance
   [{:qualified-name "example.session/authenticate-user"
     :platform :cljs
     :grade 3}
    {:qualified-name "example.session/decode-session"
     :platform :cljs
     :grade 1}]
   :hard-negatives
   [{:qualified-name "example.session/authorize-user"
     :platform :cljs}]}]}
```

The validator resolves each format-2 selector to exactly one analyzer symbol
and rejects ambiguous selectors and relevant/hard-negative overlap. Each
relevance judgment can contribute gain only once, even when duplicate
candidates match it. Format 1 string judgments and the legacy vector of
`{:query string :expected [...]}` entries remain accepted unchanged.

The harness requires a running, synchronized project service. For every query
it evaluates both hybrid search and `context --intent`, reporting search
recall-at-k, MRR, nDCG-at-k, hard-negative-before-relevant rate, context seed
recall-at-1, final packet recall, per-language and per-query-type quality slices,
LateOn query/seed rates, separate search and end-to-end context latency
distributions, misses, and context errors. Here `k` is the returned hybrid
candidate count. Keep the same graph, model revision, query set, candidate
count, context budget, and hardware when comparing changes.

When query-level details must remain in a local result file, pass `--output`:

```bash
clojure -M:semantic-bench /path/to/project query-set.edn \
  --output /path/to/private-result.edn
```

The result file contains the full benchmark result, including query-level
misses and hard-negative diagnostics. Standard output contains only aggregate
quality metrics, slice metrics, latency summaries, and diagnostic counts. When
`--output` is absent, the existing full standard-output behavior is preserved.
Every run records the scorer version, retrieval model and revision, document
version, candidate count, context depth, and context token budget.

Benchmark output is descriptive rather than a universal release gate because
filesystem location, JDK, native architecture, and project language mix have
large effects. Regression comparisons should use the same machine and fixture
size.

## 0.8.0 release-candidate project gate

The `0.8.0` distribution analyzed this repository on 2026-07-25 under WSL and
JDK 25.0.1:

| Metric | Result |
|---|---:|
| Supported files | 76 |
| Canonical entities | 10,706 |
| Symbols | 773 |
| Exact traversable edges | 3,075 |
| External references | 6,648 |
| Dynamic references | 62 |
| Ambiguous references | 2 |
| Unresolved references | 0 |
| Duplicate identities | 0 |
| Full rebuild (warm cache, including semantic reconciliation) | 50 s |
| No-op incremental | 0 changed / 0 deleted |

The full command completed with zero diagnostics, queued 698 version-2
semantic documents, and a 2,000-token context request returned a deterministic
explained path while respecting its budget.

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
