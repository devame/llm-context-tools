# User guide

`llm-context` is a project-local code graph for Clojure, ClojureScript, CLJC,
and Janet. The executable, LateOn model, and 32M query router are installed
once per user; each repository keeps its generated Datalevin graph and indices below
`.llm-context/`. Datalevin and clj-kondo are embedded—there is no database
server or separate analyzer to install.

## Initialize and analyze

Run these commands from the repository root:

```bash
llm-context init
llm-context doctor
llm-context analyze --full
llm-context service start
```

`init` confirms the canonical root before writing `llm-context.edn`. The
default include is `"."`, so analysis scans that whole root while respecting
Git ignores and configured generated/cache exclusions. It recognizes `.clj`,
`.cljs`, `.cljc`, `.janet`, and selected `deps.edn`, `bb.edn`,
`shadow-cljs.edn`, and `.clj-kondo/config.edn` files. Other extensions are
intentionally and silently ignored.

Clojure-family analysis uses the embedded clj-kondo API project-wide. Focused
literal adapters use tools.reader with evaluation disabled and emit topics only
for provably static forms. Janet walks nodes from its packaged Tree-sitter
grammar before applying a module-aware resolver pinned to Janet 1.41.2.
Analysis never runs dependency commands, project build tools, Janet, or project
macros. Existing `.clj-kondo` config and hooks are read, but generated analyzer
cache stays under `.llm-context/cache/`.

Subsequent `llm-context analyze` runs use source and semantic fingerprints.
They persist only files whose facts changed, including unchanged source files
whose cross-file resolution changed. If a resident service owns the project,
analysis is sent to that process so there is only one Datalevin writer.

Run `llm-context analyze --check` to apply the same discovery, analyzer,
canonicalization, and whole-snapshot integrity checks without opening or
changing the graph database.

## What the graph guarantees

A traversable edge always has an exact in-project target, confidence `1.0`,
and evidence identifying the analyzer or focused adapter that proved it.
External library calls, local/dynamic calls, ambiguous definitions, and
unresolved names are diagnostic `reference` records, not graph edges. They are
searchable, but callers, callees, context traversal, and graph paths cannot
walk through them.

Malformed or internally conflicting analyzer snapshots fail before persistence,
so the last complete graph remains queryable. Queries do not observe a graph
while an update is in progress; full, incremental, and read-only checks share
project-level coordination.

ClojureScript adapters also create typed topics for literal re-frame events,
subscriptions, effects, coeffects, and statically recoverable application-state
keys. Those topics connect registrations, dispatchers, subscribers, state
readers, and state writers without inventing direct calls.

## Query the project

```bash
llm-context query stats
llm-context query find-symbol authenticate
llm-context query search "where is authentication handled?"
llm-context query search "where is authentication handled?" --explain
llm-context query search "where is authentication handled?" --source-preference production --explain
llm-context query callers symbol:...
llm-context query callees symbol:...
llm-context query callees symbol:... --include-external
llm-context query unresolved --classification dynamic
llm-context query topics
llm-context query dispatchers event-key
llm-context query state-readers saved-programs
```

`find-symbol` and the lexical half of `search` use Datalevin FTS. `search`
supports `--mode fts-only`, `--mode lateon-only`, and `--mode hybrid` (the
default); the first two are useful for controlled retrieval ablations.
`--explain` reports semantic status, latency, raw candidates, accepted fresh
candidates, stale rejections, source-role counts, and whether a source
preference reordered results. Search defaults to `--source-preference none`;
`auto`, `production`, and `test` are available when the caller wants explicit
source-role policy. A timeout or runtime failure still returns FTS results with
an explicit warning on the hybrid path.

## Build bounded context

```bash
llm-context context authenticate --depth 3 --max-tokens 4000
llm-context context --intent "where is authentication failure handled?"
llm-context context --intent "where are authentication tests?" --source-preference auto
llm-context context authenticate --format edn
```

Context uses deterministic weighted traversal over exact calls, macro calls,
protocol implementations, and event/state topic bridges. Every selected symbol
includes the path that admitted it. External and unresolved references consume
only a compact diagnostic budget. Graph-limit and token-limit truncation are
reported separately.

