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
llm-context analyze
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
analysis is sent to that process so there is only one Datalevin writer. When
semantic indexing is enabled and no service owns the project, `analyze` starts
one after queueing the durable semantic jobs. Use `analyze --no-service` for a
one-shot graph-only or CI run.

If an abrupt service death leaves `.llm-context/service.edn` or
`.llm-context/service.sock` behind, the next command safely removes them after
proving that the OS service lock has no owner. A timeout is not treated as a
dead service. `llm-context service stop` is safe to repeat and reports
`not running` after stale state has been reclaimed.

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
scores lookup, set, and flow. The score is advisory: an exhaustive set needs a
complete source-backed aggregate, while a flow needs at least two qualified
roots joined by an exact call or macro-invocation edge. The same resident model
also scores the unchanged question against a bounded prefix of retrieved
candidate documents. That learned score owns semantic ordering; deterministic
code only annotates structural evidence and cannot authorize a shape. The
default `:shadow` mode reports scores without applying their order; `:enforce`
is reserved for a model that passes the frozen reranking suite.
Unsupported advice leaves
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
  :candidate-reranker
  {:enabled true
   :mode :shadow
   :candidate-count 50
   :query-timeout-ms 5000
   :document-cache-size 2048}
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
`--intent-rerank` is explicitly supplied. Shadow mode records the order the
model would choose without changing results. Learned-reranker failure preserves
the existing order and is reported; it never activates the former literal
term-overlap ordering.

The original question is sent unchanged to symbol, aggregate, and semantic
retrieval. The deterministic planner contains no repository vocabulary,
synonym table, or hidden lexical rewrite. Repository terminology is learned
from indexed source evidence and scored by the configured models.

## Semantic indexing

The service supervises NextPlaid and drains durable LateOn jobs in the
background:

```bash
llm-context semantic status [--verbose]
llm-context semantic status --watch [--interval-ms N] [--verbose]
llm-context semantic failures
llm-context semantic dirty
llm-context semantic retry --failed --wait
llm-context semantic sync --wait
```

The concise status output has two lines. The first tracks semantic documents;
the second reports aggregate and membership facts produced by analysis,
whether their owning semantic documents are complete, and any files whose
optional aggregate extraction was skipped in the latest analysis. Aggregate
facts are embedded in the owning symbol documents rather than indexed as a
separate document type.

Status defaults to one line: remaining documents, total desired documents, and
measured documents per second. Add `--verbose` for runtime availability,
desired/indexed coverage, leased and provider-accepted work, provider details,
and analysis progress. During a
graph replacement verbose status remains readable and reports the last
committed graph snapshot together with live analysis progress. Use `--watch`
from any terminal to poll either view; the default interval is 2 seconds and
can be changed with `--interval-ms`. The durable progress
snapshot is `.llm-context/analysis-progress.edn`, so a restarted service can
also report that an interrupted analysis was abandoned. A ready runtime with a handful of terminal jobs is
available with partial completeness; it is not globally unavailable. Failed
jobs remain terminal until `retry --failed`. `sync --wait` exits non-zero until
pending, leased, failed, and dirty counts all converge.

Create a provider-native compact graph copy without changing the live graph:

```bash
llm-context maintenance status
llm-context maintenance compact-copy [--output PATH]
llm-context maintenance cleanup --older-than-days 30
llm-context maintenance cleanup --older-than-days 30 --apply
```

`maintenance status` is read-only. It inventories only known project-owned
graph, provider-index, staging, recovery, maintenance, and log locations and
reports their size, file count, and latest modification time.

Cleanup defaults to a dry run. Only llm-context-marked recovery archives,
verified compact copies, and exact content-addressed analyzer staging
generations are eligible; the newest artifact in each category is always
retained. `--apply` is required to perform deletion. Unmarked maintenance and
recovery paths, active logs, and provider indexes are never deleted.

Generate, inspect, and then install a host-native supervisor definition:

```bash
llm-context service supervisor --format systemd --output llm-context.service
llm-context service supervisor --format launchd --output llm-context.plist
llm-context service supervisor --format windows --output register-service.ps1
```

