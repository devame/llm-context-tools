# LLM Context Tools (llm-context)

CLI for generating compressed, AI-optimized code context graphs. It helps LLMs (Claude, ChatGPT) understand your codebase by providing semantic maps instead of raw file dumps.

## Why Use This?

- **95% Token Reduction**: Replaces raw code with compact outlines, call graphs, and dependency maps.
- **Incremental Updates**: Only re-analyzes changed files (99% faster than full re-scan).
- **Semantic Insight**: Exposes function relationships, entry points, and side effects.
- **Multi-Language**: Supports 14+ languages including JS/TS, Python, Go, Rust, Java, C/C++, Ruby, PHP, Bash, Clojure, Janet.

## Installation

```bash
npm install -g llm-context
```

## Quick Start

1. **Initialize & Analyze**:
   ```bash
   cd my-project
   llm-context analyze
   ```

2. **Setup Claude Integration** (Optional):
   ```bash
   llm-context setup-claude
   ```
   This creates a `.claude/CLAUDE.md` with instructions for Claude Code to use the tools.

3. **Query the Graph**:
   ```bash
   llm-context query find-function myFunc
   llm-context query trace myFunc
   llm-context stats
   ```

## Supported Languages

| Language | Extensions | Support Level |
|----------|------------|---------------|
| JavaScript/TypeScript | .js, .ts, .jsx, .tsx | Full (Tree-sitter) |
| Python | .py | Full |
| Go | .go | Full |
| Rust | .rs | Full |
| Java | .java | Full |
| C/C++ | .c, .cpp, .h | Full |
| Ruby | .rb | Full |
| PHP | .php | Full |
| Bash | .sh | Full |
| Clojure/Script | .clj, .cljs, .cljc | Full |
| Janet | .janet | Full |

## Output Files

Analysis creates a `.llm-context/` directory:
- `graph.jsonl`: The searchable code graph (JSON Lines).
- `manifest.json`: Change tracking data.
- `summaries/L0-system.md`: High-level system overview.
- `summaries/L1-domains.json`: Domain-level summaries.
- `index.scip`: SCIP index (if applicable).

## Contributing

Issues and PRs are welcome!
