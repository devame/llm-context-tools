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
- **Files**: 6 modules
- **Functions**: 28 total
- **Call relationships**: 187
- **Side effects**: file_io, logging, database

## Key Components
- **.**: scip-parser, change-detector, incremental-analyzer, query, summary-updater, function-change-detector

## Entry Points
- None detected

## Architecture Pattern
- **Manifest System**: Tracks file hashes for change detection
- **Incremental Analysis**: Re-analyze only changed files
- **Graph Management**: JSONL format for efficient updates
- **Query Interface**: Fast lookups on function call graphs
