# ARD: Operational health, notification, and bounded self-healing

- Date: 2026-08-18
- Status: accepted and implemented; hardware-specific WSL CUDA qualification
  remains a release-environment gate
- Scope: resident service, analysis watcher, semantic runtime and worker, query
  router/reranker, accelerator selection, status, doctor, upgrades, and
  operator-facing recovery

## Context

`llm-context` already protects canonical graph correctness with atomic graph
replacement, graph-format validation, durable semantic jobs, leases, visibility
verification, generation watermarks, storage guards, and stale service-lock
recovery. Those mechanisms preserve data, but they do not form an operational
control plane.

The cl-viz incident exposed the gap. Static CUDA checks passed because a GPU,
driver, CUDA libraries, cuDNN, ONNX providers, and the FP32 model were present.
The NextPlaid process loaded an incompatible native Linux `libcuda.so.1` instead
of the WSL driver proxy, failed CUDA initialization, and fell back internally to
CPU. The parent process retained the CUDA/FP32 profile with one encoding session
and one update request. Provider calls timed out, every affected document spent
its retry budget independently, and 1,083 jobs became terminal failures.

The failure was durable but operationally obscure:

- the NextPlaid HTTP health endpoint was ready after CPU fallback;
- runtime state continued to describe CUDA selection;
- the worker thread remained alive and reported `:running` after the queue had
  no runnable work;
- concise status calculated remaining documents as `desired - indexed`, thereby
  relabeling terminal failures as pending work;
- cumulative throughput remained visible after progress stopped;
- the detached daemon had no notification channel back to the original shell;
- an older resident service could survive a CLI upgrade because descriptors and
  requests carried no application/protocol version; and
- recovery required the operator to correlate verbose status, two logs,
  process mappings, WSL driver layout, and release history.

Similar ownership gaps exist for a sidecar that dies after startup, a worker or
watcher future that exits, a query router that becomes unavailable, watched
analysis that repeatedly fails, storage failures that later become recoverable,
and broad lexical/advisory fallbacks that preserve availability without clearly
reporting reduced capability.

## Problem statement

The system records facts about failures but lacks one component that owns this
closed loop:

```text
detect -> classify -> publish -> recover -> verify -> clear or escalate
```

Recovery is currently local and incomplete:

- job retry does not distinguish a provider outage from a poison document;
- expired leases recover only when a worker is running again;
- the coordinator starts child components once but does not supervise them;
- static accelerator eligibility is mistaken for runtime success;
- status fields describe thread/process existence rather than useful progress;
- doctor checks installation presence more thoroughly than live project health;
- warning rendering is command-specific and can swallow status-probe failures;
- no durable health transition allows later CLI commands to report a failure
  that occurred while no terminal was attached; and
- safe fallback is sometimes presented as healthy operation.

## Goals

1. Publish one authoritative, project-scoped health snapshot derived from
   canonical state, runtime state, and recent progress.
2. Make concise and verbose status agree on pending, leased, failed, dirty,
   coverage, and current activity.
3. Detect runtime fallback, child death, worker failure, watcher failure,
   terminal jobs, stalled progress, version skew, and enabled optional-component
   failure without requiring log archaeology.
4. Automatically recover failures that are safe and idempotent to replay.
5. Preserve fail-closed behavior for explicit CUDA, invalid configuration,
   deterministic source errors, index corruption, and unsafe storage.
6. Prevent provider-wide failures from exhausting every document's retry budget.
7. Make every user-facing command capable of reporting unresolved project health
   transitions without requiring a permanently attached terminal.
8. Make `doctor` a robust live diagnostic: configured components that are broken
   must affect its result even if installing those components was optional.
9. Detect resident-service application/protocol skew and recover it safely.
10. Add fault-injection tests that prove recovery reaches a verified healthy
    state rather than merely restarting a process.

## Non-goals

- Automatically installing or replacing NVIDIA display drivers.
- Deleting a corrupt provider index without preserving evidence.
- Retrying deterministic source/document failures forever.
- Making semantic retrieval a dependency of canonical graph queries.
- Sending source, health, or failure information to a remote telemetry service.
- Using desktop notifications as the only notification path.

## Decision

### 1. One health vocabulary

Every configured component reports one of these states:

- `:disabled`: deliberately not configured;
- `:starting`: initial startup has not completed;
- `:healthy`: ready and satisfying its current contract;
- `:indexing`: healthy and actively completing queued work;
- `:degraded`: usable fallback exists, but configured capability is reduced;
- `:stalled`: outstanding work exists and no forward progress occurred within
  the derived stall window;
- `:recovering`: a supervisor is applying bounded automatic recovery;
- `:failed`: recovery is unsafe, exhausted, or requires operator action; and
- `:unknown`: current state cannot be established safely.

