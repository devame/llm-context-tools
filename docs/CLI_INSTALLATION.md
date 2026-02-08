# CLI Installation & Usage Guide

## Installation

### Option 1: Global Installation (Recommended)

Install once, use everywhere:

```bash
npm install -g llm-context
```

### Option 2: Local Installation

Install in a specific project:

```bash
npm install --save-dev llm-context
```

### Option 3: npx (No Installation)

Run directly without installing:

```bash
npx llm-context analyze
```

## Quick Start

### Initialize in New Project

```bash
cd ~/my-project
llm-context init
```

This will:
- Install required dependencies
- Run initial analysis
- Generate `.llm-context/` directory with:
  - `graph.jsonl` - Function call graph
  - `manifest.json` - Change tracking
  - `summaries/` - Multi-level summaries

### Analyze Existing Project

```bash
cd ~/my-project
llm-context analyze
```

## CLI Commands

### Analysis Commands

```bash
# Auto-detect full vs incremental
llm-context analyze

# Force full re-analysis
llm-context analyze:full

# Preview changes without analyzing
llm-context check-changes
```

### Query Commands

```bash
# Show statistics
llm-context stats

# Find entry points
llm-context entry-points

# Find functions with side effects
llm-context side-effects

# Custom queries
llm-context query find-function myFunc
llm-context query calls-to myFunc
llm-context query called-by myFunc
llm-context query trace myFunc
```

### Utility Commands

```bash
# Show version
llm-context version

# Show help
llm-context help
```

## Usage with LLMs

### Claude Code Skill

The package includes a Claude Code skill that teaches LLMs how to use the tool:

**Location**: `.claude/skills/llm-context-tools/`

**Automatic Usage**: When you install this package globally or locally, Claude Code can automatically detect and use the skill when you ask questions about codebases.

**Manual Trigger**:
```
User: "Analyze this codebase using llm-context-tools"
```

### Example LLM Interaction

**User**: "Help me understand this codebase"

**Claude**:
```bash
# I'll analyze the codebase first
llm-context analyze

# Now let me read the system overview
cat .llm-context/summaries/L0-system.md

# And get some statistics
llm-context stats
```

**Claude's Response**:
```
I've analyzed your codebase. Here's what I found:

System Overview:
- Type: Web application with REST API
- 156 functions across 47 files
- Entry points: main(), handleRequest(), processQueue()

Statistics:
- Total calls: 423
- Functions with side effects: 89
- Effect types: database, network, file_io, logging

Would you like me to:
1. Explain a specific module?
2. Trace a particular function's call path?
3. Review the architecture?
```

## Workflow Examples

### Daily Development

```bash
# Morning: Pull latest
git pull origin main

# Check what changed
llm-context check-changes

# Re-analyze changed files (auto-incremental)
llm-context analyze

# Work on code...
# Edit src/auth.js

# Quick re-analysis (only analyzes auth.js)
llm-context analyze
```

### Code Review

```bash
# Checkout PR branch
git checkout feature/new-auth

# Analyze to see changes
llm-context analyze

# Compare stats
llm-context stats

# Look for new entry points
llm-context entry-points

# Check side effects
llm-context side-effects | grep auth
```

### Debugging

```bash
# Find the problematic function
llm-context query find-function processPayment

# See what calls it
llm-context query calls-to processPayment

# Trace the full call path
llm-context query trace processPayment

# Check for side effects
llm-context side-effects | grep payment
```

### Architecture Review

```bash
# Get overview
llm-context stats

# Find entry points
llm-context entry-points

# Check module organization
cat .llm-context/summaries/L1-domains.json | jq

# Review function distribution
cat .llm-context/summaries/L2-modules.json | jq
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
name: Analyze Codebase

on: [push, pull_request]

jobs:
  analyze:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-node@v3
        with:
          node-version: '18'

      - name: Install llm-context
        run: npm install -g llm-context

      - name: Run analysis
        run: llm-context analyze

      - name: Upload artifacts
        uses: actions/upload-artifact@v3
        with:
          name: llm-context
          path: .llm-context/
```

### Watch Mode (Future)

```bash
# Coming soon
llm-context watch
```

## Performance

### Initial Analysis

| Codebase Size | Time |
|---------------|------|
| 10-100 files | 1-5s |
| 100-500 files | 5-30s |
| 500-1,000 files | 30s-2min |
| 1,000-5,000 files | 2-10min |

### Incremental Updates

| Files Changed | Time |
|---------------|------|
| 1 file | 30-50ms |
| 5 files | 100-200ms |
| 10 files | 200-500ms |
| 50 files | 1-2s |

**Key Insight**: Incremental updates are 99%+ faster for large codebases!

## Generated Files

### .llm-context/graph.jsonl

Function call graph (JSONL format):

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

### .llm-context/manifest.json

Change tracking metadata:

```json
{
  "version": "1.0.0",
  "generated": "2025-11-09T12:00:00Z",
  "files": {
    "src/auth.js": {
      "hash": "abc123...",
      "size": 4567,
      "functions": ["authenticateUser", "login"],
      "analysisTime": 45
    }
  }
}
```

### .llm-context/summaries/

- **L0-system.md** (200 tokens) - Architecture overview
- **L1-domains.json** (50-100 tokens/domain) - Domain summaries
- **L2-modules.json** (20-50 tokens/module) - Module details

## Token Efficiency

**Traditional Approach** (reading raw files):
- 10 files × 1,000 tokens = 10,000 tokens
- Missing: call graphs, side effects, relationships

**LLM-Context Approach**:
- L0 + L1 + Graph queries = 500-2,000 tokens
- Includes: full call graph, side effects, architecture

**Savings**: 80-95% reduction in tokens

**Better Understanding**: Semantic context vs syntactic parsing

## Troubleshooting

### "llm-context: command not found"

**Solution**: Install globally
```bash
npm install -g llm-context
```

### "Cannot find module @babel/parser"

**Solution**: Install in project
```bash
cd ~/my-project
npm install --save-dev @babel/parser @babel/traverse protobufjs
```

Or use `llm-context init` to auto-install.

### "No JavaScript files found"

**Solution**: Make sure you're in the project root directory with .js files:
```bash
cd ~/my-project  # Must contain .js files
llm-context analyze
```

### "SCIP indexing failed"

**Solution**: This is normal for JavaScript projects. The tool automatically falls back to Babel parsing, which works perfectly.

## Uninstallation

```bash
# Global
npm uninstall -g llm-context

# Local
npm uninstall llm-context

# Clean generated files
rm -rf .llm-context/
```

## Next Steps

1. **Analyze your codebase**: `llm-context analyze`
2. **Explore the results**: `llm-context stats`
3. **Use with LLMs**: Share `.llm-context/` with AI assistants
4. **Set up automation**: Add to pre-commit hooks or CI/CD

## Support

- **GitHub**: https://github.com/devame/llm-context-tools
- **Issues**: https://github.com/devame/llm-context-tools/issues
- **Documentation**: See README.md and INCREMENTAL_UPDATES.md
- **Examples**: See DEMO.md and examples in `.claude/skills/`

## License

MIT - See LICENSE file
