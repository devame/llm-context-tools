#!/usr/bin/env node
/**
 * LLM Context Tools - CLI Entry Point
 *
 * Global CLI for generating LLM-optimized code context
 */

import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { execSync } from 'child_process';
import { existsSync, readFileSync } from 'fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const rootDir = join(__dirname, '..');

// Parse command line arguments
const args = process.argv.slice(2);
const command = args[0] || 'help';
const commandArgs = args.slice(1);

// Helper to run script from package
// Helper to run script from package
function runScript(scriptPath, extraArgs = []) {
  const fullPath = join(rootDir, scriptPath);

  if (!existsSync(fullPath)) {
    console.error(`Error: Script not found: ${scriptPath}`);
    process.exit(1);
  }

  try {
    const allArgs = [...extraArgs, ...commandArgs].join(' ');
    execSync(`node "${fullPath}" ${allArgs}`, {
      stdio: 'inherit',
      cwd: process.cwd()
    });
  } catch (error) {
    process.exit(1);
  }
}

const commands = {
  analyze: () => runScript('src/index.js'),
  'analyze:full': () => runScript('src/index.js', ['--full']),
  'check-changes': () => runScript('src/core/change-detector.js'),

  query: () => runScript('src/utils/query.js'),
  stats: () => runScript('src/utils/query.js', ['stats']),
  'entry-points': () => runScript('src/utils/query.js', ['entry-points']),
  'side-effects': () => runScript('src/utils/query.js', ['side-effects']),

  init: () => {
    console.log('Initializing llm-context...');
    // TODO: explicit init script if needed
    runScript('src/index.js');
  },

  'setup-claude': () => runScript('src/setup/setup-claude.js'),
  prime: () => runScript('src/setup/prime.js'),

  version: () => {
    const packageJson = JSON.parse(readFileSync(join(rootDir, 'package.json'), 'utf-8'));
    console.log(`llm-context v${packageJson.version}`);
  },

  help: () => {
    console.log(`
╔═══════════════════════════════════════════════════════════════╗
║                   LLM Context Tools CLI                        ║
║   Generate LLM-optimized code context with incremental updates ║
╚═══════════════════════════════════════════════════════════════╝

USAGE
  llm-context <command> [options]

COMMANDS

  Analysis:
    analyze              Run analysis (auto-detects full vs incremental)
    analyze:full         Force full re-analysis
    check-changes        Preview what files changed

  Queries:
    query <cmd> [args]   Query the generated graph
    stats                Show codebase statistics
    entry-points         Find entry point functions
    side-effects         Find functions with side effects

  Setup:
    init                 Initialize LLM context tools in current project
    setup-claude         Setup Claude Code integration (docs + hooks)
    prime                Inject context at session start (used by hooks)
    version              Show version
    help                 Show this help

QUERY COMMANDS

  node query.js stats                    # Statistics
  node query.js find-function <name>     # Find function
  node query.js calls-to <name>          # Who calls this?
  node query.js called-by <name>         # What does it call?
  node query.js trace <name>             # Call tree
  node query.js entry-points             # Entry points
  node query.js side-effects             # Side effects

EXAMPLES

  # Initialize in a new project
  cd ~/my-project
  llm-context init

  # Analyze codebase
  llm-context analyze

  # Query results
  llm-context stats
  llm-context entry-points
  llm-context query calls-to myFunction

  # Check what changed
  llm-context check-changes

  # Force full re-analysis
  llm-context analyze:full

  # Setup Claude Code integration
  llm-context setup-claude                  # Full setup (docs + hooks)
  llm-context setup-claude --docs-only      # Only .claude/CLAUDE.md
  llm-context setup-claude --hooks-only     # Only install hooks
  llm-context setup-claude --check          # Verify hooks installed
  llm-context setup-claude --remove         # Uninstall hooks
  llm-context setup-claude --force          # Overwrite existing

GENERATED FILES

  .llm-context/
  ├── graph.jsonl           # Function call graph
  ├── manifest.json         # Change tracking
  └── summaries/
      ├── L0-system.md      # System overview
      ├── L1-domains.json   # Domain summaries
      └── L2-modules.json   # Module summaries

DOCUMENTATION

  GitHub: https://github.com/devame/llm-context-tools
  Issues: https://github.com/devame/llm-context-tools/issues

`);
  }
};

// Execute command (handle async commands)
if (commands[command]) {
  const result = commands[command]();
  if (result instanceof Promise) {
    result.catch(err => {
      console.error('❌ Error:', err.message);
      process.exit(1);
    });
  }
} else {
  console.error(`❌ Unknown command: ${command}`);
  console.error('   Run "llm-context help" for usage\n');
  process.exit(1);
}
