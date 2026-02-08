#!/usr/bin/env node
/**
 * LLM Context Tools - Unified CLI
 *
 * Provides a unified interface for all llm-context operations
 */

import { spawn } from 'child_process';
import { existsSync, readFileSync, unlinkSync } from 'fs';
import { resolve, relative } from 'path';

// Get command and args
const [,, command, ...args] = process.argv;

// Main execution
await main();

async function main() {

// Commands that delegate to existing tools
const commands = {
  'analyze': 'src/index.js',
  'analyze:full': async () => {
    // Delete manifest and run analyze
    const manifestPath = '.llm-context/manifest.json';
    if (existsSync(manifestPath)) {
      unlinkSync(manifestPath);
    }
    return 'src/index.js';
  },
  'check-changes': 'src/core/change-detector.js',
  'query': 'src/utils/query.js',
  'stats': () => runQuery('stats'),
  'entry-points': () => runQuery('entry-points'),
  'side-effects': () => runQuery('side-effects'),
  'grep': () => runGrep(args),
  'search': () => runGrep(args),
  // 'find-symbol': () => runSymbolSearch(args),
  // 'show-context': () => showContext(args),
  'help': () => showHelp(),
  'version': () => showVersion()
};

// Show help if no command or help requested
if (!command || command === 'help' || command === '--help' || command === '-h') {
  showHelp();
  process.exit(0);
}

// Execute command
if (commands[command]) {
  const handler = commands[command];

  if (typeof handler === 'function') {
    await handler();
  } else {
    // Delegate to existing script
    const script = handler;
    const child = spawn('node', [script, ...args], {
      stdio: 'inherit',
      cwd: process.cwd()
    });

    child.on('exit', code => process.exit(code));
  }
} else {
  console.error(`❌ Unknown command: ${command}`);
  console.error(`   Run "llm-context help" for usage`);
  process.exit(1);
}

// Helper functions

async function runQuery(queryType) {
  const { default: queryModule } = await import('./utils/query.js');
  // Query module will handle its own execution
}

async function runGrep(args) {
  const { grep } = await import('./utils/grep.js');
  await grep(args);
}

  // async function runSymbolSearch(args) {
  //   const { searchSymbol } = await import('./symbol-search.js');
  //   await searchSymbol(args);
  // }

  // async function showContext(args) {
  //   const { showContext: show } = await import('./context-extractor.js');
  //   await show(args);
  // }

function showVersion() {
  const pkg = JSON.parse(readFileSync('./package.json', 'utf-8'));
  console.log(`llm-context v${pkg.version}`);
}

function showHelp() {
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

  Search:
    grep <pattern>       Search code contents (ripgrep-style)
    search <pattern>     Alias for grep
    find-symbol <name>   Find symbol definitions/usage
    show-context <ref>   Show code context around reference

  Setup:
    init                 Initialize LLM context tools in current project
    version              Show version
    help                 Show this help

GREP/SEARCH EXAMPLES

  # Search for pattern in all files
  llm-context grep "qualified name"

  # Search with context lines
  llm-context grep "parsePrimary" -C 5

  # Search in specific files
  llm-context grep "TokenType" --files "parser.js"
  llm-context grep "SLASH" --files "*.js"

  # Case-insensitive search
  llm-context grep "error" -i

  # Search with line numbers
  llm-context grep "function" -n

SYMBOL SEARCH EXAMPLES

  # Find all uses of a symbol
  llm-context find-symbol "TokenType.SLASH"
  llm-context find-symbol "QualifiedName"

CONTEXT EXTRACTION EXAMPLES

  # Show context around a function
  llm-context show-context "parser.js:parsePrimary" --lines 20

  # Extract specific line range
  llm-context show-context "parser.js:450-600"

QUERY COMMANDS

  node query.js stats                    # Statistics
  node query.js find-function <name>     # Find function
  node query.js calls-to <name>          # Who calls this?
  node query.js called-by <name>         # What does it call?
  node query.js trace <name>             # Call tree
  node query.js entry-points             # Entry points
  node query.js side-effects             # Side effects

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

} // end main()
