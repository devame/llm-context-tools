# Troubleshooting FAQ

This FAQ starts from visible symptoms and gives the safest verification and
recovery path. Do not delete `.llm-context/` as a first response: it contains
the failure details needed to distinguish a damaged index from a runtime,
configuration, or service problem.

## What should I run first?

From the project root, collect the basic health report:

```bash
llm-context version
llm-context doctor
llm-context semantic status --verbose
llm-context service status
```

If semantic status reports failed jobs, also run:

```bash
llm-context semantic failures
```

Runtime logs are under `.llm-context/logs/`. Do not share
`.llm-context/service.edn`; it contains the local service authentication token.

## How do I know whether a project is fully indexed?

Use:

```bash
llm-context semantic status --verbose
```

Semantic indexing is complete only when all of these are true:

- `:indexed` equals `:desired`;
- `:coverage-percent` is `100.0`;
- `:completeness` is `:complete`;
- `:pending`, `:leased`, `:failed`, and `:dirty` are all zero; and
- the semantic watermark is ready for the current graph revision.

Graph analysis and semantic indexing are separate. Graph queries can work while
semantic indexing is still partial.

## Why does concise status say documents are pending when verbose status says `:pending 0`?

Current releases use the durable queue counts directly. Concise status reports
indexed/desired coverage and separate pending, leased, failed, and dirty counts.
If an older CLI prints only `N of M documents pending`, upgrade it: that format
derived pending from coverage and could hide terminal failures.

For example, this is a failed state rather than active indexing:

```clojure
{:desired 5211
 :indexed 4128
 :pending 0
 :leased 0
 :failed 1083
 :watermark {:semantic.watermark/state :degraded}}
```

Find the actual failures with `llm-context semantic failures`.

## Why does status say the worker is running even though nothing is progressing?

Current status combines worker liveness with progress age. Outstanding work
without forward progress beyond the configured provider deadlines becomes
`:stalled` and emits an alert. Check:

- whether `:pending` or `:leased` changes between status samples;
- whether `:failed` is nonzero;
- whether the watermark is `:degraded` or `:failed`;
- the age of `.llm-context/logs/service.log`; and
- recent errors in `.llm-context/logs/next-plaid.log`.

Use a short polling interval while diagnosing:

```bash
llm-context semantic status --watch --interval-ms 2000 --verbose
```

An unchanged cumulative speed is historical telemetry; the health state and
exact queue counts determine whether work is active.

## Why did many semantic documents fail with `NextPlaid request timed out`?

Common causes are an unhealthy or overloaded inference runtime, a child process
that exited after startup, incorrect CUDA library resolution, insufficient
memory, or storage that cannot keep up with updates.

Current releases classify provider-wide failures before consuming per-document
retry budgets. The worker returns the batch to pending, opens the recovery
circuit, and the service restarts and verifies the provider. First inspect the
automatic recovery and remaining deterministic failures:

```bash
llm-context doctor
llm-context semantic status --verbose
```

Known infrastructure failures left terminal by older releases are retried once
after a verified runtime recovery. Inspect `.llm-context/logs/next-plaid.log`
for the original cause. Retry remaining terminal jobs only after correcting it:

```bash
llm-context semantic retry --failed
llm-context semantic status --watch
```

Repeated retries while the provider is still unhealthy will exhaust the jobs
again.

## Does `doctor` fail when semantic indexing is broken?

Yes. Installation is optional, but an enabled component is an operational
commitment. `doctor` performs static artifact checks plus a live execution
probe or resident-service check. It exits nonzero for incompatible services,
failed or dirty queues, stalled/degraded runtime health, failed enabled
components, unsafe storage, and interrupted analysis.

## Why does status select CUDA even though inference is actually running on CPU?

Static preflight verifies that the GPU, driver, provider files, CUDA runtime,
cuDNN, and model artifacts appear to be present. The final proof is whether the
ONNX CUDA provider initializes inside NextPlaid.

Under `:auto`, if initialization fails or NextPlaid falls back internally, the
service rejects that process and starts a newly verified CPU/INT8 process with
CPU concurrency. Status records the recovery and effective profile. Explicit
`:cuda` remains fail-closed. Inspect the health alert and
`.llm-context/logs/next-plaid.log` for the original CUDA error.

To use the normal CPU profile explicitly, configure:

```clojure
{:semantic
 {:lateon-code
  {:accelerator :cpu
   :quantization :int8}}}
```

Then restart the project service:

```bash
llm-context service stop
llm-context analyze
```

## Why can CUDA preflight pass but CUDA initialization still fail under WSL?

WSL exposes the Windows driver through `/usr/lib/wsl/lib/libcuda.so.1`. A Linux
NVIDIA driver package installed inside WSL can add another `libcuda.so.1`; the
dynamic loader may select that incompatible library even though `nvidia-smi`
works.

Run `llm-context setup` and inspect the runtime log. If the WSL driver proxy
must be selected explicitly, configure:

```clojure
{:semantic
 {:lateon-code
  {:cuda-library-paths ["/usr/lib/wsl/lib"]}}}
```

