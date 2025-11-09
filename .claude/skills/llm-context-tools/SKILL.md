---
name: llm-context-tools
description: Generate LLM-optimized code context with incremental updates for efficient codebase understanding
categories:
  - code-analysis
  - developer-tools
  - llm-optimization
triggers:
  - analyze codebase
  - generate code context
  - llm context
  - code intelligence
  - incremental analysis
version: 0.2.0
---

# LLM Context Tools Skill

This skill helps LLMs efficiently understand codebases by generating compact, semantically-rich code representations with incremental update support.

## What This Skill Does

Generates and maintains LLM-optimized code context that includes:
- **Function-level call graphs** with side effect detection
- **Multi-level summaries** (System → Domain → Module)
- **Incremental updates** (99%+ faster re-analysis)
- **Hash-based change detection**
- **Query interface** for instant lookups

## When To Use This Skill

Use this skill when:
- User asks to "analyze this codebase"
- User wants to understand code architecture
- User needs help debugging or refactoring
- You need efficient context about a large codebase
- User wants to set up LLM-friendly code documentation

## How It Works

### Progressive Disclosure Strategy

1. **L0 (System Overview)** - 200 tokens - Architecture, entry points, key components
2. **L1 (Domain Summaries)** - 50-100 tokens per domain - Subsystem boundaries
3. **L2 (Module Summaries)** - 20-50 tokens per module - File-level details
4. **Graph (Function Details)** - Variable tokens - Call relationships, side effects

**Token Efficiency**: 74-97% reduction vs reading raw source files

### Analysis Modes

**Initial Analysis** (first time):
- Runs SCIP indexer (if available)
- Parses all JavaScript/TypeScript files
- Builds complete function graph
- Generates manifest with MD5 hashes
- Creates multi-level summaries

**Incremental Analysis** (subsequent runs):
- Detects changes via hash comparison
- Re-analyzes only changed files (99%+ faster)
- Patches graph without full rebuild
- Updates only affected summaries

## Installation & Setup

### Step 1: Check if tool is installed

```bash
# Check if analysis tools exist
ls -la analyze.js manifest-generator.js 2>/dev/null
```

If files don't exist, the tool needs to be installed in this project.

### Step 2: Install dependencies

```bash
npm install --save-dev @babel/parser @babel/traverse @sourcegraph/scip-typescript protobufjs
```

### Step 3: Run initial analysis

```bash
node analyze.js
```

This will:
- Create `.llm-context/` directory
- Generate function graph (`graph.jsonl`)
- Create manifest (`manifest.json`)
- Build summaries (`L0-system.md`, `L1-domains.json`, `L2-modules.json`)

## Usage Patterns

### Pattern 1: First-Time Codebase Understanding

**User asks**: "Help me understand this codebase"

**LLM workflow**:
```bash
# 1. Check if already analyzed
ls .llm-context/manifest.json

# 2. If not, run analysis
node analyze.js

# 3. Read L0 for overview
cat .llm-context/summaries/L0-system.md

# 4. Query for specifics
node query.js stats
node query.js entry-points
```

**Response template**:
```
I've analyzed the codebase. Here's what I found:

[L0 Summary Content]

Key statistics:
- X functions across Y files
- Z call relationships
- Entry points: [list]

Would you like me to:
1. Explain a specific domain/module?
2. Trace a particular function's call path?
3. Find functions with specific side effects?
```

### Pattern 2: Incremental Updates

**User asks**: "I just edited some files, what changed?"

**LLM workflow**:
```bash
# 1. Check for changes
node change-detector.js

# 2. Run incremental analysis
node analyze.js

# 3. Read updated manifest to see what changed
cat .llm-context/manifest.json | grep -A5 '"generated"'
```

**Response template**:
```
I've re-analyzed the changed files. Here's what's new:

Files changed: X
Files skipped: Y (Z% efficiency)

Changes detected:
- [file]: Added function `foo()`, modified `bar()`
- [file]: New side effects detected

Would you like me to explain the impact of these changes?
```

### Pattern 3: Debugging Assistance

**User asks**: "There's a bug in the checkout flow"

**LLM workflow**:
```bash
# 1. Find checkout-related functions
node query.js find-function checkout

# 2. Trace the call path
node query.js trace handleCheckout

# 3. Check for side effects
node query.js side-effects | grep checkout
```