The project health snapshot contains:

```clojure
{:state :healthy|:indexing|:degraded|:stalled|:recovering|:failed|:unknown
 :observed-at epoch-ms
 :summary "bounded operator-facing text"
 :components
 {:service {...}
  :graph {...}
  :analysis {...}
  :watcher {...}
  :semantic-runtime {...}
  :semantic-worker {...}
  :semantic-queue {...}
  :accelerator {...}
  :query-router {...}
  :candidate-reranker {...}
  :storage {...}}
 :alerts [{:id stable-id
           :severity :warning|:error
           :component keyword
           :kind keyword
           :since epoch-ms
           :detail string
           :action string
           :self-healing? boolean}]}
```

Health is a reduction over component states. `:failed` wins, followed by
`:stalled`, `:recovering`, `:degraded`, `:indexing`, and `:healthy`. Disabled
components do not reduce project health. A configured component is never
ignored merely because its installation was optional.

The status RPC computes a fresh snapshot. Runtime state also retains stable
transition timestamps and recovery counters so repeated polls do not invent new
alerts.

### 2. Status means queue state, not arithmetic coverage

Concise semantic status uses the durable queue fields directly:

- `:pending` is runnable pending work;
- `:leased` is in-flight work;
- `:failed` is terminal work;
- `:dirty` is deferred reconciliation work; and
- `:indexed`/`:desired` express coverage, not queue state.

The concise line reports all nonzero exceptional states. It never substitutes
`desired - indexed` for `pending`.

Worker progress records `:last-progress-at` whenever documents complete,
provider work is accepted, or a retry/recovery transition occurs. Throughput is
reported as both cumulative and recent-window telemetry when available. If
pending or leased work exists and progress is older than the stall window,
health becomes `:stalled`. If no runnable work exists but terminal failures do,
the state is `:failed`, not stalled or running.

### 3. Accelerator selection requires an execution probe

Static checks remain an eligibility preflight. Process startup is the final
probe.

- Explicit `:cuda` remains fail-closed on any static or runtime failure.
- `:auto` may launch CUDA only when static prerequisites pass.
- If startup logs show provider registration failure, CPU fallback, or no CUDA
  device, the coordinator terminates that process and starts a new CPU/INT8
  process using CPU sessions and update concurrency.
- The recovered runtime reports requested `:auto`, effective `:cpu/:int8`, the
  CUDA failure reason, and a successful recovery transition.
- The worker is not started until the effective runtime profile is verified.
- On WSL, an eligible CUDA child prepends the WSL driver-proxy directory to its
  child-only loader path. Static discovery and process resolution must use the
  same ordered directories.
- A NextPlaid process that internally fell back is never published as a healthy
  CUDA runtime.

### 4. Provider failures use a circuit breaker

Errors are classified before document retry:

- infrastructure: connection failure, HTTP timeout, child exit, failed health,
  provider initialization, visibility failure affecting a whole batch, memory
  exhaustion, or unavailable storage;
- transient document race: source changed, lease superseded, or graph revision
  changed;
- deterministic document failure: invalid operation, repeatable document
  construction failure, or provider rejection tied to one document; and
- unsafe/corrupt: model/index generation mismatch that cannot be rebuilt safely,
  index corruption, invalid configuration, or storage safety violation.

Infrastructure failures open the provider circuit. The current batch is
returned to pending without becoming terminal, new leasing pauses, and the
semantic supervisor restarts or revalidates the runtime with exponential
backoff capped by the configured retry maximum. After health and index
generation are verified, leases are recovered and processing resumes.

Document retry budgets apply only to document-scoped failures. A single provider
outage cannot consume thousands of independent poison-record budgets.

Existing terminal jobs whose bounded error text identifies a known
infrastructure failure are automatically marked dirty and reconciled once after
verified runtime recovery. Unknown/deterministic failures remain terminal and
actionable through `semantic failures` and `semantic retry --failed`.

### 5. The coordinator supervises component lifecycles

The project coordinator owns supervisor loops for:

- semantic NextPlaid runtime and worker;
- query router and candidate reranker runtime; and
- filesystem watcher.

Each supervisor:

1. publishes `:starting` or `:recovering` before an attempt;
2. starts the component;
3. verifies its component-specific readiness contract;
4. publishes `:healthy`/`:indexing` only after verification;
5. observes child/future completion and periodic health;
6. stops the old component before replacement;
7. retries replay-safe failures with bounded exponential backoff; and
8. publishes an actionable `:failed` state when automatic recovery is unsafe.

The semantic worker performs periodic provider health checks even when the job
queue is idle. Query requests continue using canonical FTS while semantic or
router recovery is in progress.