The generator resolves the current `llm-context` executable, binds the
definition to the selected project root, and adds restart backoff,
single-instance behavior, and conservative process limits. It does not install
or enable anything. Host-managed journals are preferred over an unbounded
application-owned supervisor log.

Analyzer preparation snapshots are compressed and content-addressed under the
configured `:analysis/:staging-directory`. The default 2 GiB
`:analysis/:maximum-staging-generation-bytes` limit stops a generation before
its completion index is published. Only a complete generation matching the
whole source inventory, graph format, and analyzer contract can be resumed;
partial data is never queryable.

Generated writes also have two independent guards. Set
`:store/:minimum-free-space-bytes` for the capacity reserve and
`:store/:maximum-operation-growth-bytes` for the maximum growth of one graph
analysis or semantic-worker run. Directory sizing is rate-limited by
`:store/:storage-sample-interval-ms` (5 seconds by default); free-space itself
is checked before every write unit.

The destination must not exist or overlap the live database. llm-context opens
the finished copy and compares graph metadata plus canonical and semantic
operational identity counts. It reports the verified path but never activates
or deletes it automatically.

Graph format 4 makes `:symbol/indexable?` authoritative for semantic document
selection and indexes namespace/module containers as deterministic coarse
summaries. Safe literal collections become source-backed aggregates with
explicit completeness and membership. Document/index v4 rejects conflicting
documents, uses graph-revision freshness watermarks, and automatically
recreates missing reconciliation work.

## Upgrade to graph format 4

Graph format 4 adds aggregate evidence and container documents. After
upgrading, the next normal analysis detects an older graph and automatically
rebuilds it. To force the upgrade explicitly, run:

```bash
llm-context analyze --full
```

Normal queries against an older graph remain fail-closed until the rebuild
finishes. A normal `analyze` performs the upgrade automatically; `--full`
forces the same rebuild explicitly. The rebuild removes only generated
graph/queue metadata, records analyzer and
catalog versions, and uses the current versioned NextPlaid index so stale
vectors cannot appear in new results. Source files and `llm-context.edn` are
untouched. If the project service is running, leave it running—the rebuild is
coordinated through its Unix socket (loopback TCP on Windows).

## Installation and troubleshooting

The one-script installer verifies the jar, NextPlaid, ONNX Runtime, and both
FP32 and INT8 variants of the pinned retrieval and routing models. Set
`LLM_CONTEXT_SKIP_SEMANTIC=1` when only exact graph and FTS features are wanted.

After installation, inspect the machine and packaged runtime with:

```bash
llm-context setup
llm-context doctor
```

`setup` reports the visible GPU, NVIDIA driver version, minimum supported
driver (`525.60.13` for the CUDA 12 package), `libcuda.so.1`, CUDA 12, and
cuDNN 9. These are static host checks; the first semantic service startup is
still the authoritative runtime probe. If it finds a missing cuDNN package on
Debian/Ubuntu, setup offers:

```bash
sudo apt-get update && sudo apt-get -y install cudnn9-cuda-12
```

