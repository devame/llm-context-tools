# LLM Context Tools - Multi-Language Code Analysis for AI

Generate compact, semantically-rich code context optimized for LLM consumption with **99%+ faster incremental updates** across **11+ programming languages**.

[![npm version](https://img.shields.io/npm/v/llm-context.svg)](https://www.npmjs.com/package/llm-context)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## What Is This?

A multi-language code analysis tool that transforms raw source code into LLM-optimized context, enabling AI assistants like Claude, ChatGPT, and Copilot to understand your codebase with **80-95% fewer tokens**.

**Key Features:**
- 🌍 **Multi-language support** (JavaScript, TypeScript, Python, Go, Rust, Java, C, C++, Ruby, PHP, Bash)
- 🔍 **Function call graphs** with AST-based side effect detection
- 📊 **Multi-level summaries** (System → Domain → Module)
- ⚡ **Incremental updates** (99%+ faster than full re-analysis)
- 🔐 **Hash-based change tracking** (only re-analyze what changed)
- 🎯 **Query interface** for instant lookups
- 🪝 **Claude Code hooks** for automatic context injection
- 🌳 **Tree-sitter powered** (robust parsing, 50+ languages available)

## Supported Languages

| Language | Extensions | Features |
|----------|-----------|----------|
| **JavaScript** | `.js`, `.mjs`, `.cjs`, `.jsx` | Full support |
| **TypeScript** | `.ts`, `.tsx` | Full support (no SCIP needed) |
| **Python** | `.py` | Full support |
| **Go** | `.go` | Full support |
| **Rust** | `.rs` | Full support |
| **Java** | `.java` | Full support |
| **C** | `.c`, `.h` | Full support |
| **C++** | `.cpp`, `.hpp` | Full support |
| **Ruby** | `.rb` | Full support |
| **PHP** | `.php` | Full support |
| **Bash** | `.sh`, `.bash` | Full support |

**More languages coming:** Any language with a [Tree-sitter grammar](https://tree-sitter.github.io/tree-sitter/#available-parsers) can be added.

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
# Analyze your codebase (auto-detects languages)
cd ~/my-project
llm-context analyze

# Query results
llm-context stats
llm-context entry-points
llm-context side-effects

# Setup Claude Code integration (hooks + docs)
llm-context setup-claude
```

## New: Claude Code Hooks Integration

Automatic context injection and incremental analysis (similar to [beads](https://github.com/steveyegge/beads)):

```bash
# Install hooks + documentation
llm-context setup-claude

# What gets installed:
# 1. SessionStart hook → runs `llm-context prime` at session start
# 2. PreCompact hook → runs `llm-context analyze --quiet` before compaction
# 3. .claude/CLAUDE.md → query-first instructions
```

**What happens automatically:**
- 📊 **Session Start**: Claude Code injects 1-2k tokens of context (L0 summary, stats, entry points)
- 🔄 **Before Compaction**: Analysis auto-updates to ensure context is current
- 🤖 **Zero Manual Work**: Everything happens in the background

**Verify installation:**
```bash
llm-context setup-claude --check   # Verify hooks installed
llm-context setup-claude --remove  # Uninstall hooks
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
Includes: Complete call graph, side effects, entry points, language info
```

**Result:** 80-95% token savings + better understanding

## Features

### 1. Multi-Language Support (NEW!)

Powered by [Tree-sitter](https://tree-sitter.github.io/) for robust, multi-language parsing:

```bash
# Analyzes all supported languages in your project
llm-context analyze

# Configure languages in llm-context.config.json
{
  "parser": "tree-sitter",
  "languages": {
    "enabled": ["javascript", "typescript", "python", "go", "rust"],
    "autoDetect": true
  },
  "patterns": {
    "include": [
      "**/*.js", "**/*.ts", "**/*.py", "**/*.go", "**/*.rs"
    ]
  }
}
```

**Graph output includes language:**
```json
{
  "id": "validateInput",
  "file": "validator.py",
  "line": 42,
  "language": "python",
  "calls": ["sanitize", "checkType"],
  "effects": ["logging"]
}
```

### 2. Enhanced Side Effect Detection

AST-based analysis with import tracking (replaces fragile regex):

**Before (Regex-based):**
- ❌ False positives (`myreadFunc` detected as file I/O)
- ❌ No import tracking
- ❌ Language-specific patterns hardcoded

**After (Tree-sitter AST + Import Tracking):**
- ✅ Import-aware: knows if `fs` is actually imported
- ✅ Confidence scoring: high/medium/low
- ✅ Language-specific patterns for Python, Go, Rust, etc.

**Detected categories:**
- `file_io` - File operations
- `network` - HTTP requests
- `database` - DB queries
- `logging` - Console output
- `dom` - Browser DOM manipulation

### 3. Incremental Updates (99%+ Faster)

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

### 4. Function-Level Granularity

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

### 5. Progressive Disclosure

Read only what you need:

1. **L0** (200 tokens) - System overview
2. **L1** (50-100 tokens/domain) - Domain boundaries
3. **L2** (20-50 tokens/module) - Module details
4. **Graph** (variable) - Function specifics
5. **Source** (as needed) - Targeted file reading

### 6. Query Interface

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

## CLI Commands

### Analysis

```bash
llm-context analyze              # Auto-detect full/incremental
llm-context analyze:full         # Force full re-analysis
llm-context analyze --quiet      # Suppress output (for hooks)
llm-context check-changes        # Preview changes without analyzing
```

### Queries

```bash
llm-context stats                # Show statistics
llm-context entry-points         # Find entry points
llm-context side-effects         # Functions with side effects
llm-context query <cmd> [args]   # Custom queries
```

### Claude Code Integration

```bash
llm-context setup-claude                  # Full setup (docs + hooks)
llm-context setup-claude --docs-only      # Only .claude/CLAUDE.md
llm-context setup-claude --hooks-only     # Only install hooks
llm-context setup-claude --check          # Verify hooks installed
llm-context setup-claude --remove         # Uninstall hooks
llm-context setup-claude --force          # Overwrite existing

llm-context prime                         # Inject context (called by hook)
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
    ├── L0-system.md      # System overview (~200 tokens) + query reminders
    ├── L1-domains.json   # Domain summaries
    └── L2-modules.json   # Module summaries

.claude/
├── CLAUDE.md             # Auto-generated Claude Code instructions
└── skills/               # Ships with npm package
    └── analyzing-codebases/
        ├── SKILL.md      # Main skill file
        └── ...           # Reference docs
```

### Graph Format

Each line in `graph.jsonl`:

```json
{
  "id": "authenticateUser",
  "type": "function",
  "file": "src/auth.py",
  "line": 45,
  "language": "python",
  "sig": "(async credentials)",
  "async": true,
  "calls": ["validateCredentials", "createSession"],
  "effects": ["database", "logging"]
}
```

## Usage Examples

### Understanding a New Codebase

```bash
llm-context analyze
cat .llm-context/summaries/L0-system.md
llm-context stats
llm-context entry-points
```

**With Claude (automatic):**
```
[Session starts]
Claude: [Reads llm-context prime output automatically]
"I can see this is a multi-language project with Python and JavaScript.
 156 functions across 47 files.
 Key domains: auth (12 functions), users (23), api (34)
 Entry points: main(), handleRequest()

 How can I help?"
```

### Daily Development

```bash
# Morning
git pull origin main
llm-context check-changes
llm-context analyze

# Edit code
vim src/feature.py

# Quick re-analysis (only feature.py)
llm-context analyze  # ~30ms
```

### Debugging

```bash
llm-context query find-function buggyFunc
llm-context query calls-to buggyFunc
llm-context query trace buggyFunc
llm-context side-effects | grep buggy
```

### Multi-Language Projects

```bash
# Analyze Python + JavaScript project
llm-context analyze

# Stats show both languages
llm-context stats
# Output:
#   Total Functions: 234
#   Languages: python (156), javascript (78)
#   Files: 45

# Query specific language
llm-context query find-function validateInput
# Shows language field: "language": "python"
```

## Integration

### Claude Code Hooks (Recommended)

```bash
# One-time setup
llm-context setup-claude

# Hooks install to ~/.claude/hooks/
# - session-start.sh → runs `llm-context prime`
# - pre-compact.sh → runs `llm-context analyze --quiet`
```

### Pre-commit Hook

```bash
# .git/hooks/pre-commit
#!/bin/bash
llm-context analyze
git add .llm-context/
```

### GitHub Action (Recommended)

Use the included GitHub Action for automatic analysis in CI/CD:

```yaml
# .github/workflows/llm-context.yml
name: Update LLM Context

on:
  push:
    branches: [main]
  pull_request:

jobs:
  analyze:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Analyze codebase
        uses: ./.github/actions/llm-context-action
        with:
          upload-artifact: true
```

**Features:**
- ✅ Automatic incremental updates (99% faster on subsequent runs)
- ✅ Multi-language support
- ✅ Upload artifacts for download
- ✅ Auto-commit option to keep context in repo
- ✅ PR comments with stats

See [GitHub Action README](.github/actions/llm-context-action/README.md) for complete documentation.

## How It Works

### 1. Tree-sitter Parsing

```javascript
// Language-agnostic parser with lazy loading
const parser = await ParserFactory.createParser('python');
const tree = parser.parse(sourceCode);

// Unified AST adapter
const adapter = createAdapter(tree, 'python', sourceCode, filePath);
const functions = adapter.extractFunctions();
```

**Key advantages:**
- 🌍 Supports 11+ languages (50+ available)
- 🚀 WASM-based (fast, runs in Node.js)
- 🛡️ Handles partial/invalid syntax better than Babel
- 🔄 Lazy loading (only load grammars when needed)

### 2. Enhanced Side Effect Analysis

```javascript
// Import-aware AST-based analysis
const imports = adapter.extractImports();
const analyzer = createAnalyzer('python', imports);

const effects = analyzer.analyze(calls, functionSource);
// Returns: [{ type: 'file_io', at: 'open', confidence: 'high' }]
```

**Pattern matching (per language):**
```json
{
  "python": {
    "file_io": {
      "imports": ["os", "pathlib", "shutil"],
      "calls": ["open", "read", "write"],
      "builtin": ["open"]
    }
  }
}
```

### 3. Function Extraction

```javascript
// Tree-sitter queries (language-specific)
const query = `
  (function_definition
    name: (identifier) @name
    parameters: (parameters) @params
    body: (block) @body) @function
`;

// Extract metadata
const functions = adapter.extractFunctions();
// Returns: [{name, line, params, calls, effects, language}]
```

### 4. Incremental Updates

```javascript
// Hash-based change detection
const currentHash = md5(fileContent);
const cachedHash = manifest.files[file].hash;

if (currentHash !== cachedHash) {
  // Re-analyze only this file
  const results = await analyzeFile(file);
  updateGraph(file, results);
  manifest.files[file].hash = currentHash;
}
```

### 5. Graph Generation

```javascript
// JSONL format (one function per line)
const entry = {
  id: func.name,
  file: filePath,
  line: func.line,
  language: 'python',
  calls: extractCalls(func),
  effects: detectSideEffects(func)
};

fs.appendFileSync('graph.jsonl', JSON.stringify(entry) + '\n');
```

## Performance Benchmarks

### Token Efficiency

| Approach | Tokens | Includes |
|----------|--------|----------|
| Read 10 raw files | 10,000 | Syntax only |
| LLM-Context | 500-2,000 | Call graph + semantics + language info |
| **Savings** | **80-95%** | **Better understanding** |

### Incremental Updates

| Codebase Size | Files Changed | Full Analysis | Incremental | Savings |
|---------------|---------------|---------------|-------------|---------|
| 500 files | 5 | 14s | 140ms | **99.0%** |
| 5,000 files | 10 | 2.3min | 280ms | **99.8%** |
| 50,000 files | 20 | 23min | 560ms | **99.996%** |

### Multi-Language Performance

Tree-sitter is typically **faster** than Babel:
- Python: ~2x faster than traditional Python AST
- Go: ~3x faster than go/parser
- TypeScript: No SCIP needed (SCIP was slow)

## Configuration

### llm-context.config.json

```json
{
  "parser": "tree-sitter",
  "languages": {
    "enabled": ["javascript", "typescript", "python", "go", "rust"],
    "autoDetect": true,
    "fallback": "skip"
  },
  "patterns": {
    "include": [
      "**/*.js", "**/*.ts", "**/*.jsx", "**/*.tsx",
      "**/*.py",
      "**/*.go",
      "**/*.rs",
      "**/*.java"
    ],
    "exclude": ["node_modules", ".git", ".llm-context", "dist", "build"]
  },
  "analysis": {
    "useASTAnalysis": true,
    "trackCallGraph": true,
    "detectSideEffects": true
  },
  "granularity": "function",
  "incremental": {
    "storeSource": true,
    "detectRenames": true,
    "similarityThreshold": 0.85
  }
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
- [x] Function-level granularity (98% faster for large files)
- [x] Advanced features: Rename detection, dependency analysis
- [x] GitHub Action (CI/CD integration)
- [x] **Multi-language support (11+ languages via Tree-sitter)**
- [x] **Enhanced AST-based side effect analysis**
- [x] **Claude Code hooks integration**

### 🚧 In Progress

- [ ] Watch mode (auto-analyze on file changes)
- [ ] More languages (expand to 20+)

### 📋 Planned

- [ ] VS Code extension
- [ ] Cross-language call detection
- [ ] Local LLM semantic analysis integration
- [ ] MCP server (like RepoPrompt)

## Documentation

- **[Installation Guide](CLI_INSTALLATION.md)** - Detailed setup instructions
- **[Incremental Updates](INCREMENTAL_UPDATES.md)** - How incremental updates work
- **[Function-Level Granularity](FUNCTION_LEVEL_GRANULARITY.md)** - Track changes at function level
- **[GitHub Action](.github/actions/llm-context-action/README.md)** - CI/CD integration
- **[Demo](DEMO.md)** - Live demonstrations
- **[Performance](performance-comparison.md)** - Benchmarks and projections
- **[Proof of Concept](PROOF_OF_CONCEPT_RESULTS.md)** - Original research

## Requirements

- Node.js ≥ 16.0.0 (for Tree-sitter WASM support)
- Any supported language project (JavaScript, TypeScript, Python, Go, Rust, Java, C, C++, Ruby, PHP, Bash)
- Works on Linux, macOS, Windows (WSL recommended on Windows)

## Contributing

Contributions welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

**Areas where help is needed:**
- Adding more language support (Tree-sitter grammars)
- Improving side effect pattern detection
- Performance optimizations
- Documentation improvements

## License

MIT - See [LICENSE](LICENSE) file

## Support

- **GitHub**: https://github.com/devame/llm-context-tools
- **Issues**: https://github.com/devame/llm-context-tools/issues
- **npm**: https://www.npmjs.com/package/llm-context

## Related Projects

- **[Tree-sitter](https://tree-sitter.github.io/)** - Multi-language parser
- **[SCIP](https://github.com/sourcegraph/scip)** - Code Intelligence Protocol
- **[Claude Code](https://claude.ai/code)** - AI pair programming
- **[Beads](https://github.com/steveyegge/beads)** - Git-based issue tracking (hooks inspiration)
- **[RepoPrompt](https://repoprompt.com/)** - MCP server for codebase context

## Acknowledgments

Built with:
- **[web-tree-sitter](https://github.com/tree-sitter/tree-sitter)** - WASM-based parser runtime
- **Tree-sitter grammars**: javascript, typescript, python, go, rust, java, c, cpp, ruby, php, bash, json
- **[protobufjs](https://github.com/protobufjs/protobuf.js)** - Protocol buffer parsing (for SCIP fallback)

## Citation

If you use this tool in research, please cite:

```
LLM Context Tools - Multi-Language Code Analysis for AI
https://github.com/devame/llm-context-tools
```

---

**Made with ❤️ for AI-assisted development**

**Powered by Tree-sitter 🌳**
