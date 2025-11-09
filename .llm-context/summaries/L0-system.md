# LLM Context Tools - System Overview

**Type**: Code analysis system for LLM-optimized context generation
**Purpose**: Generate compact, semantically-rich code representations for LLM consumption
**Architecture**: JavaScript modules with incremental update support

## Statistics
- **Files**: 5 modules
- **Functions**: 22 total
- **Call relationships**: 161
- **Side effects**: file_io, logging, database

## Key Components
- **.**: scip-parser, change-detector, incremental-analyzer, query, summary-updater

## Entry Points
- None detected

## Architecture Pattern
- **Manifest System**: Tracks file hashes for change detection
- **Incremental Analysis**: Re-analyze only changed files
- **Graph Management**: JSONL format for efficient updates
- **Query Interface**: Fast lookups on function call graphs