Use `llm-context setup --install-cudnn --yes` only when that package and its
repository are already configured; the package name follows the
[NVIDIA cuDNN Linux installation guide](https://docs.nvidia.com/deeplearning/cudnn/installation/latest/linux.html).
Setup does not install NVIDIA drivers. In WSL, install or update the
[CUDA-enabled NVIDIA driver on Windows](https://docs.nvidia.com/cuda/pdf/CUDA_on_WSL_User_Guide.pdf);
do not install a Linux NVIDIA driver inside WSL.

On Linux x86-64, `install.sh` defaults to
`LLM_CONTEXT_ACCELERATOR_PACKAGE=auto`: it selects the CUDA package only when
the static preflight passes, otherwise it installs the CPU package and prints
the missing prerequisites. Use `LLM_CONTEXT_ACCELERATOR_PACKAGE=cpu` to force
CPU or `LLM_CONTEXT_ACCELERATOR_PACKAGE=cuda` to require CUDA and fail early
when the host is incomplete. The Windows package is currently CPU-only.

- If `doctor` reports Java failure, install JDK 23 or newer.
- If graph format is incompatible, run `llm-context analyze`; it automatically
  performs the required full rebuild. Use `--full` to force the rebuild.
- If no files are discovered, confirm `init` was run at the repository root
  and inspect `:analysis :include` and `:exclude`.
- If a supported file is absent, check Git ignore rules and
  `:max-file-bytes`. Unsupported extensions are ignored by design.
- If semantic indexing is partial, inspect `semantic failures` and
  `semantic dirty`, then explicitly retry failed jobs.
- Runtime details are in `.llm-context/logs/`; all project state is disposable
  and excluded from normal source control.

### CPU and CUDA inference

Embedding generation can use CUDA; canonical analysis, Datalevin transactions,
queue management, and NextPlaid index writes remain CPU and storage work. The
semantic retriever accepts these project settings:

```edn
{:semantic
 {:lateon-code
  {:accelerator :auto       ; :auto, :cpu, or :cuda
   :quantization :auto      ; :auto, :int8, or :fp32
   :cuda-library-paths []   ; absolute directories for cuDNN/CUDA libraries
   :cuda-encoding-sessions 1
   :cuda-encoding-batch-size 1
   :cuda-update-concurrency 1}}}
```

`:auto` selects CUDA/FP32 only when a GPU device, both ONNX Runtime CUDA
provider libraries, cuDNN 9, and the verified `model.onnx` are present.
Otherwise it uses CPU/INT8 and immediately reports the exact fallback reason
and a corrective action during `analyze`, `semantic status`, and `doctor`.
For example, `cudnn-missing` means that `libcudnn.so.9` must be installed and
made visible to the service. Explicit `:cuda` fails closed when any
prerequisite is absent. Runtime checks also verify that the CUDA provider can
actually discover a device; if it cannot, `semantic status` and `doctor` show
the provider error and the corrective action. Explicit `:cuda` fails closed
for that runtime failure instead of silently falling back to CPU. NextPlaid
does not support CUDA with the INT8 model. In verbose status, treat
`:runtime-diagnostic` as authoritative: a bare `:accelerator :cuda` selection
describes the launch mode, not proof that the CUDA provider initialized.

The query router/reranker has the same fields under
`:context :query-router`. Its default remains CPU/INT8 because the 32M model is
small and per-query GPU transfer and synchronization can cost more than the
inference it saves. Users can still select CUDA/FP32 and benchmark it locally.
The router's separate CUDA batch default is 8 because its documents are capped
at 128 tokens; do not copy that value to the 2,048-token semantic retriever.

Linux x86_64 users can request the verified CUDA-enabled NextPlaid and ONNX
Runtime package during installation:

```bash
LLM_CONTEXT_ACCELERATOR_PACKAGE=cuda sh install.sh
```

The host must provide CUDA 12 and cuDNN 9. Add non-standard dependency
directories to `:cuda-library-paths`. The Linux installer defaults to the
same `auto` preflight described above; use `LLM_CONTEXT_ACCELERATOR_PACKAGE=cpu`
to keep a CPU-only installation.

Agent guidance can be installed with
`llm-context integrate codex|claude|generic`. Deterministic EDN, JSON, JSONL,
and Markdown projections remain available through `llm-context export`.

### Replacing model packages

The semantic retriever, query router/reranker, and optional answer reader use a
shared verified package contract. Inspect an installation with:

```bash
llm-context models status
```

To install a custom Hugging Face-compatible snapshot set, create a contract-v1
EDN manifest using the built-in `resources/llm_context/model-packages.edn` as a
template. Every role needs an immutable 40-character revision and a SHA-256 for
every file. Then run the installer with:

```bash
LLM_CONTEXT_MODEL_MANIFEST=/absolute/path/models.edn \
LLM_CONTEXT_MODEL_MANIFEST_SHA256=<manifest-sha256> \
LLM_CONTEXT_MODEL_ROLES=semantic-retriever,query-router-reranker,answer-reader \
sh install.sh
```

The same variables work with `install.ps1`. A `file:///absolute/directory`
base URL in the manifest installs from a local snapshot. There is no option to
run unverified models: a missing manifest checksum, mutable revision, unsafe
path, or mismatched model file stops installation before the runtime registry
is changed.
