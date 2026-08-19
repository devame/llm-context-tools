# llm-context

`llm-context` builds a persistent, project-local code graph and turns the
relevant parts of that graph into compact context for developers and AI coding
assistants.

It supports Clojure, ClojureScript, CLJC, Janet, and selected EDN project
configuration. Analysis and search run locally; source code is not sent to a
remote service.

## What it gives you

- Exact callers, callees, ownership, protocol, event, and state relationships.
- Local natural-language search across symbols and source-backed concepts.
- Bounded context packets for a symbol or a question.
- Incremental indexing that stays with the repository under `.llm-context/`.
- EDN, JSON, JSONL, and Markdown exports for tools and agents.

Semantic retrieval helps find relevant starting points. Graph traversal still
uses only analyzer-proven, in-project relationships; ambiguous, dynamic, and
external references cannot silently become graph edges.

## Requirements

- JDK 23 or newer.
- Linux x86-64, macOS Apple Silicon, or Windows x86-64 for the bundled semantic
  runtime. Exact graph analysis and lexical search work without it.
- Clojure CLI 1.12+ only when running from source.

## Install

Linux and macOS:

```bash
curl -fsSL https://raw.githubusercontent.com/devame/llm-context-tools/main/install.sh | sh
```

Windows PowerShell:

```powershell
irm https://raw.githubusercontent.com/devame/llm-context-tools/main/install.ps1 | iex
```

The installer verifies downloaded artifacts and installs the CLI and local
semantic models for the current user. Open a new terminal if the installer
updates your `PATH`.

On Linux x86-64, the installer performs a GPU/driver/CUDA preflight. When a
compatible visible NVIDIA GPU is present, it offers to install whichever CUDA
12 runtime (`cuda-cudart-12-9`), CUDA math libraries (`cuda-libraries-12-9`,
including cuBLAS/cuRAND), and cuDNN 9 (`cudnn9-cuda-12`) libraries are missing;
otherwise it installs the CPU package and prints the corrective action. Use
`LLM_CONTEXT_ACCELERATOR_PACKAGE=auto`, `cpu`, or `cuda` to choose explicitly.
Inspect or repair the host later with:

```bash
llm-context setup
llm-context setup --install-cudnn       # install missing CUDA 12/cuDNN packages after confirmation
llm-context setup --install-cudnn --yes # explicit non-interactive install
```

The dependency bootstrap and top-level installer can install the supported CUDA
12/cuDNN packages on Debian/Ubuntu and configure NVIDIA's signed CUDA apt
repositories when those packages are not already available. Repository repair is
self-healing: stale NVIDIA source entries are removed, the WSL CUDA and Ubuntu
cuDNN repositories use separate verified keyrings, and the current keyring
package is selected from each repository's metadata. They never install a GPU
driver automatically. In WSL, install or update
the NVIDIA CUDA-enabled driver on Windows, not a Linux driver inside WSL.

For graph analysis and lexical search without the semantic models:

```bash
curl -fsSL https://raw.githubusercontent.com/devame/llm-context-tools/main/install.sh \
  | LLM_CONTEXT_SKIP_SEMANTIC=1 sh
```

