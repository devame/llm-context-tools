# LLM-Optimized Code Context

An **LLM-optimized code analysis system** that combines semantic analysis with powerful code search capabilities.

## Features

- 📊 **Semantic Analysis**: Function call graphs, side effects, entry points
- 🔍 **Content Search**: grep-style code searching with context
- 📈 **Multi-level Summaries**: System, domain, and module-level overviews
- ⚡ **Incremental Updates**: Only re-analyze changed files
- 🎯 **Query Interface**: Fast lookups on function relationships

## Quick Start

```bash
# Install globally
npm link

# Search code
llm-context grep "pattern" -C 5

# Analyze codebase
llm-context analyze

# Query the graph
llm-context stats
llm-context entry-points
```

## What's Here

### Generated Data Files
- **`graph.jsonl`** (16.5 KB) - Main output: one function per line
- **`summaries/L0-system.md`** - System overview (236 tokens)
- **`summaries/L1-domains.json`** - Domain summaries (136 tokens)
- **`summaries/L2-modules.json`** - Module summaries (98 tokens/3 modules)
- **`index.scip`** (403 KB) - Raw SCIP binary output
- **`scip-parsed.json`** (1.2 MB) - SCIP converted to JSON

### Tools
- **`scip-parser.js`** - Parses SCIP protobuf → JSON
- **`transformer.js`** - Combines SCIP + custom analysis → graph.jsonl
- **`summarizer.js`** - Generates multi-level summaries
- **`query.js`** - Query interface for the graph

## Usage Examples

### Content Search (NEW!)

```bash
# Search for a pattern
llm-context grep "qualified name"

# Search with context lines
llm-context grep "parsePrimary" -C 5

# Case-insensitive search
llm-context grep "error" -i

# Search in specific files
llm-context grep "TokenType" --files "parser.js"

# Search with regex
llm-context grep "Token.*SLASH"
```

### Query Interface

```bash
# Show statistics
$ node query.js stats
{
  "totalFunctions": 73,
  "filesAnalyzed": 14,
  "totalCalls": 302,
  "withSideEffects": 33,
  "effectTypes": ["logging", "database", "dom", "file_io"]
}

# Find functions with side effects
$ node query.js side-effects
Found 33 results:
  1. initialize (js/state.js:26)
     Calls: log.info, stateStorage.init, ...
     Effects: logging
  ...

# Find entry points
$ node query.js entry-points
Found 29 results:
  1. resetState (js/state.js:61)
  2. getVariables (js/state.js:79)
  ...

# Find who calls a function
$ node query.js calls-to updateVariable
Found 5 callers:
  - evalDclStatement
  - evalChgvarStatement
  ...
```

### Reading Summaries

```bash
# System overview (L0) - 236 tokens
cat summaries/L0-system.md

# Domain details (L1)
cat summaries/L1-domains.json | jq '.[] | select(.domain == "js")'

# Module details (L2)
cat summaries/L2-modules.json | jq '.[] | select(.module == "evaluator")'
```

### Consuming the Graph

```javascript
// Load graph
const fs = require('fs');
const lines = fs.readFileSync('.llm-context/graph.jsonl', 'utf-8').split('\n');
const functions = lines.filter(Boolean).map(JSON.parse);

// Find functions with database operations
const dbFuncs = functions.filter(f => f.effects.includes('database'));

// Build reverse call graph
const calledBy = new Map();
functions.forEach(func => {
  func.calls.forEach(called => {
    if (!calledBy.has(called)) calledBy.set(called, []);
    calledBy.get(called).push(func.name);
  });
});

// Trace execution path
function trace(funcName, depth = 3) {
  const func = functions.find(f => f.name === funcName);
  if (!func || depth === 0) return null;

  return {
    name: funcName,
    file: func.file,
    calls: func.calls.map(c => trace(c, depth - 1)).filter(Boolean)
  };
}
```

## Graph Format

Each line in `graph.jsonl` represents one function:

