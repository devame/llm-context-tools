# llm-context

`llm-context` builds a persistent semantic graph of a source repository and
turns focused neighborhoods of that graph into compact context for AI coding
assistants.

The application core is Clojure. [Datalevin](https://datalevin.org/) is the
authoritative embedded database and Datalog query engine. The analyzer is
deliberately focused on Clojure, ClojureScript, CLJC, Janet, and selected EDN
configuration. A local LateOn-Code
sidecar adds multi-vector semantic retrieval without sending source code to a
remote service.

## Why a graph?

Source files are not independent documents. Symbols call, contain, import,
reference, and implement other symbols. Persisting those facts in Datalevin
makes reverse relationships, transitive reachability, ambiguity, and deletion
correctness first-class instead of rebuilding ad-hoc JSON indexes for every
question.

Traversable relationships are exact in-project facts with analyzer evidence.
External library calls, dynamic calls, ambiguous names, and unresolved names
are stored separately as non-traversable references. Naming guesses can
therefore never enter callers, callees, context traversal, or graph paths.

## Requirements

- JDK 23 or newer. JDK 25 is used for development and release validation.
- Clojure CLI 1.12+ when running from source.
- The bundled LateOn runtime supports Linux x86-64, macOS Apple Silicon,
  and Windows x86-64. Exact graph analysis works elsewhere.

## Install

Linux and macOS:

```bash
curl -fsSL https://raw.githubusercontent.com/devame/llm-context-tools/main/install.sh | sh
```

Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/devame/llm-context-tools/main/install.ps1 | iex
```

Both installers require Java 23 or newer and verify every downloaded release
artifact. By default they install the jar, NextPlaid API 1.6.4, ONNX Runtime
1.23.0, the immutable INT8 LateOn-Code snapshot, and the immutable 32M
Mixedbread query router. The model downloads total about 187 MB (154 MB plus
33 MB). No Docker, Python, Rust, Datalevin server, separate clj-kondo
executable, Janet executable, or model manager is required.

Executables are installed once per user (`~/.local/bin` on Unix and the local
application-data Programs directory on Windows), and the immutable model is
cached once per user. Repository-derived data is not stored there: every
project owns its graph and semantic index below its own `.llm-context/`
directory. Open a new terminal after an installer changes `PATH`.

Set `LLM_CONTEXT_VERSION=0.12.0` to pin a release or
`LLM_CONTEXT_INSTALL_DIR` to choose another destination. The installers are
idempotent: running them again replaces the jar and launcher only after
checksum validation and reuse a verified model snapshot. Set
`LLM_CONTEXT_MODEL_CACHE` to relocate the model, or
`LLM_CONTEXT_SKIP_SEMANTIC=1` for a graph-only installation.

All model packages are replaceable through one verified manifest contract.
Set `LLM_CONTEXT_MODEL_MANIFEST` to a local EDN manifest,
`LLM_CONTEXT_MODEL_MANIFEST_SHA256` to its SHA-256, and
`LLM_CONTEXT_MODEL_ROLES` to a comma-separated selection of
`semantic-retriever`, `query-router-reranker`, and `answer-reader`. Custom
manifests without a checksum, mutable revisions, and model files that do not
match their declared hashes are rejected. The answer reader is opt-in because
the core CLI does not require its approximately 731 MB GGUF; demo deployments
can select all three roles.

Re-run the installer to update, or remove the installed directory to uninstall.

After installation:

```bash
llm-context doctor
llm-context init [--yes]
llm-context service start
llm-context semantic sync --wait
llm-context query search "where is authentication handled?"
```

## Quick start from source

```bash
clojure -M -m llm-context.main doctor
clojure -M -m llm-context.main init
clojure -M -m llm-context.main analyze
clojure -M -m llm-context.main query stats
clojure -M -m llm-context.main query find-symbol run
clojure -M -m llm-context.main context run --max-tokens 4000
```

Build and run the distribution jar:

```bash
clojure -T:build dist
java --enable-native-access=ALL-UNNAMED -jar dist/llm-context.jar help
```

For local npm-based development, the repository package is a thin launcher
around the same jar:

```bash
npm pack
npm install --global ./llm-context-0.9.0.tgz
llm-context doctor
```

The public npm name `llm-context` is not controlled by this project. Use the
one-script installer for normal installations rather than installing that
unrelated registry package.

## Commands

```text
llm-context init [--yes]
llm-context doctor
llm-context analyze [--full|--check]
llm-context query stats
llm-context query find-symbol <name-or-id>
llm-context query search <natural-language-query> [--mode fts-only|lateon-only|hybrid] [--source-preference auto|production|test|none] [--semantic-timeout-ms N] [--intent-rerank] [--explain]
llm-context query callers <symbol-id>
llm-context query callees <symbol-id> [--include-external]
llm-context query trace <symbol-id> [--depth N] [--limit N]
llm-context query entry-points
llm-context query effects
llm-context query unresolved [--classification unresolved|ambiguous|dynamic|external]
llm-context query topics|registrations|dispatchers|subscribers
llm-context query state-readers|state-writers
llm-context context <name-or-id> [--depth N] [--max-tokens N]
llm-context context --intent <natural-language-query> [--source-preference auto|production|test|none] [--semantic-timeout-ms N] [--seed-mode auto|single|multi] [--max-seeds N] [--depth N] [--max-tokens N]
llm-context export --format edn|json|jsonl|markdown [--output PATH]
llm-context summary [--output PATH]
llm-context integrate claude|codex|generic [--force]
llm-context semantic status [--verbose]
llm-context semantic status --watch [--interval-ms N] [--verbose]
llm-context semantic sync [--wait]
llm-context semantic failures
llm-context semantic dirty
llm-context semantic retry --failed [--wait]
llm-context maintenance status
llm-context maintenance compact-copy [--output PATH]
llm-context maintenance cleanup --older-than-days DAYS [--apply]
llm-context service start|status|stop
```

`find-symbol` and the `fts-only` search mode use Datalevin's embedded full-text
index across symbol names, qualified names, signatures, and documentation.
This search is local and model-free. `lateon-only` uses only
freshness-validated local LateOn candidates and does not fall back to lexical
results. The default `hybrid` mode fuses lexical results with the local LateOn
multi-vector index. It preserves exact identifiers, rejects semantic
candidates whose content hash or model revision is stale, and falls back to
Datalevin whenever the sidecar is unavailable.

`context --intent` starts with shape-neutral, freshness-safe hybrid retrieval.
In parallel, a resident 32M Mixedbread model scores lookup, set, and flow answer
shapes. The model is advisory: llm-context accepts a flow only when at least
two structurally qualified roots have an exact call or macro-invocation edge,
accepts a set only when several roots structurally qualify, and otherwise
retains an adaptive multi-root plan. Lexical and semantic relevance may reorder
candidates but cannot qualify them. Explicit
`--seed-mode` choices remain authoritative. The model never filters the
candidate pool, and its three scores, margin, latency, revision, structural
support, and final planning authority remain inspectable in retrieval
provenance. The router reuses NextPlaid and a 33 MB INT8 ONNX artifact; it does
not require Python or start a model per query.

After source-role policy, the same 32M ColBERT model reranks a bounded candidate
prefix against the unchanged question. It defaults to `:shadow` until the model
passes the frozen reranking suite; `:enforce` applies its ordering.
Deterministic code then annotates exact
structural evidence without changing model order. A model timeout or failure
preserves fused order and is visible in provenance. Accepted lookup plans
select one seed; set and flow plans select
bounded, file-diverse roots under one shared traversal and token budget. Every
relationship admitted afterward is still an exact canonical graph edge.
Without either resident model, retrieval falls back to Datalevin and planning
stays adaptive. Set packets also carry a compact, explicitly bounded inventory
of structurally qualified candidates; inventory entries do not become
traversal roots or inferred graph relationships.
Packets distinguish `:structural-evidence`, `:relevance-only`, and
`:no-evidence`, and disclose whether roots came from structural selection, a
relevance fallback, or the original rank.
Intent context defaults to `--source-preference auto`: ordinary implementation
questions stably prefer production paths, while requests explicitly about
tests prefer test paths. Exact identifier matches retain priority, scores are
never rewritten, and the packet records fused rank, final rank, source role,
and the resolved preference. `query search` defaults to `none` for backward-
compatible general-purpose search ordering.

The semantic deadline comes from
`:semantic/:lateon-code/:query-timeout-ms` and can be overridden per request
with `--semantic-timeout-ms`. Retrieval provenance reports the effective
deadline, latency, status, fallback, query plan, reranker status, and selected
root count. `--seed-mode single` preserves historical one-root behavior;
`--seed-mode multi` explicitly requests bounded multi-root selection.

`analyze` runs embedded clj-kondo once over the complete Clojure source set and
a two-pass Tree-sitter AST/module resolver over Janet. Static Clojure topic
forms are read with evaluation disabled; dynamic forms remain diagnostic
observations instead of becoming graph facts. Analysis never invokes Clojure,
Leiningen, shadow-cljs, Janet, project build tools, or project macros. Per-file
semantic fingerprints make subsequent runs incremental while still updating
unchanged callers when cross-file resolution changes. The default scan covers
the whole confirmed project root and silently ignores unsupported extensions.
Full analysis reports timestamped stages and persists in transactions of at
most 100 records.

`analyze --check` runs the same discovery, analyzers, canonicalization, and
whole-snapshot integrity audit without opening or changing the graph database.
Use it as a read-only source validation gate.

## Configuration

`llm-context.edn` is the only configuration format:

```clojure
{:analysis
 {:include ["."]
  :exclude [".git" ".llm-context" "node_modules" "target" ".cpcache"
            "dist" "build" "out" ".shadow-cljs" ".cljs_node_repl" ".lsp"]
  :max-file-bytes 1048576}

 :store {:path ".llm-context/db"
         :max-transaction-weight 4000
         :minimum-free-space-bytes 10737418240
         :free-space-probe-path nil}

 :service {:watch true
           :watch-initial true
           :watch-debounce-ms 750
           :request-threads 4
           :request-queue-capacity 32}

 :semantic
 {:providers [:lateon-code]
  :lateon-code
  {:enabled true
   :mode :background
   :next-plaid-version "1.6.4"
   :model "lightonai/LateOn-Code"
   :model-revision "734b659a57935ef50562d79581c3ff1f8d825c93"
   :quantization :int8}}

 :context
 {:default-max-tokens 8000
  :trace-depth 4
  :trace-limit 200
  :candidate-reranker
  {:enabled true
   :mode :shadow
   :candidate-count 50
   :query-timeout-ms 5000
   :document-cache-size 2048}
  :query-router
  {:enabled true
   :model "mixedbread-ai/mxbai-edge-colbert-v0-32m"
   :model-revision "963e23afa1478d8bcc12e5d7115adcfdbd22c3af"
   :query-timeout-ms 250
   :minimum-margin 0.02}}}
```

Set `:providers []` for a graph-only installation. There is intentionally
no JSON configuration or persisted-data migration layer in this greenfield
release.

Generated graph and semantic-index writes stop before usable space falls below
`:store/:minimum-free-space-bytes` (10 GiB by default). The check runs before
full analysis and before every graph or semantic ingestion batch. Set
`:store/:free-space-probe-path` when generated state is backed by a different
filesystem. When it is `nil`, native systems probe the database filesystem;
WSL probes `/mnt/c` because ext4 reports the thin-provisioned VHDX ceiling, not
the Windows host capacity. Override the path for a WSL distribution stored on
another Windows drive. A value of `0` disables the reserve but not the check.

## Language support

Supported source files are `.clj`, `.cljs`, `.cljc`, and `.janet`. Selected
`deps.edn`, `bb.edn`, `shadow-cljs.edn`, and `.clj-kondo/config.edn` files are
indexed as configuration data. Other source extensions are intentionally and
silently ignored.

## Persistent data and exports

The project database lives under `.llm-context/db/`. Datalevin is the only
source of truth. The disposable LateOn index lives under
`.llm-context/semantic/next-plaid/`; the three-document router index lives
under `.llm-context/query-router/next-plaid/`. JSONL, JSON, EDN, and Markdown
are deterministic projections for interoperability, debugging, and artifacts.

Current builds use graph format 4 and semantic document/index format 4. In
addition to exact symbols and relationships, safe top-level literal
collections are stored as source-backed aggregates with explicit membership
and completeness; namespace/module containers provide deterministic coarse
summaries. Existing generated graphs must be
rebuilt once with `llm-context analyze --full`; source and configuration files
are never changed. Normal queries detect older state and print this instruction
instead of opening it as if it were current. Complete-inventory questions only
qualify when a complete source-backed aggregate is present.

## Resident service

Cold JVM startup remains materially slower than warm Datalog and model queries.
Start the detached project coordinator once:

```bash
llm-context service start
```

The coordinator watches included source trees, debounces changes, runs the same
semantic-fingerprint analyzer as the CLI, drains durable LateOn jobs in the background,
and supervises loopback-only NextPlaid children for retrieval and advisory
query planning. Query, context, and export
commands automatically use the warm service. A random token in the ignored
`.llm-context/service.edn` descriptor authenticates local requests. Structural
commands and lexical search remain available when the model is loading or
unavailable.

The service handles query, context, status, and export requests through a
bounded request pool. A slow request therefore does not block health checks or
other clients. When the pool is saturated the service returns an explicit busy
response, and clients report timeouts or unreachable endpoints instead
of silently opening the project database behind the resident service.

On Linux and macOS, project clients connect through the owner-only
`.llm-context/service.sock` Unix-domain socket. This lets shells or containers
in different network namespaces share the service when they share the project
filesystem. Windows uses authenticated loopback TCP. An OS file lock prevents
two service processes from owning the same project.

The descriptor and Unix socket are advertisements, not ownership records: an
abrupt process or JVM failure can leave them behind. On the next command,
`llm-context` removes an unreachable or malformed advertisement only after it
acquires the OS service lock and confirms that no service owns the project.
Timeouts are never reclaimed automatically because they can represent a live,
busy service. Descriptor publication is atomic, normal JVM shutdown removes
only the current instance's files, and `service stop` is idempotent.

Use `llm-context semantic status` to inspect lag and
`llm-context semantic status --watch` to monitor a full or incremental graph
replacement from another terminal. The default output is one concise line with
remaining documents, total documents, and processing speed; add `--verbose`
for the complete runtime and progress map. Status remains available while the
graph is being committed: verbose mode reports the last committed counters plus
the live analysis stage. The service also writes an atomic
`.llm-context/analysis-progress.edn` snapshot, and marks a previously running
operation as interrupted when the service restarts.
`llm-context semantic sync --wait` when automation needs all queued embeddings
visible before continuing. Logs are under `.llm-context/logs/`; they contain
identifiers and bounded errors, not source documents.

`llm-context maintenance status` reports size, file count, and latest
modification time for an explicit allowlist of project-owned graph, recovery,
semantic, router, staging, maintenance, and log paths. It never scans source
files, follows symlinks, or deletes artifacts.

`llm-context maintenance compact-copy` asks Datalevin to create a compact copy
under `.llm-context/maintenance/`, then opens the copy and verifies graph
metadata plus canonical and operational identity counts. `--output` selects a
different empty destination. The command never activates or deletes a copy.

`maintenance cleanup` is a dry run unless `--apply` is present. It considers
only direct-child recovery archives and verified compact copies carrying an
exact llm-context retention marker. It always keeps the newest artifact in
each category and ignores unmarked directories, provider indexes, and active
logs.

## Development

```bash
clojure -M:test
clojure -M:bench 50
clojure -M:bench 500
clojure -T:build dist
scripts/verify-release-quality.sh dist/llm-context.jar
clojure -M:semantic-bench /path/to/project queries.edn
npm pack --dry-run
```

Maintainers can reproduce the embedded Janet grammar libraries with Zig 0.15+
by running `script/build-janet-grammar.sh`. The pinned source revision and
license are recorded in `resources/llm_context/native/JANET_GRAMMAR.md`.

See [architecture and tradeoffs](docs/architecture.md),
[semantic graph model](docs/semantic-graph.md), and
[benchmark methodology](docs/benchmarks.md). The complete project workflow is
in the [user guide](docs/user-guide.md).