Watcher failure cannot disappear inside an unobserved future. The supervisor
recreates the recursive watch registration and schedules one full incremental
scan after recovery so events lost during downtime cannot leave the graph
stale. Watched-analysis failure is recorded in health and durable analysis
progress while the watcher remains available for later changes.

Host-native systemd/launchd/Windows supervision remains responsible for a whole
coordinator process crash. In-process supervision handles child and future
failure while the coordinator remains alive.

### 6. Service descriptors and RPC expose compatibility

Service descriptors include:

```clojure
{:application-version "x.y.z"
 :protocol-version 1
 :started-at epoch-ms
 ...}
```

The RPC exposes `:service-info` with the same identity. Clients distinguish:

- no service;
- unreachable stale advertisement;
- busy or timed-out live service;
- compatible service; and
- application/protocol mismatch.

`analyze` and `service start` safely replace a mismatched service: request a
graceful stop, verify exact advertised PID exit, and launch the current CLI.
Read-only commands report the mismatch and corrective action rather than
silently using an older contract. A service is never replaced while a request
cannot establish ownership safely.

### 7. Notification is durable and command-boundary based

A detached service cannot write to a shell that no longer exists. Therefore,
notifications are based on persistent health transitions, not terminal
attachment.

- Every status response includes active alerts.
- `analyze`, `semantic status`, `service status`, and `doctor` always render
  unresolved warning/error transitions.
- Other project commands render a compact one-line health banner when a new or
  unresolved error transition exists, unless `--quiet` was requested.
- Watch mode prints an alert only when its stable identity or severity changes.
- Logs retain structured transition and recovery events.
- Optional future desktop/webhook integrations consume the same local alert
  contract; they are not required for correctness.

Alerts contain paths, IDs, counts, timings, and bounded error text. They do not
contain source documents, service tokens, or model request bodies.

### 8. Doctor validates installation and live operation

`doctor` reports separate checks for:

- Java, writable project, parser resources, Datalevin, and graph format;
- storage capacity and configured growth limits;
- semantic executable, ONNX libraries, pinned model checksums, and accelerator
  static readiness;
- actual semantic runtime profile and runtime diagnostic;
- semantic queue counts, coverage, watermark, current progress age, and stall;
- worker state and recovery attempts;
- watcher state and last watched-analysis result;
- query router and candidate reranker state;
- service endpoint reachability, ownership, version, and protocol; and
- interrupted/unreadable analysis progress.

Requiredness is contextual:

- canonical graph prerequisites are always required;
- a disabled component is informational;
- an enabled component with no initialized graph may be reported as not started;
- an enabled component with queued/indexed state or a running service is
  operationally required; and
- degraded, stalled, failed, incompatible, or terminal state makes doctor exit
  nonzero.

Doctor never uses `available?` as the sole health result or collapses every
failed ping to “not running.” It preserves busy, timeout, unreachable,
protocol, and version states.

### 9. Availability fallbacks remain explicit

Canonical graph queries remain available during semantic recovery. Hybrid
search, intent routing, and learned reranking retain their safe fallback
behavior, but normal output reports the fallback once per request. Explanation
output retains the structured cause.

Broad exception catches around full-text and advisory source readers are
narrowed:

- invalid user full-text syntax may fall back to literal matching with explicit
  provenance;
- Datalevin/storage failures propagate as failures;
- aggregate and topic reader failures preserve base graph facts but emit bounded
  diagnostics; and
- corrupt resumable caches may self-heal by recomputation while recording that
  recovery in analysis diagnostics/progress.

### 10. Data safety boundaries

Automatic recovery may restart processes, retry idempotent work, recover leases,
reconcile current documents, and recreate watchers. It may not:

- delete the canonical graph;
- activate an unverified graph or provider generation;
- delete a corrupt provider index without preserving it for diagnosis;
- install system drivers;
- ignore minimum-space or growth guards;
- turn ambiguous semantic candidates into graph edges; or
- retry an unknown deterministic poison record forever.

## Failure-policy matrix

| Failure | User-visible state | Automatic action | Escalation |
|---|---|---|---|
| CUDA runtime failure under `:auto` | recovering, then degraded recovery notice | restart CPU/INT8 | fail if CPU startup fails |
| CUDA runtime failure under `:cuda` | failed | none | correct host/config or select CPU |
| Sidecar exits/unhealthy | recovering | pause leasing, restart, verify, recover leases | failed after unsafe error; otherwise capped backoff |
| Provider timeout/outage | recovering | open circuit; do not exhaust jobs | report persistent recovery attempts |
| Deterministic document failure | degraded/failed queue | bounded document retries | terminal failure with file/symbol |
| Worker future exits | recovering | restart runtime/worker and reconcile | failed on unsafe error |
| Watcher exits | recovering | recreate watcher and run catch-up analysis | failed with manual analyze action |
| Watched analysis fails | degraded | retain watcher; retry on next change/catch-up | show durable analysis error |
| Query router/reranker exits | degraded/recovering | restart advisory runtime | canonical/FTS fallback remains |
| Terminal infrastructure jobs from older release | recovering | retry once after verified provider recovery | retain unknown failures |
| Storage reserve/growth limit | failed | pause writes | free/configure storage, then restart |
| Old service version | incompatible | analyze/start performs verified replacement | read-only commands report action |
| Whole coordinator crash | unavailable | host supervisor restarts if installed | next command reclaims stale endpoint |
| Index corruption | failed | preserve evidence | explicit rebuild command |

