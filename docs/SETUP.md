# LLM-Context Setup Guide

This package contains a complete proof-of-concept LLM-optimized code analysis system.

## Quick Setup

### 1. Install Dependencies

Add to your `package.json`:

```json
{
  "devDependencies": {
    "@babel/parser": "^7.28.5",
    "@babel/traverse": "^7.28.5",
    "@sourcegraph/scip-typescript": "^0.4.0",
    "protobufjs": "^7.5.4"
  }
}
```

Then run:
```bash
npm install
```

### 2. Install SCIP Globally (Optional)

For the SCIP indexer:
```bash
npm install -g @sourcegraph/scip-typescript
```

### 3. Run the Analysis

```bash
# Full pipeline (run in your project root)
# 1. Index with SCIP
scip-typescript index --infer-tsconfig --output .llm-context/index.scip

# 2. Transform SCIP + custom analysis → graph
node .llm-context/transformer.js

# 3. Generate multi-level summaries
node .llm-context/summarizer.js

# 4. Query the results
node .llm-context/query.js stats
```

## Usage

### Query Interface

```bash
# Statistics
node .llm-context/query.js stats

# Find functions with side effects
node .llm-context/query.js side-effects

# Find entry points
node .llm-context/query.js entry-points

# Find function by name
node .llm-context/query.js find-function <name>

# Show who calls a function
node .llm-context/query.js calls-to <function-name>

# Show what a function calls
node .llm-context/query.js called-by <function-name>

# Trace call tree
node .llm-context/query.js trace <function-name>
```

### View Summaries

```bash
# System overview (L0)
cat .llm-context/summaries/L0-system.md

# Domain summaries (L1)
cat .llm-context/summaries/L1-domains.json

# Module summaries (L2)
cat .llm-context/summaries/L2-modules.json
```

### Consume the Graph

The main output is `graph.jsonl` - one function per line in JSON format:

```javascript
const fs = require('fs');
const lines = fs.readFileSync('.llm-context/graph.jsonl', 'utf-8').split('\n');
const functions = lines.filter(Boolean).map(JSON.parse);

// Now you can query functions
const withSideEffects = functions.filter(f => f.effects.length > 0);
const asyncFunctions = functions.filter(f => f.async);
```

## Files in This Package

### Essential Tools (Run These)
- **transformer.js** - Main tool: SCIP + custom analysis → graph
- **summarizer.js** - Generates L0/L1/L2 summaries
- **query.js** - Query interface for the graph

### Generated Data (Examples from IBM-CL-Visualizer)
- **graph.jsonl** - LLM-optimized function graph (16.5 KB)
- **summaries/** - Multi-level summaries (L0/L1/L2)
- **index.scip** - Raw SCIP output (can be regenerated)
- **scip-parsed.json** - SCIP as JSON (can be regenerated)

### Supporting Files
- **scip-parser.js** - Utility: Parse SCIP protobuf → JSON
- **scip.proto** - SCIP protocol buffer schema

### Documentation
- **README.md** - Main documentation
- **PROOF_OF_CONCEPT_RESULTS.md** - Detailed analysis of results
- **SETUP.md** - This file

## Customization

### Adding More Side Effect Patterns

Edit `transformer.js`, around line 150-172:

```javascript
// Add your custom side effect detection
if (/yourPattern/i.test(calledName)) {
  effects.push({ type: 'your_effect_type', at: calledName });
}
```

### Supporting Other Languages

Currently supports JavaScript/TypeScript. To add more:

1. Install appropriate SCIP indexer (scip-java, scip-python, etc.)
2. Modify `transformer.js` to use appropriate AST parser
3. Add language-specific side effect patterns

### Adjusting Summary Levels

Edit `summarizer.js`:
- L0 generation: around line 45
- L1 generation: around line 75
- L2 generation: around line 105

## Token Budget Example

For the IBM-CL-Visualizer codebase:

**Traditional**: Read 4 files = 7,430 tokens

**LLM-Context**:
- L0 (system) = 236 tokens
- L1 (domain) = 136 tokens
- L2 (modules) = 98 tokens
- Graph (functions) = 1,434 tokens
- **Total = 1,904 tokens (74% savings)**

## Incremental Updates (Future Enhancement)

Not yet implemented, but the design supports it:

```javascript
// Planned feature
const fileHashes = computeHashes(files);
const changed = detectChanges(fileHashes, '.llm-context/hashes.json');
reanalyzeOnly(changed);
updateGraph(changed);
```

## Troubleshooting

### SCIP doesn't detect functions in JavaScript
- This is expected - SCIP marks untyped JS as "Unknown"
- The custom Babel analyzer fills this gap
- Use TypeScript for better SCIP results

### "Cannot find module" errors
- Make sure you ran `npm install`
- Check that all devDependencies are installed

### Empty graph.jsonl
- Check for parse errors in transformer.js output
- Try running on a single file first to debug

## License

MIT - Use freely in your projects

## Questions?

See the full analysis in `PROOF_OF_CONCEPT_RESULTS.md`
