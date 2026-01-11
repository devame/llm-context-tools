# LLM Context Tools - System Overview

**Type**: Code analysis system for LLM-optimized context generation
**Purpose**: Generate compact, semantically-rich code representations for LLM consumption
**Architecture**: JavaScript modules with incremental update support

## ⚡ Quick Queries (USE THESE before grep/read)

**To understand this codebase, try these queries FIRST:**

```bash
# Find any function
llm-context query find-function <name>

# Understand dependencies
llm-context query calls-to <name>      # Who calls this?
llm-context query trace <name>         # Full call tree

# Discover patterns
llm-context entry-points               # 0 entry points
llm-context side-effects               # Functions with I/O

# Statistics
llm-context stats                      # Full statistics
```

**Why queries > grep:**
- ✅ Show call relationships (grep can't)
- ✅ Detect side effects (grep misses these)
- ✅ Trace call trees (grep shows only text matches)
- ✅ 80-95% fewer tokens needed

## Statistics
- **Files**: 17 modules
- **Functions**: 108 total
- **Call relationships**: 516
- **Side effects**: network, database, logging, file_io, dom

## Key Components
- **.**: ast-adapter, change-detector, claude-setup, dependency-analyzer, full-analysis, function-change-detector, function-source-extractor, incremental-analyzer, language-queries, manifest-generator, parser-factory, query, scip-parser, side-effects-analyzer, summary-updater, transformer
- **bin**: llm-context

## Entry Points
- None detected

## Architecture Pattern
- **Manifest System**: Tracks file hashes for change detection
- **Incremental Analysis**: Re-analyze only changed files
- **Graph Management**: JSONL format for efficient updates
- **Query Interface**: Fast lookups on function call graphs