## Testing strategy

Tests must exercise transitions, not only message formatting:

1. CUDA auto launch emits a fallback log, process is stopped, CPU/INT8 starts,
   worker receives CPU concurrency, and health becomes healthy.
2. Explicit CUDA runtime failure remains failed and never starts CPU.
3. WSL child environment orders the driver proxy before native Linux driver
   directories.
4. A provider timeout opens the circuit without making jobs terminal.
5. Sidecar death while idle is detected and restarted.
6. A worker exception causes bounded supervisor recovery and lease replay.
7. Known infrastructure terminal jobs are automatically retried once after
   verified recovery; deterministic failures are retained.
8. A watcher future exception recreates registrations and triggers catch-up
   analysis.
9. Concise status reports exact pending/leased/failed/dirty counts and identifies
   stale telemetry as stalled.
10. Doctor exits nonzero for runtime fallback, terminal jobs, degraded
    watermark, stalled progress, failed worker/watcher/router, storage failure,
    and version/protocol mismatch.
11. A compatible healthy project keeps doctor green.
12. An uninitialized project can run doctor before its first analysis without a
    false operational failure.
13. Existing v0.12.4 graph and semantic state opens without migration loss.
14. Query and context commands retain canonical/FTS availability during all
    semantic recovery states and report their fallback.

The full test suite, packaged-JAR quality gate, and installed-runtime smoke test
must pass. Live qualification requires one forced CPU run and one WSL CUDA run
with `pending=leased=failed=dirty=0`, complete coverage, a valid current
watermark, and clean process shutdown.

## Rollout and compatibility

1. Add health calculation and exact status reporting without changing graph
   schema.
2. Add descriptor/RPC version fields while accepting old descriptors only long
   enough to identify and replace their service safely.
3. Add CUDA execution fallback and WSL loader ordering.
4. Add provider circuit breaking and semantic supervision.
5. Add watcher/router supervision and command-boundary alerts.
6. Harden doctor and enable contextual nonzero results.
7. Update the FAQ and user guide to remove limitations that are now repaired.

No graph-format bump is required because health and compatibility state are
runtime/descriptor data and existing semantic failure classification uses
bounded error text for the one-time legacy recovery path. A future schema may
store structured failure kinds, but this release does not require rebuilding
canonical project data.

## Alternatives rejected

- **Only improve log messages.** Detached logs do not notify users and do not
  recover components.
- **Retry every failed document forever.** Provider outages would create retry
  storms, while poison documents would never become actionable.
- **Trust NextPlaid health after CUDA fallback.** HTTP readiness does not prove
  the requested execution provider is active.
- **Treat all semantic checks as optional in doctor.** Configuration is an
  operational commitment; enabled broken components must affect health.
- **Restart the whole coordinator for every child failure.** It disrupts graph
  queries and watcher ownership unnecessarily; child supervision is narrower.
- **Automatically delete provider indexes.** Corruption and generation mismatch
  require preserved evidence and an explicit destructive boundary.
- **Rely only on systemd/launchd.** Host supervision can restart the coordinator
  but cannot classify document failures or safely recover child state.
- **Send remote telemetry.** Project health can be diagnosed and surfaced
  locally without transmitting code or operational data.

## Acceptance criteria

The implementation is complete when:

- the reproduced WSL CUDA failure either runs verified CUDA or automatically
  restarts as CPU/INT8 under `:auto`;
- no provider-wide outage can turn an entire healthy queue into terminal jobs;
- child, worker, watcher, and advisory-runtime failures become visible health
  transitions and recover when replay is safe;
- concise status cannot label terminal failures as pending;
- stale throughput cannot imply current progress;
- doctor reports every enabled component and exits nonzero for unresolved
  operational degradation;
- an old resident service is detected and safely replaced by `analyze`/`service
  start`;
- users see unresolved alerts on later commands even when the failure happened
  in a detached process; and
- fault-injection, full-suite, packaged-release, CPU, and WSL CUDA validation all
  pass without weakening graph, generation, lease, or storage guarantees.
