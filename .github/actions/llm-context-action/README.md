# LLM Context Analyzer - GitHub Action

Automatically generate and maintain LLM-optimized context for your codebase in CI/CD.

## Features

- ✅ **Automatic analysis** on every push/PR
- ✅ **Incremental updates** (99%+ faster on subsequent runs)
- ✅ **Function-level granularity** (98% faster for large files)
- ✅ **Artifact upload** for later download
- ✅ **Auto-commit** option to keep context in repo
- ✅ **Multi-language** support (JavaScript/TypeScript)

## Quick Start

### Basic Usage

```yaml
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
```

This will:
- Analyze your codebase
- Upload `.llm-context/` as an artifact
- Keep analysis results for 30 days

### Auto-commit Changes

```yaml
- name: Analyze codebase
  uses: ./.github/actions/llm-context-action
  with:
    commit-changes: true
    commit-message: 'chore: update LLM context [skip ci]'
```

**Note:** Add `[skip ci]` to avoid infinite loops!

### Custom Configuration

```yaml
- name: Analyze codebase
  uses: ./.github/actions/llm-context-action
  with:
    config: 'custom-config.json'
    working-directory: './src'
    upload-artifact: true
    artifact-name: 'codebase-context'
```

## Inputs

| Input | Description | Default | Required |
|-------|-------------|---------|----------|
| `config` | Path to llm-context.config.json | `llm-context.config.json` | No |
| `working-directory` | Directory to run analysis in | `.` | No |
| `commit-changes` | Commit .llm-context/ back to repo | `false` | No |
| `commit-message` | Commit message for updates | `chore: update LLM context` | No |
| `upload-artifact` | Upload as artifact | `true` | No |
| `artifact-name` | Artifact name | `llm-context` | No |

## Outputs

| Output | Description |
|--------|-------------|
| `context-path` | Path to .llm-context/ directory |
| `total-functions` | Number of functions analyzed |
| `total-files` | Number of files analyzed |

## Usage Examples

### PR Comment with Stats

```yaml
- name: Analyze codebase
  id: analyze
  uses: ./.github/actions/llm-context-action

- name: Comment on PR
  if: github.event_name == 'pull_request'
  uses: actions/github-script@v7
  with:
    script: |
      github.rest.issues.createComment({
        issue_number: context.issue.number,
        owner: context.repo.owner,
        repo: context.repo.repo,
        body: `## 📊 LLM Context Updated\n\n` +
              `- Functions: ${{ steps.analyze.outputs.total-functions }}\n` +
              `- Files: ${{ steps.analyze.outputs.total-files }}\n` +
              `- Context: [Download artifact](https://github.com/${{ github.repository }}/actions/runs/${{ github.run_id }})`
      });
```

### Commit Only on Main

```yaml
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
          commit-changes: ${{ github.ref == 'refs/heads/main' }}
          upload-artifact: ${{ github.event_name == 'pull_request' }}
```

### Monorepo Setup

```yaml
strategy:
  matrix:
    package: [api, frontend, backend]

steps:
  - uses: actions/checkout@v4

  - name: Analyze ${{ matrix.package }}
    uses: ./.github/actions/llm-context-action
    with:
      working-directory: ./packages/${{ matrix.package }}
      artifact-name: llm-context-${{ matrix.package }}
```

### Quality Gate

```yaml
- name: Analyze codebase
  id: analyze
  uses: ./.github/actions/llm-context-action

- name: Check complexity
  run: |
    # Fail if too many side effects
    SIDE_EFFECTS=$(jq '[.[] | select(.effects | length > 0)] | length' .llm-context/graph.jsonl)

    if [ $SIDE_EFFECTS -gt 100 ]; then
      echo "❌ Too many side effects: $SIDE_EFFECTS"
      exit 1
    fi

    echo "✅ Side effects: $SIDE_EFFECTS"
```

## Configuration

Create `llm-context.config.json` in your repo:

```json
{
  "granularity": "function",
  "incremental": {
    "enabled": true,
    "storeSource": true,
    "detectRenames": true
  },
  "analysis": {
    "trackDependencies": true,
    "detectSideEffects": true
  }
}
```

See [main README](../../../README.md) for full configuration options.

## Incremental Updates

The action automatically detects if this is an initial run or an incremental update:

**First run:**
- Generates manifest
- Analyzes all files
- ~5-30 seconds

**Subsequent runs:**
- Detects changes via file hashes
- Re-analyzes only changed files/functions
- ~100-500ms (99% faster!)

## Artifact Usage

Download the artifact to use the context:

```bash
# Via GitHub CLI
gh run download <run-id> -n llm-context

# Or from the UI
# Actions → Workflow run → Artifacts → llm-context
```

Then use with your LLM:

```bash
# Copy to your prompt
cat .llm-context/L0-system.md

# Or query specific functions
jq '.[] | select(.name == "authenticateUser")' .llm-context/graph.jsonl
```

## Permissions

If using `commit-changes: true`, ensure workflow has write permissions:

```yaml
permissions:
  contents: write
```

## Caching

Speed up even more with dependency caching:

```yaml
- name: Cache npm
  uses: actions/cache@v4
  with:
    path: ~/.npm
    key: ${{ runner.os }}-node-${{ hashFiles('**/package-lock.json') }}
```

## Troubleshooting

### "permission denied" when committing

Add to workflow:
```yaml
permissions:
  contents: write
```

### Infinite commit loop

Add `[skip ci]` to commit message:
```yaml
commit-message: 'chore: update context [skip ci]'
```

### Large artifact size

Disable source storage in config:
```json
{
  "incremental": {
    "storeSource": false
  }
}
```

## Examples

See [.github/workflows/examples/](../workflows/examples/) for complete workflow examples.

## Support

- [Documentation](../../../README.md)
- [Issues](https://github.com/devame/llm-context-tools/issues)
- [Discussions](https://github.com/devame/llm-context-tools/discussions)