**Response template**:
```
Based on the call graph analysis:

Checkout flow:
handleCheckout() → validateCart() → processPayment() → createOrder()

Side effects detected:
- validateCart(): database reads
- processPayment(): network calls (external API)
- createOrder(): database writes + email sending

The bug is likely in [function] because [reasoning based on side effects].

Let me read that specific file to investigate...
```

### Pattern 4: Code Navigation

**User asks**: "What calls the `updateUser` function?"

**LLM workflow**:
```bash
node query.js calls-to updateUser
```

**Response template**:
```
The `updateUser` function is called by:
1. handleProfileUpdate (users.js:45)
2. adminUpdateUser (admin.js:78)
3. syncUserData (sync.js:23)

Would you like me to explain any of these call sites?
```

## Available Commands

### Analysis Commands

```bash
# Main analysis (auto-detects full vs incremental)
node analyze.js

# Force full re-analysis
npm run analyze:full

# Check what changed without analyzing
node change-detector.js
```

### Query Commands

```bash
# Statistics
node query.js stats

# Find function by name
node query.js find-function <name>

# Show what calls a function
node query.js calls-to <name>

# Show what a function calls
node query.js called-by <name>

# Find functions with side effects
node query.js side-effects

# Find entry points
node query.js entry-points

# Trace call tree
node query.js trace <name>
```

## Understanding the Generated Files

### `.llm-context/graph.jsonl`

JSONL format (one function per line):

```json
{
  "id": "processPayment",
  "type": "function",
  "file": "checkout.js",
  "line": 45,
  "sig": "(amount, userId)",
  "async": true,
  "calls": ["validateAmount", "stripe.charge", "createTransaction"],
  "effects": ["network", "database"],
  "scipDoc": ""
}
```

**Read this for**: Detailed function information, call graphs, side effects

### `.llm-context/summaries/L0-system.md`

System-level overview (~200 tokens):
- Architecture type
- Key components
- Entry points
- Statistics

**Read this for**: Initial codebase understanding

### `.llm-context/summaries/L1-domains.json`

Domain-level summaries (50-100 tokens each):
- Module lists per directory
- Function counts
- Side effect types
- Key functions

**Read this for**: Understanding subsystem boundaries

### `.llm-context/summaries/L2-modules.json`

Module-level summaries (20-50 tokens each):
- Exports
- Entry points
- Effect types
- Function counts

**Read this for**: File-level details

### `.llm-context/manifest.json`

Change tracking metadata:
- File MD5 hashes
- Last analysis time
- Functions per file
- Global statistics

**Read this for**: Understanding what changed, when analysis was last run

## Best Practices for LLMs

### 1. Start with Summaries, Not Source

❌ **Don't**: Read raw source files first
✅ **Do**: Read L0 → L1 → L2 → Graph → Source (as needed)

**Why**: 95%+ token savings, better context

### 2. Use Queries Before Reading

❌ **Don't**: Grep through files manually
✅ **Do**: Use `query.js` for instant lookups

**Why**: Pre-built indices are faster and more accurate

### 3. Leverage Incremental Updates

❌ **Don't**: Re-read entire codebase on every change
✅ **Do**: Run `node analyze.js` to get incremental updates

**Why**: 99%+ faster re-analysis at scale

### 4. Check Manifest Age

```bash
# Check when last analyzed
cat .llm-context/manifest.json | grep generated
```

If older than user's last edit, suggest re-running analysis.

### 5. Explain Side Effects

When helping with debugging, always mention detected side effects:
- `file_io`: Reads/writes files
- `network`: External API calls
- `database`: Database queries
- `logging`: Console/log output
- `dom`: Browser DOM manipulation

## Performance Characteristics

### Small Codebase (< 100 files)
- Initial analysis: ~2-5 seconds
- Incremental: ~50-200ms
- Token usage: 500-2,000 tokens

### Medium Codebase (100-1,000 files)
- Initial analysis: ~20-60 seconds
- Incremental: ~200-500ms
- Token usage: 2,000-10,000 tokens