Set `LLM_CONTEXT_VERSION=0.12.10` to pin the current release. See the
[installation and troubleshooting guide](docs/user-guide.md#installation-and-troubleshooting)
for custom locations, CUDA, and verified model packages.

## Index your first project

Run these commands from the repository root:

```bash
llm-context init
llm-context doctor
llm-context setup
llm-context analyze
```

`init` confirms the project root and creates `llm-context.edn`. The first
analysis builds the graph; later `analyze` runs are incremental. If an upgrade
introduces an incompatible graph format, `analyze` automatically performs the
guarded full rebuild required to update it. When semantic indexing is enabled,
`analyze` starts the project service after queueing work; the service watches
for changes, keeps the JVM warm, and drains semantic jobs in the background.
Use `llm-context analyze --no-service` for a one-shot graph-only or CI run.

You can target a project without changing directories:

```bash
llm-context -C /path/to/project query stats
```

### Check indexing progress

```bash
llm-context semantic status
llm-context semantic status --watch
llm-context semantic status --verbose
```

The concise view reports remaining documents and indexing speed, followed by a
separate aggregate-analysis line showing aggregate and membership facts,
whether their semantic documents are complete, and skipped files from the
latest analysis. A semantic index is complete when verbose status shows:

- `:indexed` equals `:desired`;
- `:coverage-percent` is `100.0`;
- `:completeness` is `:complete`; and
- `:pending`, `:leased`, `:failed`, and `:dirty` are all zero.

Graph queries are available as soon as graph analysis finishes. Hybrid search
falls back to local lexical results while the semantic index is unavailable or
still catching up.

## Find and understand code

Search by name or question:

```bash
llm-context query find-symbol authenticate
llm-context query search "where is authentication handled?"
llm-context query search "where is authentication handled?" --explain
```

Inspect exact relationships after choosing a symbol ID:

```bash
llm-context query callers symbol:...
llm-context query callees symbol:...
llm-context query trace symbol:... --depth 3
```

Build a bounded context packet:

```bash
llm-context context authenticate --max-tokens 4000
llm-context context --intent "where is authentication failure handled?"
```

`query search` returns ranked matches. `context --intent` resolves a question
to one or more relevant roots and then expands them through exact graph
relationships under a shared token budget.

For the complete query surface—including unresolved references, re-frame
topics, source-role preferences, and retrieval diagnostics—see
[Query the project](docs/user-guide.md#query-the-project) and
[Build bounded context](docs/user-guide.md#build-bounded-context).
Run `llm-context --help` for the top-level command list.

## Use it with coding agents

Install project guidance for a supported agent:

```bash
llm-context integrate codex
llm-context integrate claude
llm-context integrate generic
```

You can also export deterministic project data directly:

```bash
llm-context export --format jsonl --output graph.jsonl
llm-context summary --output graph-summary.md
```

## How project data is handled

Generated state lives below `.llm-context/` in the indexed repository. It is
project-local, disposable, and should remain outside source control. Source and
configuration files are never modified by analysis.

Analysis does not run project code, build tools, dependency commands, Janet,
or project macros. To validate a source snapshot without changing the graph,
run:

```bash
llm-context analyze --check
```

When semantic indexing is enabled, `analyze` starts the resident service
automatically. Manage it explicitly when needed with:

```bash
llm-context service status
llm-context service stop
```

Runtime and indexing logs are under `.llm-context/logs/`. Storage inspection
and cleanup commands are documented in
[Semantic indexing](docs/user-guide.md#semantic-indexing).

## Troubleshooting

If status appears contradictory, indexing stops progressing, acceleration falls
back unexpectedly, or a service survives an upgrade, start with the
[troubleshooting FAQ](docs/troubleshooting-faq.md). It covers analysis,
services, semantic indexing, CPU/CUDA, search fallback, storage, upgrades, and
the boundary between automatic and operator-required recovery.

## Configuration

`llm-context.edn` is the project configuration file. The defaults scan the
confirmed project root while respecting Git ignores and common generated or
cache directories. Typical configuration changes narrow included paths,
exclude project-specific generated files, disable semantic providers, or tune
storage and model settings.

See the [user guide](docs/user-guide.md) for configuration examples and the
current defaults in
[`resources/llm_context/default-config.edn`](resources/llm_context/default-config.edn).

## Supported files and upgrades

Source analysis recognizes `.clj`, `.cljs`, `.cljc`, and `.janet` files, plus
selected `deps.edn`, `bb.edn`, `shadow-cljs.edn`, and
`.clj-kondo/config.edn` files. Unsupported extensions are ignored.

Re-run the installer to update the CLI. If a release changes the graph format,
a normal `analyze` detects the older state and automatically performs a guarded
full rebuild. To request that rebuild explicitly, or to force one for another
reason, run:

```bash
llm-context analyze --full
```

Source files and `llm-context.edn` are preserved during a rebuild.

## Run from source

```bash
clojure -M -m llm-context.main doctor
clojure -M -m llm-context.main init
clojure -M -m llm-context.main analyze --full
clojure -M -m llm-context.main query stats
```

Build and run the distribution JAR:

```bash
clojure -T:build dist
java --enable-native-access=ALL-UNNAMED -jar dist/llm-context.jar help
```

For local npm-based development, the repository package is a thin launcher
around the same JAR:

```bash
npm pack
npm install --global ./llm-context-0.12.10.tgz
llm-context doctor
```

The public npm name `llm-context` is not controlled by this project. Use the
installer above for normal installations rather than installing the unrelated
registry package.

## Development

Bootstrap or audit the development dependency set with the checked-in
manifest. The command retries downloads, shows progress, checks current
upstream releases, verifies hashes, and retains its error log:

```bash
scripts/install-dependencies.sh install
scripts/install-dependencies.sh verify
```

Use `--with-native` to build only the local WSL/Linux Janet parser library and
`--with-models` to download and verify all model packages. GitHub Actions builds
the ARM Linux, macOS, and Windows parser libraries; the release workflow
combines those CI artifacts with the Linux library present in the tagged
checkout. `--offline` skips only the live release lookup; static contract and
checksum checks still run.

```bash
clojure -M:test
clojure -T:build dist
clojure -M script/verify-dependencies.clj
scripts/verify-release-quality.sh dist/llm-context.jar
npm pack --dry-run
```

Maintainers can publish the next patch release with one command. The command
checks the current version, derives release notes from the latest commit,
creates the version/changelog commit, runs the release gates, pushes `main`,
creates the annotated tag, and waits for GitHub to publish the assets:

```bash
scripts/release.sh 0.12.4
```

The argument is the current repository version; the default result is the next
patch version (`0.12.4` becomes `0.12.5`). For a minor or major release, use
`--version-bump`:

```bash
scripts/release.sh 0.12.4 --version-bump minor
scripts/release.sh 0.12.4 --version-bump major
```

Use `--no-wait` when CI completion will be monitored separately. The existing
`scripts/release.sh check` and `scripts/release.sh publish` subcommands remain
available for explicit, already-prepared releases.

Additional benchmark and release workflows are in
[Performance benchmarks](docs/benchmarks.md).

## Documentation

- [User guide](docs/user-guide.md) — complete workflows and troubleshooting.
- [Dependency registry](docs/dependencies.md) — authoritative versions,
  artifacts, hashes, and drift checks.
- [Architecture and tradeoffs](docs/architecture.md) — runtime and storage
  design.
- [Semantic graph model](docs/semantic-graph.md) — entities, relationships,
  provenance, and compatibility.
- [Performance benchmarks](docs/benchmarks.md) — methodology and measured
  results.