Restart the service after changing the configuration. Do not install a Linux
NVIDIA display driver inside WSL; the NVIDIA driver belongs on Windows. CUDA 12
and cuDNN 9 user-space libraries may still be installed in the WSL distribution.

## Why is CPU indexing much slower than expected?

First confirm that this is a real CPU configuration. A failed CUDA launch that
falls back internally can leave the process using the FP32 model, one encoding
session, and CUDA-oriented request concurrency. The normal CPU profile uses the
INT8 model and CPU concurrency.

Also separate current throughput from retry time. Large request timeouts,
visibility polling, repeated failures, and idle time can make cumulative speed
look like inference speed. Verify that completed-document counts are increasing
and that `:failed` remains zero before comparing repositories.

Repository results are not directly comparable when document counts, document
lengths, changed-file sets, storage, model state, or exact-reuse rates differ.

## Why did `analyze` finish even though semantic indexing later failed?

`analyze` commits the canonical graph and queues semantic work. Semantic
encoding runs asynchronously so exact graph queries remain available when the
optional inference runtime is unavailable.

The command confirms that work was queued; it does not mean background work has
completed. Use `llm-context semantic status --watch` when completion matters.
For scripts that must require complete semantic coverage, use:

```bash
llm-context semantic sync --wait --timeout-ms 3600000
```

This exits nonzero for failed jobs or an unavailable runtime. It does not repair
terminal jobs; repair the runtime and run `semantic retry --failed` first.

## Why did `analyze` not start the semantic service?

Automatic service startup is skipped when:

- semantic indexing is disabled;
- `analyze --check` or `analyze --no-service` was used;
- analysis itself failed; or
- service startup returned an error.

Check the configured provider and start it explicitly if needed:

```bash
llm-context doctor
llm-context service start
llm-context service status
```

If another service already owns the project, do not start a second one.

## Why does the service remain in `starting` state?

The detached launcher waits only a bounded time for the service endpoint. A
large graph may still be opening indexes, recovering an interrupted operation,
or loading a model after that window.

Inspect:

```bash
llm-context service status
llm-context semantic status --verbose
```

If the service process exited, the startup error is in
`.llm-context/logs/service.log`. If it remains alive without state changes,
treat it as stalled rather than extending timeouts indefinitely.

## What should I do about `service advertised but unreachable` or `Connection refused`?

An abrupt JVM exit can leave the descriptor and Unix socket behind. The next
client request safely reclaims those files only after proving that no process
owns the project service lock.

Run:

```bash
llm-context service status
llm-context analyze
```

Do not manually delete the descriptor, socket, or lock while a process may
still be alive. A request timeout can mean a busy service and is not proof that
the service is dead. Inspect `.llm-context/logs/service.log` and the advertised
PID before intervening.

`service status` preserves timeout, unreachable, protocol, and version errors;
it reports `not running` only when no descriptor remains.

## Can an older resident service keep running after I upgrade the CLI?

The descriptor and RPC now expose application and protocol versions. Read-only
commands reject an incompatible service with a corrective action. `analyze`
and `service start` gracefully stop the advertised old PID, verify shutdown,
and launch the current service.

You may still stop long-running project services before upgrading to minimize
the first-command restart:

```bash
llm-context service stop
# rerun the installer
llm-context version
llm-context analyze
```

If replacement cannot prove ownership or shutdown, it fails closed instead of
starting a second Datalevin owner.

## Why are hybrid search results returned with a semantic warning?

Semantic retrieval is an enhancement. If it times out or is unavailable,
hybrid search returns local Datalevin full-text results instead of failing the
whole command.

Use explanation output to inspect what happened:

```bash
llm-context query search "where is authentication handled?" --explain
```

Check the retrieval status, fallback flag, effective timeout, candidate counts,
and stale-candidate rejection. A lexical fallback is usable but should not be
treated as successful semantic retrieval.

## Why can lexical or aggregate search return fewer results without an error?

Datalevin full-text search accepts richer syntax than literal identifier
matching. An invalid full-text expression falls back to literal substring
matching, and aggregate full-text lookup can return no aggregate candidates.
Only recognized expression parse/syntax errors use that fallback; storage and
other Datalevin failures propagate instead of being disguised as no results.

Try a simpler literal term and compare `query find-symbol` with
`query search ... --explain`. If unrelated full-text queries also become empty,
run `doctor` and inspect the service log rather than assuming the repository has
no matching symbols.

## Why can query routing or learned reranking degrade without changing results?

The query router and candidate reranker are advisory. If either fails, the
original retrieval ordering or built-in planning remains available. Their
failure is retained in explanation metadata so optional model failure cannot
erase otherwise valid results.

Inspect `semantic status --verbose` for `:query-router-status` and
`:candidate-reranker-status`, and use `--explain` for per-query provenance. The
service supervises and restarts the shared advisory runtime while canonical/FTS
fallback remains available.

## What does `aggregate-analysis-skipped` mean?

Aggregate extraction is an optional enrichment for safe literal collections in
Clojure-family source. A skipped aggregate does not mean the file or its normal
symbols were omitted from the canonical graph. The diagnostic includes the file
and reader error.

