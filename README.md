# llm-context

`llm-context` builds a persistent semantic graph of a source repository and
turns focused neighborhoods of that graph into compact context for AI coding
assistants.

The application core is Clojure. [Datalevin](https://datalevin.org/) is the
authoritative embedded database and Datalog query engine. Tree-sitter provides
structural syntax facts, while SCIP TypeScript can optionally supply
compiler-backed JavaScript and TypeScript symbol evidence.

## Why a graph?

Source files are not independent documents. Symbols call, contain, import,
reference, and implement other symbols. Persisting those facts in Datalevin
makes reverse relationships, transitive reachability, ambiguity, and deletion
correctness first-class instead of rebuilding ad-hoc JSON indexes for every
question.

Every relationship records a resolution state:

- `exact` — supported by semantic evidence or an explicit canonical link;
- `heuristic` — a unique structural name match;
- `ambiguous` — multiple targets remain possible;
- `unresolved` — no stored target is justified.

Side effects likewise include evidence, source location, and confidence. The
tool does not present naming guesses as compiler facts.

## Requirements

- JDK 23 or newer. JDK 25 is used for development and release validation.
- Clojure CLI 1.12+ when running from source.
- Node.js 20+ only when using the npm launcher or optional
  `scip-typescript` provider. Node is not the application runtime.

## Install

Linux and macOS:

```bash
curl -fsSL https://raw.githubusercontent.com/devame/llm-context-tools/main/install.sh | sh
```

Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/devame/llm-context-tools/main/install.ps1 | iex
```

Both installers require Java 23 or newer, download the latest release jar,
verify its SHA-256 checksum, install a launcher, and run a version smoke check.
The PowerShell installer adds its per-user installation directory to `PATH`.
The Unix installer uses `~/.local/bin` and adds it to the appropriate user
shell profile when needed. Open a new terminal after either installer changes
`PATH`.

Set `LLM_CONTEXT_VERSION=0.5.1` to pin a release or
`LLM_CONTEXT_INSTALL_DIR` to choose another destination. The installers are
idempotent: running them again replaces the jar and launcher only after
checksum validation.

To install the optional compiler-backed JavaScript/TypeScript provider in the
same run, set `LLM_CONTEXT_INSTALL_SCIP=1`; this option also requires Node.js
and npm. Re-run the installer to update, or remove the installed directory to
uninstall.

After installation:

```bash
llm-context doctor
llm-context init [--yes]
llm-context analyze
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
npm install --global ./llm-context-0.5.1.tgz
llm-context doctor
```

The public npm name `llm-context` is not controlled by this project. Use the
one-script installer for normal installations rather than installing that
unrelated registry package.

## Commands

```text
llm-context init [--yes]
llm-context doctor
llm-context analyze [--full]
llm-context query stats
llm-context query find-symbol <name-or-id>
llm-context query callers <symbol-id>
llm-context query callees <symbol-id>
llm-context query trace <symbol-id>
llm-context query entry-points
llm-context query effects
llm-context query unresolved
llm-context context <name-or-id> [--depth N] [--max-tokens N]
llm-context export --format edn|json|jsonl|markdown [--output PATH]
llm-context summary [--output PATH]
llm-context integrate claude|codex|generic [--force]
llm-context service start|status|stop
```

`analyze` runs a full scan when no graph exists and content-hash incremental
analysis afterward. Incremental updates parse only changed files, cascade
deleted files, preserve inbound evidence from unchanged callers, and reconcile
all edge targets against the new symbol set. The default scan covers the whole
confirmed project root. In Git repositories it uses tracked plus non-ignored
files; elsewhere it prunes the configured generated/cache directories during
the filesystem walk. Full analysis reports each stage and persists the rebuilt
graph in dependency order using transactions of at most 100 records. If a full
analysis is interrupted during persistence, rerun `analyze --full` to clear and
rebuild the partial graph.

## Configuration

`llm-context.edn` is the only configuration format:

```clojure
{:analysis
 {:include ["."]
  :exclude [".git" ".llm-context" "node_modules" "target" ".cpcache"
            "dist" "build" "out" ".shadow-cljs" ".cljs_node_repl" ".lsp"]
  :languages :auto
  :max-file-bytes 1048576}

 :store {:path ".llm-context/db"}

 :semantic
 {:providers [:scip-typescript]
  :scip-typescript-command ["npx" "--no-install"
                            "scip-typescript" "index"]}

 :context {:default-max-tokens 8000
           :trace-depth 4}}
```

Set `:providers []` for a structural-only installation. There is intentionally
no JSON configuration or persisted-data migration layer in this greenfield
release.

## Language support

Packaged structural grammars are verified for JavaScript, TypeScript, Python,
Java, Go, Rust, C, C++, Ruby, PHP, Bash, Clojure, and Janet. Janet analysis
recognizes `def`, `var`, function and macro variants, calls, and
`import`/`use`/`require` module relationships. The grammar and all native
libraries are embedded, so users do not need a separate Janet installation.
TSX extensions are detected but currently reported as unavailable rather than
silently producing incomplete graphs.

SCIP TypeScript is optional and currently enriches JavaScript/TypeScript
resolution. Other languages use explicit structural resolution states; they do
not claim compiler-accurate cross-file semantics.

## Persistent data and exports

The project database lives under `.llm-context/db/`. Datalevin is the only
source of truth. JSONL, JSON, EDN, and Markdown are deterministic projections
for interoperability, debugging, and artifacts.

Because this is a greenfield release, delete `.llm-context/` and analyze again
after an incompatible pre-release schema change.

## Resident service

Cold JVM startup remains materially slower than warm Datalog queries. For
interactive sessions, run this in a dedicated terminal:

```bash
llm-context service start
```

Query, context, and export commands automatically use the warm service. Analysis
runs in the invoking process so progress stays visible and a client timeout
cannot start a second database writer. The service listens only on the loopback
interface and requires a random token stored in the ignored
`.llm-context/service.edn` descriptor. Supported commands fall back to direct
execution when no service is available.

## Development

```bash
clojure -M:test
clojure -M:bench 50
clojure -T:build dist
npm pack --dry-run
```

Maintainers can reproduce the embedded Janet grammar libraries with Zig 0.15+
by running `script/build-janet-grammar.sh`. The pinned source revision and
license are recorded in `resources/llm_context/native/JANET_GRAMMAR.md`.

See [architecture and tradeoffs](docs/architecture.md),
[semantic graph model](docs/semantic-graph.md), and
[benchmark methodology](docs/benchmarks.md). The complete project workflow is
in the [user guide](docs/user-guide.md).
