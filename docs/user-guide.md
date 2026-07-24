# User guide

This guide assumes `llm-context` is installed and `llm-context doctor` reports
Java and Datalevin as healthy. The installed jar already contains Datalevin,
the graph schema, and the native Tree-sitter grammars; users do not install a
separate Datalevin server or database. The normal installer also supplies the
local NextPlaid/ONNX runtime and pinned LateOn-Code model.

## 1. Initialize a project

Change into the repository you want to analyze and create the project-local
configuration:

```bash
cd path/to/your-project
llm-context init
```

The command displays the canonical project root and requires confirmation
before creating `llm-context.edn`. Automation can pass `--yes`. It does not
modify source files. By default the analyzer scans the entire confirmed root
and prunes generated, dependency, VCS, cache, and database directories before
descent. In a Git repository it honors `.gitignore` by analyzing tracked files
plus non-ignored untracked files. The default database location is
`.llm-context/db/`.

The executable and model installation is per user, but all source-derived
state is per project under `.llm-context/`. For a structural-only project that
does not use SCIP or LateOn, set the semantic providers to an empty vector:

```clojure
{:semantic {:providers []}}
```

## 2. Build the graph

Run the first full scan:

```bash
llm-context analyze --full
```

Subsequent runs are incremental:

```bash
llm-context analyze
```

The analyzer uses file content hashes. It reparses changed files, removes
deleted files, preserves inbound relationships owned by unchanged callers,
and reconciles their targets. A full scan is useful after changing config,
language support, or the graph schema.

A full scan prints discovery, parsing, semantic, resolution, and persistence
progress. The rebuilt graph is written in dependency order—files, symbols,
edges, then effects—in transactions of at most 100 records. This bounds each
Datalevin transaction instead of asking it to resolve the entire graph at once.
If the command is interrupted during persistence, the graph may be partial;
rerun `llm-context analyze --full` to clear and rebuild it.

Check the result with:

```bash
llm-context query stats
llm-context summary --output .llm-context/summary.md
```

Analysis commits structural graph changes without waiting for model inference.
When the project service is running, a durable background queue brings the
LateOn index up to date. The analyzer's “Semantic indexing queued” line reports
that work; it does not mean the CLI synchronously embedded the source.

## 3. Find relevant code

Search symbols and inspect graph relationships:

```bash
llm-context query find-symbol authenticate
llm-context query search "where is authentication handled?"
llm-context query callers symbol:...
llm-context query callees symbol:...
llm-context query trace symbol:...
llm-context query entry-points
llm-context query effects
llm-context query unresolved
```

`find-symbol` combines exact identifiers and case-insensitive substrings with
Datalevin's local full-text relevance ranking over names, qualified names,
signatures, and documentation. It does not call an embedding model. `search`
adds local LateOn similarity and deterministically fuses both rankings. Results
show `:matched-by #{:fts :lateon}` (or the available subset), and stale LateOn
candidates are rejected against current Datalevin hashes before they are
returned.

Resolution is deliberately explicit. `exact` means semantic evidence supports
the target; `heuristic`, `ambiguous`, and `unresolved` indicate weaker or
missing evidence and should guide follow-up source inspection.

## 4. Produce an assistant context packet

Use a symbol name or canonical symbol ID as the focus:

```bash
llm-context context authenticate --depth 3 --max-tokens 4000
llm-context context authenticate --format edn --max-tokens 8000
```

The packet includes nearby symbols, relationships, effects, source locations,
resolution states, and a token estimate. It is intentionally bounded; read
the referenced source files when the packet points to implementation detail.

## 5. Integrate with an agent

Install project-local guidance for the agent you use:

```bash
llm-context integrate claude
llm-context integrate codex
llm-context integrate generic
```

Use `--force` to replace an existing generated guidance file. The guidance
asks the agent to query the graph before broad source exploration.

## 6. Keep interactive sessions warm

Start the detached, loopback-only project coordinator:

```bash
llm-context service start
llm-context service status
llm-context query stats
llm-context context authenticate --max-tokens 4000
llm-context service stop
```

`start` returns after the graph endpoint is reachable; the model may still be
loading. The service performs an initial scan, watches included directories,
coalesces bursts of edits, and incrementally updates the graph and LateOn
queue. It accelerates query, context, and export commands and uses a
per-project random token in the ignored state directory. It is not exposed
beyond loopback.