Upgrade first if ClojureScript `#js` literals are involved; support for them was
added in v0.12.2. Otherwise inspect the source form for unsupported reader
syntax. `semantic status` reports aggregate counts and the number of skipped
files separately.

## What do the other analysis diagnostics mean?

- `invalid-utf8`: malformed bytes were replaced with U+FFFD so analysis could
  remain deterministic. Repair the source file encoding.
- `file-too-large`: the file exceeded `:analysis :max-file-bytes`. Increase the
  limit only when the file is genuinely source that should be indexed.
- `binary-file`: a supported extension contained binary data and was skipped.
- `grammar-unavailable`: the file language was recognized but its parser was
  unavailable.
- `missing-include`: an `:analysis :include` path does not exist.
- `clj-kondo`: the analyzer reported a source-level finding at the displayed
  location.
- `semantic-file-failed` or `semantic-reconciliation-stale`: semantic document
  preparation was deferred; inspect `llm-context semantic dirty`.

Unsupported extensions are ignored by design and do not produce diagnostics.

## Why can re-frame topic queries be incomplete without an analysis warning?

Topic extraction is advisory enrichment layered on the canonical symbols and
references. If its safe form reader cannot parse a source file, analysis
preserves the base graph, omits topic facts from that reader pass, and emits a
`topic-analysis-skipped` diagnostic with the file and bounded reader error.

Compare topic-query results with ordinary symbol/reference queries and inspect
reader conditionals or custom tagged literals in the affected file. Missing
topic enrichment does not mean the file's base symbols were omitted.

## Why are no files discovered or expected files missing?

Confirm that `llm-context init` was run at the repository root and inspect
`:analysis :include`, `:analysis :exclude`, Git ignore rules, supported file
extensions, and `:analysis :max-file-bytes`.

Run `llm-context analyze --check` to validate the source snapshot without
changing the graph. Unknown extensions are outside the analyzer contract.

## Why did file watching stop updating the graph?

First run `llm-context analyze` manually. Status now reports watcher health.
Unexpected watcher failure recreates all recursive registrations and schedules
a catch-up scan. If recovery remains unsuccessful, inspect
`.llm-context/logs/service.log` and run:

```bash
llm-context service stop
llm-context analyze
```

For a whole coordinator-process crash, use the host-native supervisor definition
documented in the user guide; in-process recovery cannot restart its own JVM.

## What happens when disk space or generated-state growth is unsafe?

Analysis and semantic indexing check both minimum free space and maximum growth
for one operation. They stop before the next bounded write when a configured
limit is exceeded.

Inspect project-owned generated storage with:

```bash
llm-context maintenance status
```

Cleanup is dry-run by default:

```bash
llm-context maintenance cleanup --older-than-days 30
llm-context maintenance cleanup --older-than-days 30 --apply
```

On WSL, the default free-space probe uses `/mnt/c` because the Linux VHDX can
report a misleading virtual ceiling. Configure `:store
:free-space-probe-path` when the VHDX resides on another host drive.

## What happens after an interrupted analysis or graph-format upgrade?

A normal `llm-context analyze` detects an empty, incompatible, or interrupted
graph replacement and performs the required guarded full rebuild. Complete,
compatible staging generations may be resumed; partial or corrupt staging data
is ignored and recomputed.

Force a rebuild only when needed:

```bash
llm-context analyze --full
```

The previous committed graph is not replaced until the new graph passes its
validation boundary.

## Which failures currently self-heal?

The following recovery is automatic:

- stale service advertisements are reclaimed after lock ownership is checked;
- incompatible or interrupted graph replacements trigger a safe rebuild;
- expired semantic leases are returned to the queue;
- missing semantic reconciliation markers are reconstructed; and
- invalid resumable-analysis caches are ignored and recomputed;
- CUDA runtime failure under `:auto` restarts a verified CPU/INT8 runtime;
- provider outages pause leasing and restart the semantic runtime/worker without
  exhausting document retry budgets;
- sidecar, worker, watcher, query-router, and reranker lifecycles are supervised;
- known infrastructure failures from older releases are reconciled after
  verified recovery; and
- incompatible resident services are safely replaced by `analyze` or
  `service start`.

The following require operator action:

- explicit CUDA failure or host driver/CUDA/cuDNN repair;
- deterministic terminal document failures;
- invalid configuration, model/index corruption, or unsafe growth;
- freeing disk space when the configured reserve is reached; and
- a whole coordinator crash when no host supervisor is installed.

Health alerts remain in `.llm-context/health.edn` and are surfaced by later CLI
commands. Index deletion remains an explicit last resort after preserving logs
and confirming provider-index corruption.

## What should I include in a bug report?

Include:

- `llm-context version`;
- operating system and whether WSL is involved;
- `llm-context doctor` output;
- `llm-context semantic status --verbose`;
- the relevant bounded tail of `service.log` and `next-plaid.log`;
- the exact command and approximate time of the failure; and
- whether the CLI or service was upgraded or restarted shortly beforehand.

Do not include `.llm-context/service.edn`, complete source documents, or other
secrets. Failure records and logs are intentionally bounded, but review them
before sharing.