### Large Codebase (1,000-10,000 files)
- Initial analysis: ~5-15 minutes
- Incremental: ~500ms-2s
- Token usage: 10,000-50,000 tokens

**Key insight**: Incremental updates maintain sub-second response times regardless of codebase size.

## Troubleshooting

### "No manifest found"

**Solution**: Run initial analysis
```bash
node analyze.js
```

### "Cannot find module @babel/parser"

**Solution**: Install dependencies
```bash
npm install
```

### "SCIP indexing failed"

**Solution**: This is normal for JavaScript projects. The tool falls back to Babel parsing.

### "Parse errors in file.js"

**Solution**: File has syntax errors. Fix syntax or exclude from analysis.

### "Graph is empty"

**Solution**: Check that JS files exist and are being discovered. Try:
```bash
node manifest-generator.js
cat .llm-context/manifest.json
```

## Integration Examples

### Example 1: Architecture Review

```
User: "Review the architecture of this codebase"

LLM workflow:
1. node analyze.js
2. cat .llm-context/summaries/L0-system.md
3. node query.js stats
4. node query.js entry-points

Response:
Based on the analysis:
- Architecture: [type from L0]
- [X] functions across [Y] modules
- Entry points: [list]
- Key patterns: [observations]

Recommendations:
1. [Based on call graph complexity]
2. [Based on side effect distribution]
3. [Based on entry point organization]
```

### Example 2: Refactoring Assistance

```
User: "I want to refactor the authentication module"

LLM workflow:
1. node query.js find-function auth
2. node query.js calls-to login
3. node query.js trace authenticateUser
4. Read .llm-context/summaries/L2-modules.json for auth.js

Response:
The authentication module has:
- [X] functions
- Called by [Y] other modules
- Side effects: [list]

Refactoring considerations:
1. [Functions that depend on it]
2. [Side effects to preserve]
3. [Suggested approach based on call graph]
```

### Example 3: Bug Investigation

```
User: "Users report errors during checkout"

LLM workflow:
1. node query.js find-function checkout
2. node query.js side-effects | grep checkout
3. node query.js trace processCheckout

Response:
Checkout flow analysis:
- [Call path from entry point]
- Side effects: [network, database, etc]
- Potential failure points:
  1. [Based on network effects]
  2. [Based on database operations]

Let me read the specific functions with external dependencies...
[Read targeted source files]
```

## Advanced Usage

### Combining with Other Tools

```bash
# Use with git to understand changes in a PR
git diff main --name-only | xargs node summary-updater.js

# Watch mode (future feature)
# node analyze.js --watch

# Export for team sharing
tar -czf llm-context.tar.gz .llm-context/
```

### Custom Queries

The graph is JSONL, so you can use standard tools:

```bash
# Find all async functions
cat .llm-context/graph.jsonl | jq 'select(.async == true)'

# Functions with network effects
cat .llm-context/graph.jsonl | jq 'select(.effects | contains(["network"]))'

# Most-called functions
cat .llm-context/graph.jsonl | jq '.calls | length' | sort -rn | head
```

## Limitations & Future

### Current Limitations
- JavaScript/TypeScript only
- File-level granularity (not function-level)
- No cross-file dependency tracking
- Pattern-based side effect detection (may miss some)

### Planned Features
- Multi-language support (Python, Go, Rust, Java)
- Function-level change detection
- Cross-file dependency tracking
- Watch mode for real-time updates
- LLM-powered intent summarization
- Vector embeddings for semantic search

## Success Criteria

This skill is working correctly when:

1. ✅ Analysis completes without errors
2. ✅ `.llm-context/` directory contains all expected files
3. ✅ `query.js stats` shows detected functions
4. ✅ Incremental updates are 10-100x faster than full analysis
5. ✅ LLM can navigate codebase using summaries + graph
6. ✅ Token usage is 50-95% less than reading raw files

## Summary

This skill transforms codebase understanding from:
- ❌ Reading thousands of lines of code token-by-token
- ❌ Missing global context and relationships
- ❌ Slow, expensive re-analysis on every change

To:
- ✅ Compact, semantic code representations
- ✅ Call graphs and side effect detection
- ✅ 99%+ faster incremental updates
- ✅ 50-95% token savings

**Net result**: 10-100x more effective LLM assistance for understanding and working with code.
