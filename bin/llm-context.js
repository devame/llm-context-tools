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
    process.exit(error.status || 1);
  }
}

// Command handlers
const commands = {
  analyze: () => runScript('analyze.js'),

  'analyze:full': () => {
    console.log('🔄 Forcing full re-analysis...\n');
    const manifestPath = join(process.cwd(), '.llm-context', 'manifest.json');
    if (existsSync(manifestPath)) {
      execSync(`rm "${manifestPath}"`, { stdio: 'inherit' });
    }
    runScript('analyze.js');
  },

  query: () => runScript('query.js'),

  'check-changes': () => runScript('change-detector.js'),

  init: () => {
    console.log('🚀 Initializing LLM Context Tools...\n');

    // Check if already initialized
    if (existsSync(join(process.cwd(), '.llm-context'))) {
      console.log('⚠️  .llm-context directory already exists');
      console.log('   Run "llm-context analyze" to update analysis\n');
      return;
    }

    console.log('📦 Setting up dependencies...');

    // Check for package.json
    if (!existsSync(join(process.cwd(), 'package.json'))) {
      console.log('⚠️  No package.json found. Creating one...');
      execSync('npm init -y', { stdio: 'inherit' });
    }

    // Install dependencies
    console.log('\n📥 Installing analysis dependencies...');
    execSync('npm install --save-dev @babel/parser @babel/traverse @sourcegraph/scip-typescript protobufjs', {
      stdio: 'inherit'
    });

    // Run first analysis
    console.log('\n🔍 Running initial analysis...');
    runScript('analyze.js');

    console.log('\n✅ Initialization complete!');
    console.log('\nNext steps:');
    console.log('  - Run "llm-context query stats" to see statistics');
    console.log('  - Check .llm-context/ directory for generated files');
    console.log('  - Use "llm-context help" for more commands');
  },

  stats: () => runScript('query.js', ['stats']),

  'entry-points': () => runScript('query.js', ['entry-points']),

  'side-effects': () => runScript('query.js', ['side-effects']),

  version: () => {
    const packageJson = JSON.parse(
      readFileSync(join(rootDir, 'package.json'), 'utf-8')
    );
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

// Execute command
if (commands[command]) {
  commands[command]();
} else {
  console.error(`❌ Unknown command: ${command}`);
  console.error('   Run "llm-context help" for usage\n');
  process.exit(1);
}