`--intent` asks the local LateOn index and Datalevin FTS to resolve a
natural-language request before traversal. Automatic planning first retrieves
a broad, shape-neutral pool while a resident 32M Mixedbread model independently
scores lookup, set, and flow. The score is advisory: a set needs several
structurally qualified candidates, while a flow needs at least two qualified
roots joined by an exact call or macro-invocation edge. Vocabulary relevance
can reorder candidates but cannot authorize a shape. Unsupported advice leaves
an adaptive multi-root plan. Explicit
single/multi options remain authoritative, and the model never filters the
retrieval pool. Accepted lookup requests keep one seed; set/flow requests may
select bounded, diverse roots under one shared traversal budget. Set requests
additionally expose a compact qualified-candidate
inventory so a four-root traversal is not presented as an exhaustive set.
Inventory entries are evidence summaries, not graph edges. Up to four
unselected alternatives remain packet metadata. If LateOn
is unavailable or times out, lexical retrieval remains available and the
packet records `:lexical-fallback`.
The query plan also reports whether evidence was structural, relevance-only,
or absent, and whether seed selection used structural evidence, relevance
fallback, or original rank.
Intent requests default to `--source-preference auto`. General implementation
questions prefer production files; explicit test/spec/fixture questions prefer
test files. This is a stable policy ordering, not a filter: lower-priority
roles remain alternatives, exact identifier matches retain priority, and the
original reciprocal-rank score is unchanged.

Project-specific path conventions can override the built-in cross-language
classifier in `llm-context.edn`:

```edn
{:context
 {:intent-source-preference :auto
  :intent-seed-mode :auto
  :intent-max-seeds 4
  :intent-rerank true
  :intent-candidate-count 100
  :query-router
  {:enabled true
   :query-timeout-ms 250
   :minimum-margin 0.02}
  :source-role-overrides
  [{:role :production :pattern "test/support/runtime/**"}
   {:role :test :pattern "quality/**"}]}}
```

Overrides are evaluated in order and use `*`, `**`, and `?` glob syntax.

Use `--semantic-timeout-ms N` to override the configured LateOn query deadline
for one `query search` or `context --intent` request. Use
`--seed-mode single|multi|auto` and `--max-seeds N` to override context
cardinality. `query search` retains its existing ordering unless
`--intent-rerank` is explicitly supplied.

## Semantic indexing

The service supervises NextPlaid and drains durable LateOn jobs in the
background:

```bash
llm-context semantic status
llm-context semantic failures
llm-context semantic dirty
llm-context semantic retry --failed --wait
llm-context semantic sync --wait
```

Status separates runtime availability from index completeness and reports
desired/indexed coverage. A ready runtime with a handful of terminal jobs is
available with partial completeness; it is not globally unavailable. Failed
jobs remain terminal until `retry --failed`. `sync --wait` exits non-zero until
pending, leased, failed, and dirty counts all converge.

Graph format 3 makes `:symbol/indexable?` authoritative for semantic document
selection. Document/index v3 rejects conflicting documents, uses graph-revision
freshness watermarks, and automatically recreates missing reconciliation work.

## Upgrade to graph format 3

Version `0.9.0` replaces the analyzer interchange contract. After upgrading,
run:

```bash
llm-context analyze --full
```

Normal queries against an older graph stop with that actionable instruction.
The rebuild removes only generated graph/queue metadata, records analyzer and
catalog versions, and uses the current versioned NextPlaid index so stale
vectors cannot appear in new results. Source files and `llm-context.edn` are
untouched. If the project service is running, leave it running—the rebuild is
coordinated through its Unix socket (loopback TCP on Windows).

## Installation and troubleshooting

The one-script installer verifies the jar, NextPlaid, ONNX Runtime, pinned
LateOn model, and pinned 33 MB INT8 query-router model. Set
`LLM_CONTEXT_SKIP_SEMANTIC=1` when only exact graph and FTS features are wanted.

- If `doctor` reports Java failure, install JDK 23 or newer.
- If graph format is incompatible, run `llm-context analyze --full`.
- If no files are discovered, confirm `init` was run at the repository root
  and inspect `:analysis :include` and `:exclude`.
- If a supported file is absent, check Git ignore rules and
  `:max-file-bytes`. Unsupported extensions are ignored by design.
- If semantic indexing is partial, inspect `semantic failures` and
  `semantic dirty`, then explicitly retry failed jobs.
- Runtime details are in `.llm-context/logs/`; all project state is disposable
  and excluded from normal source control.

Agent guidance can be installed with
`llm-context integrate codex|claude|generic`. Deterministic EDN, JSON, JSONL,
and Markdown projections remain available through `llm-context export`.
