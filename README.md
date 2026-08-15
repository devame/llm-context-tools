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
1.23.0, and the five files needed by the immutable INT8 LateOn-Code snapshot.
The model download is about 154 MB. No Docker, Python, Rust, Datalevin server,
separate clj-kondo executable, Janet executable, or model manager is required.

Executables are installed once per user (`~/.local/bin` on Unix and the local
application-data Programs directory on Windows), and the immutable model is
cached once per user. Repository-derived data is not stored there: every
project owns its graph and semantic index below its own `.llm-context/`
directory. Open a new terminal after an installer changes `PATH`.

Set `LLM_CONTEXT_VERSION=0.11.0` to pin a release or
`LLM_CONTEXT_INSTALL_DIR` to choose another destination. The installers are
idempotent: running them again replaces the jar and launcher only after
checksum validation and reuse a verified model snapshot. Set
`LLM_CONTEXT_MODEL_CACHE` to relocate the model, or
`LLM_CONTEXT_SKIP_SEMANTIC=1` for a graph-only installation.

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
llm-context query search <natural-language-query> [--mode fts-only|lateon-only|hybrid] [--source-preference auto|production|test|none] [--explain]
llm-context query callers <symbol-id>
llm-context query callees <symbol-id> [--include-external]
llm-context query trace <symbol-id> [--depth N] [--limit N]
llm-context query entry-points
llm-context query effects
llm-context query unresolved [--classification unresolved|ambiguous|dynamic|external]
llm-context query topics|registrations|dispatchers|subscribers
llm-context query state-readers|state-writers
llm-context context <name-or-id> [--depth N] [--max-tokens N]
llm-context context --intent <natural-language-query> [--source-preference auto|production|test|none] [--depth N] [--max-tokens N]
llm-context export --format edn|json|jsonl|markdown [--output PATH]
llm-context summary [--output PATH]
llm-context integrate claude|codex|generic [--force]
llm-context semantic status
llm-context semantic sync [--wait]
llm-context semantic failures
llm-context semantic dirty
llm-context semantic retry --failed [--wait]
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

`context --intent` performs that freshness-safe hybrid retrieval first, selects
only the highest-ranked symbol as the traversal seed, and records up to four
unexpanded alternatives in the packet. LateOn chooses the likely starting
symbol; every relationship admitted afterward is still an exact canonical
graph edge. Without the resident service it falls back to Datalevin retrieval.
Intent context defaults to `--source-preference auto`: ordinary implementation
questions stably prefer production paths, while requests explicitly about
tests prefer test paths. Exact identifier matches retain priority, scores are
never rewritten, and the packet records fused rank, final rank, source role,
and the resolved preference. `query search` defaults to `none` for backward-
compatible general-purpose search ordering.

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

 :store {:path ".llm-context/db"}

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

 :context {:default-max-tokens 8000
           :trace-depth 4
           :trace-limit 200}}
```

Set `:providers []` for a graph-only installation. There is intentionally
no JSON configuration or persisted-data migration layer in this greenfield
release.

## Language support

Supported source files are `.clj`, `.cljs`, `.cljc`, and `.janet`. Selected
`deps.edn`, `bb.edn`, `shadow-cljs.edn`, and `.clj-kondo/config.edn` files are
indexed as configuration data. Other source extensions are intentionally and
silently ignored.

## Persistent data and exports

The project database lives under `.llm-context/db/`. Datalevin is the only
source of truth. The disposable LateOn index lives under
`.llm-context/semantic/next-plaid/`. JSONL, JSON, EDN, and Markdown are
deterministic projections for interoperability, debugging, and artifacts.

Release `0.9.0` introduces graph format 3. Existing generated graphs must be
rebuilt once with `llm-context analyze --full`; source and configuration files
are never changed. Normal queries detect older state and print this instruction
instead of opening it as if it were current.

## Resident service

Cold JVM startup remains materially slower than warm Datalog and model queries.
Start the detached project coordinator once:

```bash
llm-context service start
```

The coordinator watches included source trees, debounces changes, runs the same
semantic-fingerprint analyzer as the CLI, drains durable LateOn jobs in the background,
and supervises a loopback-only NextPlaid child. Query, context, and export
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

Use `llm-context semantic status` to inspect lag and
`llm-context semantic sync --wait` when automation needs all queued embeddings
visible before continuing. Logs are under `.llm-context/logs/`; they contain
identifiers and bounded errors, not source documents.

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