Inspect or wait for semantic freshness with:

```bash
llm-context semantic status
llm-context semantic sync --wait
```

`sync --wait` is useful in CI or before an evaluation. It also retries
previously exhausted jobs by reconciling the entire desired semantic state.
If NextPlaid is absent or unhealthy, graph analysis and Datalevin search
continue to work.

## 7. Export or inspect the graph

Datalevin remains authoritative. Exports are deterministic projections:

```bash
llm-context export --format edn --output .llm-context/graph.edn
llm-context export --format json --output .llm-context/graph.json
llm-context export --format jsonl --output .llm-context/graph.jsonl
llm-context summary --output .llm-context/summary.md
```

The database itself is under `.llm-context/db/`. Keep that directory out of
source control unless you intentionally want to distribute a generated graph;
it is already ignored by the default configuration.

## 8. Installation, privacy, and resource use

The default one-script install downloads the INT8 LateOn snapshot (about
154 MB) once and verifies fixed SHA-256 hashes. NextPlaid and ONNX Runtime are
also checksum-verified. Model inference and indexing are local; source text is
sent only over the loopback connection between the project coordinator and its
child process.

Supported semantic-runtime targets are Linux x86-64, macOS Apple Silicon,
and Windows x86-64. Set `LLM_CONTEXT_SKIP_SEMANTIC=1` during
installation on other systems or whenever only the structural graph is
wanted. Runtime memory and index size depend on codebase size and symbol
length; pooling factor 2 is the default space/quality tradeoff.

## 9. JavaScript and TypeScript projects

To install the optional compiler-backed SCIP
provider in the same setup command, provide Node.js 20+ and npm, then set
`LLM_CONTEXT_INSTALL_SCIP=1` when running the installer. Otherwise the tool
still performs structural analysis and records semantic links as heuristic,
ambiguous, or unresolved where compiler evidence is unavailable.

SCIP is used only for JavaScript and TypeScript enrichment. It is not required
for Python, Java, Go, Rust, C, C++, Ruby, PHP, Bash, Clojure, or Janet
structural analysis.

## 10. Janet projects

Janet support is included in the normal installation. No Janet executable,
package manager, Tree-sitter CLI, or C compiler is required. A normal project
scan includes every tracked or non-ignored `.janet` file and extracts:

- module symbols based on source paths;
- constants, variables, functions, private functions, global bindings, and
  macros declared by Janet's standard definition forms;
- function and macro call relationships;
- `import`, `use`, and `require` module relationships; and
- high-confidence file, process, network, and logging effects from Janet core
  APIs.

Janet macro expansion and compiler name resolution are not executed. As a
result, generated definitions and dynamically computed calls remain absent or
unresolved, and cross-file links are labelled heuristic unless exact evidence
exists in the graph. This is deliberate: structural evidence is not presented
as compiler evidence.

## 11. Troubleshooting

- `doctor` reports Java failure: install JDK 23 or newer and reopen the shell.
- `doctor` reports SCIP as optional/unavailable: install Node/npm and rerun the
  installer with `LLM_CONTEXT_INSTALL_SCIP=1`, or disable the provider in
  `llm-context.edn`.
- `doctor` reports NextPlaid, ONNX Runtime, or model failure: rerun the normal
  installer. It verifies and repairs the pinned components. Check
  `.llm-context/logs/next-plaid.log` if the runtime is installed but cannot
  load.
- `semantic status` reports failed jobs: run
  `llm-context semantic sync --wait`. Graph and lexical queries remain usable
  while semantic indexing is repaired.
- `analyze` finds no files: verify that `init` was run at the repository root,
  then inspect `:analysis :include` and `:exclude`.
- A project has stale graph data after a pre-release schema change: remove
  `.llm-context/` and run `llm-context analyze --full`.
- A file is skipped: inspect `:analysis :include`, `:exclude`, language
  detection, and `:max-file-bytes` in `llm-context.edn`.

For the graph model and ownership rules, see
[semantic-graph.md](semantic-graph.md). For implementation tradeoffs, see
[architecture.md](architecture.md).
