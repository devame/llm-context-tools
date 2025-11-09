# LLM Context - Code Analysis for AI Assistants

Generate compact, semantically-rich code context optimized for LLM consumption with **99%+ faster incremental updates**.

[![npm version](https://img.shields.io/npm/v/llm-context.svg)](https://www.npmjs.com/package/llm-context)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## What Is This?

A tool that transforms raw source code into LLM-optimized context, enabling AI assistants like Claude, ChatGPT, and Copilot to understand your codebase with **80-95% fewer tokens**.

**Key Features:**
- 🔍 **Function call graphs** with side effect detection
- 📊 **Multi-level summaries** (System → Domain → Module)
- ⚡ **Incremental updates** (99%+ faster than full re-analysis)
- 🔐 **Hash-based change tracking** (only re-analyze what changed)
- 🎯 **Query interface** for instant lookups
- 🤖 **Claude Code skill** included

## Quick Start

### Installation

```bash
# Install globally
npm install -g llm-context

# Or use directly without installing
npx llm-context analyze
```

### Usage

```bash
# Analyze your codebase
cd ~/my-project
llm-context analyze

# Query results
llm-context stats
llm-context entry-points
llm-context side-effects

# Use with LLMs
# Share .llm-context/ directory with AI assistants
```

## Why Use This?

### Traditional Approach: Read Raw Files

```
LLM: "Help me debug this codebase"
[Reads 10 files × 1,000 tokens = 10,000 tokens]
Missing: Call graphs, side effects, architecture
```

### LLM-Context Approach: Optimized Summaries

```
LLM: "Help me debug this codebase"
[Reads L0 + L1 + Graph = 500-2,000 tokens]
Includes: Complete call graph, side effects, entry points
```

**Result:** 80-95% token savings + better understanding

## Features

### 1. Incremental Updates (99%+ Faster)

Only re-analyzes files that changed:

```bash
# Initial analysis (500 files)
llm-context analyze
# Time: ~30 seconds

# Edit 3 files and re-analyze
llm-context analyze
# Time: ~150ms (99.5% faster!)
```

Performance at scale:
- 100 files: 2-5s → 50-200ms (96% faster)
- 1,000 files: 30-60s → 200-500ms (99% faster)
- 10,000 files: 5-15min → 500ms-2s (99.7% faster)

### 2. Function-Level Granularity (NEW!)

Track changes at the function level, not just file level:

```bash
# Edit 1 function in a file with 50 functions
# File-level: Re-analyze all 50 (500ms)
# Function-level: Re-analyze 1 (10ms) - 98% faster!

# Configure in llm-context.config.json
{
  "granularity": "function",
  "incremental": {
    "storeSource": true,        // Enable rename detection
    "detectRenames": true,
    "similarityThreshold": 0.85
  },
  "analysis": {
    "trackDependencies": true   // Enable impact analysis
  }
}
```

**Advanced Features:**
- **Rename Detection**: Detects function renames via similarity matching
- **Impact Analysis**: Shows which functions are affected by changes
- **Dependency Graphs**: Entry points, leaf functions, cycle detection

Results:
- Large files (50+ functions): **98% faster** when editing 1 function
- Medium files (20-30 functions): **93% faster**
- Perfect for utility files, generated code, and focused edits

See [FUNCTION_LEVEL_GRANULARITY.md](FUNCTION_LEVEL_GRANULARITY.md) for complete details.

### 3. Progressive Disclosure

Read only what you need:

1. **L0** (200 tokens) - System overview
2. **L1** (50-100 tokens/domain) - Domain boundaries
3. **L2** (20-50 tokens/module) - Module details
4. **Graph** (variable) - Function specifics
5. **Source** (as needed) - Targeted file reading

### 4. Side Effect Detection

Automatically identifies:
- `file_io` - File operations
- `network` - HTTP requests
- `database` - DB queries
- `logging` - Console output
- `dom` - Browser DOM manipulation

### 5. Query Interface

```bash
# Find function
llm-context query find-function authenticateUser

# Who calls this?
llm-context query calls-to login

# Trace call path
llm-context query trace processPayment

# Functions with side effects
llm-context side-effects | grep network
```

## Installation & Setup

### Global Installation (Recommended)

```bash
npm install -g llm-context
cd ~/any-project
llm-context analyze
```

### Project-Specific Installation

```bash
cd ~/my-project
npm install --save-dev llm-context
npx llm-context analyze
```

### Initialize New Project

```bash
cd ~/my-project
llm-context init
# Installs dependencies and runs first analysis
```

## CLI Commands

### Analysis

```bash
llm-context analyze              # Auto-detect full/incremental
llm-context analyze:full         # Force full re-analysis
llm-context check-changes        # Preview changes without analyzing
```

### Queries

```bash
llm-context stats                # Show statistics
llm-context entry-points         # Find entry points
llm-context side-effects         # Functions with side effects
llm-context query <cmd> [args]   # Custom queries
```

### Utilities

```bash
llm-context init                 # Initialize in project
llm-context version              # Show version
llm-context help                 # Show help
```

## Generated Files

```
.llm-context/
├── graph.jsonl           # Function call graph (JSONL format)
├── manifest.json         # Change tracking (MD5 hashes)
└── summaries/
    ├── L0-system.md      # System overview (~200 tokens)
    ├── L1-domains.json   # Domain summaries
    └── L2-modules.json   # Module summaries
```

### Graph Format

Each line in `graph.jsonl`:

```json
{
  "id": "authenticateUser",
  "file": "src/auth.js",
  "line": 45,
  "sig": "(credentials)",
  "async": true,
  "calls": ["validateCredentials", "createSession"],
  "effects": ["database", "logging"]
}
```

## Claude Code Skill

This package includes a Claude Code skill that teaches Claude how to use the tool effectively.

**Location:** `.claude/skills/analyzing-codebases/`

**How it works:**
1. Claude automatically detects the skill when analyzing codebases
2. Knows to read L0 → L1 → L2 → Graph → Source (in order)
3. Uses queries instead of grepping files
4. Achieves 80-95% token savings

## Usage Examples

### Understanding a New Codebase

```bash
llm-context analyze
cat .llm-context/summaries/L0-system.md
llm-context stats
llm-context entry-points
```

**With Claude:**
```
You: "Analyze this codebase"

Claude: [Runs llm-context analyze, reads L0]
"This is a web application with 156 functions across 47 files.
 Key domains: auth (12 functions), users (23), api (34)
 Entry points: main(), handleRequest()

 Would you like me to explain a specific module?"
```

### Daily Development

```bash
# Morning
git pull origin main
llm-context check-changes
llm-context analyze

# Edit code
vim src/feature.js

# Quick re-analysis (only feature.js)
llm-context analyze  # ~30ms
```

### Debugging

```bash
llm-context query find-function buggyFunc
llm-context query calls-to buggyFunc
llm-context query trace buggyFunc
llm-context side-effects | grep buggy
```

### Code Review

```bash
git checkout feature/new-auth
llm-context analyze
llm-context stats
llm-context side-effects | grep auth
```

## Integration

### Pre-commit Hook

```bash
# .git/hooks/pre-commit
#!/bin/bash
llm-context analyze
git add .llm-context/
```

### CI/CD

```yaml
# .github/workflows/analyze.yml
- name: Install llm-context
  run: npm install -g llm-context

- name: Analyze codebase
  run: llm-context analyze

- name: Upload artifacts
  uses: actions/upload-artifact@v3
  with:
    name: llm-context
    path: .llm-context/
```

### Watch Mode (Coming Soon)

```bash
llm-context watch  # Auto-analyze on file changes
```

## Performance Benchmarks

### Token Efficiency

| Approach | Tokens | Includes |
|----------|--------|----------|
| Read 10 raw files | 10,000 | Syntax only |
| LLM-Context | 500-2,000 | Call graph + semantics |
| **Savings** | **80-95%** | **Better understanding** |

### Incremental Updates

| Codebase Size | Files Changed | Full Analysis | Incremental | Savings |
|---------------|---------------|---------------|-------------|---------|
| 500 files | 5 | 14s | 140ms | **99.0%** |
| 5,000 files | 10 | 2.3min | 280ms | **99.8%** |
| 50,000 files | 20 | 23min | 560ms | **99.996%** |

## How It Works

### 1. SCIP Indexing (Optional)

```bash
scip-typescript index --infer-tsconfig
```
- Uses TypeScript compiler for symbol extraction
- Captures references and types
- Falls back to Babel for JavaScript

### 2. Custom Analysis

```javascript
// Parse with Babel
const ast = parse(sourceCode);

// Extract functions, calls, side effects
traverse(ast, {
  FunctionDeclaration(path) {
    // Analyze function
  }
});
```

### 3. Graph Generation

```javascript
// Combine SCIP + custom analysis
const node = {
  id: func.name,
  calls: extractCalls(func),
  effects: detectSideEffects(func)
};

// Write as JSONL (one function per line)
```

### 4. Incremental Updates

```javascript
// Hash-based change detection
const currentHash = md5(fileContent);
const cachedHash = manifest.files[file].hash;

if (currentHash !== cachedHash) {
  // Re-analyze only this file
  analyze(file);
  updateGraph(file, results);
}
```

## Roadmap

### ✅ Completed

- [x] Incremental updates with hash-based invalidation
- [x] CLI packaging (`npm install -g`)
- [x] Claude Code skill
- [x] Multi-level summaries
- [x] Side effect detection
- [x] Query interface
- [x] **Function-level granularity** (98% faster for large files)
- [x] **Advanced features**: Rename detection, dependency analysis, source storage

### 🚧 In Progress

- [ ] Watch mode (auto-analyze on file changes)
- [ ] Multi-language support (Python, Go, Rust, Java)

### 📋 Planned

- [ ] Parallel analysis
- [ ] VS Code extension
- [ ] GitHub Action

## Documentation

- **[Installation Guide](CLI_INSTALLATION.md)** - Detailed setup instructions
- **[Incremental Updates](INCREMENTAL_UPDATES.md)** - How incremental updates work
- **[Function-Level Granularity](FUNCTION_LEVEL_GRANULARITY.md)** - Track changes at function level (NEW!)
- **[Demo](DEMO.md)** - Live demonstrations
- **[Performance](performance-comparison.md)** - Benchmarks and projections
- **[Proof of Concept](PROOF_OF_CONCEPT_RESULTS.md)** - Original research

## Requirements

- Node.js ≥ 16.0.0
- JavaScript or TypeScript project
- Works on Linux, macOS, Windows

## Contributing

Contributions welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## License

MIT - See [LICENSE](LICENSE) file

## Support

- **GitHub**: https://github.com/devame/llm-context-tools
- **Issues**: https://github.com/devame/llm-context-tools/issues
- **npm**: https://www.npmjs.com/package/llm-context

## Related Projects

- **[SCIP](https://github.com/sourcegraph/scip)** - Code Intelligence Protocol
- **[Babel](https://babeljs.io/)** - JavaScript parser
- **[Claude Code](https://claude.ai/code)** - AI pair programming

## Acknowledgments

Built with:
- `@babel/parser` - JavaScript parsing
- `@babel/traverse` - AST traversal
- `@sourcegraph/scip-typescript` - SCIP indexing
- `protobufjs` - Protocol buffer parsing

## Citation

If you use this tool in research, please cite:

```
LLM Context Tools - Code Analysis for AI Assistants
https://github.com/devame/llm-context-tools
```

---

**Made with ❤️ for AI-assisted development**
