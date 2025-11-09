# LLM Context Tools - System Overview

**Type**: Code analysis system for LLM-optimized context generation
**Purpose**: Generate compact, semantically-rich code representations for LLM consumption
**Architecture**: JavaScript modules with incremental update support

## Statistics
- **Files**: 14 modules
- **Functions**: 83 total
- **Call relationships**: 447
- **Side effects**: logging, file_io, database, network, state_mutation

## Key Components
- **bin**: llm-context
- **.**: change-detector, dependency-analyzer, function-change-detector, function-source-extractor, incremental-analyzer, language-detector, manifest-generator, query, scip-parser, summary-updater, test-sample.py, transformer, tree-sitter-parser

## Entry Points
- None detected

## Architecture Pattern
- **Manifest System**: Tracks file hashes for change detection
- **Incremental Analysis**: Re-analyze only changed files
- **Graph Management**: JSONL format for efficient updates
- **Query Interface**: Fast lookups on function call graphs
