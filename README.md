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

The npm package is a thin launcher around the same jar:

```bash
npm install -g llm-context
llm-context doctor
```

## Commands

```text
llm-context init
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
all edge targets against the new symbol set.

## Configuration

`llm-context.edn` is the only configuration format:

```clojure
{:analysis
 {:include ["src" "test"]
  :exclude [".git" ".llm-context" "node_modules" "target"]
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
Java, Go, Rust, C, C++, Ruby, PHP, Bash, and Clojure. TSX and Janet extensions
are detected but currently reported as unavailable rather than silently
producing incomplete graphs.

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

Analyze, query, context, and export commands automatically use the warm service.
It listens only on the loopback interface and requires a random token stored in
the ignored `.llm-context/service.edn` descriptor. Commands fall back to direct
execution when no service is available.

## Development

```bash
clojure -M:test
clojure -M:bench 50
clojure -T:build dist
npm pack --dry-run
```

See [architecture and tradeoffs](docs/architecture.md),
[semantic graph model](docs/semantic-graph.md), and
[benchmark methodology](docs/benchmarks.md).