```json
{
  "id": "initialize",
  "type": "function",
  "file": "js/state.js",
  "line": 26,
  "sig": "(?)",
  "async": true,
  "calls": ["log.info", "stateStorage.init", "stateStorage.startSession"],
  "effects": ["logging"],
  "scipDoc": ""
}
```

Fields:
- **id**: Function name
- **type**: "function"
- **file**: Source file path
- **line**: Line number
- **sig**: Function signature (params)
- **async**: Whether it's async
- **calls**: Array of called function names
- **effects**: Array of side effect types
- **scipDoc**: Documentation from SCIP (if available)

## How It Works

### Phase 1: SCIP Indexing
```bash
scip-typescript index --infer-tsconfig --output .llm-context/index.scip
```
- Analyzes source files with TypeScript compiler
- Captures symbols, references, types
- Outputs binary protobuf (403 KB)
- **Time**: 656ms

### Phase 2: Custom Analysis
```javascript
// Parse each JS file with Babel
const ast = parse(sourceCode);

// Extract functions
traverse(ast, {
  FunctionDeclaration(path) {
    // Collect function metadata
  }
});

// Detect side effects
if (/read|write|fetch|query/.test(calledFunc)) {
  effects.push({ type: 'file_io' });
}
```
- Identifies 73 functions (SCIP found 0 due to JS limitations)
- Builds call graph (302 edges)
- Detects 161 side effects

### Phase 3: Transformation
```javascript
// Combine SCIP + custom data
const node = {
  id: func.name,
  calls: extractedCalls,
  effects: detectedEffects,
  scipDoc: scipSymbols.get(func.id)?.doc
};

// Write as JSONL
fs.writeFileSync('graph.jsonl',
  functions.map(f => JSON.stringify(f)).join('\n')
);
```
- Merges SCIP structure with custom semantics
- Outputs compact JSONL (97% smaller)

### Phase 4: Summarization
```javascript
// Generate L0: System overview
const L0 = generateSystemSummary(functions);

// Generate L1: Domain summaries
const L1 = groupByDirectory(functions);

// Generate L2: Module summaries
const L2 = functions.map(groupByFile);
```
- Creates hierarchical summaries
- Token-budgeted for progressive disclosure

## Results

| Metric | SCIP Alone | Hybrid Approach | Improvement |
|--------|-----------|-----------------|-------------|
| Size | 593.6 KB | 16.5 KB | ⬇ 97.2% |
| Functions | 0 | 73 | ✅ Complete |
| Call Graph | ❌ | 302 edges | ✅ Complete |
| Side Effects | ❌ | 161 | ✅ Complete |
| Token Usage | Baseline | 74% less | ⬇ 5,526/session |

## Why This Matters

### Traditional Approach
```
LLM Task: "Fix bug in variable evaluation"

Reads:
  js/evaluator.js           → 1,899 tokens
  js/state.js               → 1,077 tokens
  js/expressions.js         → 1,844 tokens
  js/statementEvaluators.js → 2,610 tokens

Total: 7,430 tokens
```

### LLM-Context Approach
```
LLM Task: "Fix bug in variable evaluation"

Loads:
  L0 (system overview)   → 236 tokens
  L1 (js domain)         → 136 tokens
  L2 (3 relevant modules)→  98 tokens
  Graph (functions)      → 1,434 tokens

Total: 1,904 tokens (74% savings)
```

## Next Steps

To make this production-ready:

1. **Incremental Updates** - Only re-analyze changed files
2. **More Languages** - Add Python, Go, Rust, Java analyzers
3. **LLM Summarization** - Use Haiku to generate intent descriptions
4. **Vector Search** - Add embeddings for semantic queries
5. **CLI Tool** - Package as `npm install -g llm-context`
6. **Watch Mode** - Auto-update on file changes

## Learn More

- **Full Analysis**: `PROOF_OF_CONCEPT_RESULTS.md`
- **SCIP Docs**: https://github.com/sourcegraph/scip
- **Babel Parser**: https://babeljs.io/docs/babel-parser

---

*Generated: 2025-11-09*
*Codebase: IBM-CL-Visualizer (27 files, 4K LOC)*
*Analysis Time: 3 seconds total*
