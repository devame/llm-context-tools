#!/usr/bin/env node
/**
 * Main Analysis Script - Unified workflow for code analysis
 *
 * Usage:
 *   node analyze.js          - Initial full analysis
 *   node analyze.js --quiet  - Suppress non-error output
 *   node analyze.js --watch  - Watch mode (future enhancement)
 *
 * This script orchestrates the complete analysis pipeline:
 * 1. Check for existing manifest
 * 2. If no manifest: full analysis
 * 3. If manifest exists: incremental analysis
 * 4. Update summaries
 */

import { existsSync, readFileSync, writeFileSync } from 'fs';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { detectChanges } from './core/change-detector.js';
import { updateSummaries } from './utils/summary-updater.js';
import { setupClaudeIntegration } from './setup/claude-setup.js';
import { detectLanguages, printLanguageReport, shouldAnalyze } from './core/language-detector.js';
import { promptYesNo } from './utils/prompt-helper.js';

// Get package directory
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Check for --quiet flag
const isQuiet = process.argv.includes('--quiet');
const stdio = isQuiet ? 'ignore' : 'inherit';

// Logging helper
function log(...args) {
  if (!isQuiet) {
    console.log(...args);
  }
}

// Map file extensions to tree-sitter parser package names and grammar info
function getTreeSitterParserInfo(extension) {
  const parserMap = {
    '.clj': { package: 'tree-sitter-clojure', language: 'clojure', extensions: ['.clj', '.cljs', '.cljc'] },
    '.cljs': { package: 'tree-sitter-clojure', language: 'clojure', extensions: ['.clj', '.cljs', '.cljc'] },
    '.cljc': { package: 'tree-sitter-clojure', language: 'clojure', extensions: ['.clj', '.cljs', '.cljc'] },
    '.janet': null, // No tree-sitter parser available yet
    '.elm': { package: 'tree-sitter-elm', language: 'elm', extensions: ['.elm'] },
    '.ex': { package: 'tree-sitter-elixir', language: 'elixir', extensions: ['.ex', '.exs'] },
    '.exs': { package: 'tree-sitter-elixir', language: 'elixir', extensions: ['.ex', '.exs'] },
    '.erl': { package: 'tree-sitter-erlang', language: 'erlang', extensions: ['.erl', '.hrl'] },
    '.hrl': { package: 'tree-sitter-erlang', language: 'erlang', extensions: ['.erl', '.hrl'] },
    '.hs': { package: 'tree-sitter-haskell', language: 'haskell', extensions: ['.hs'] },
    '.scala': { package: 'tree-sitter-scala', language: 'scala', extensions: ['.scala'] },
    '.kt': { package: 'tree-sitter-kotlin', language: 'kotlin', extensions: ['.kt'] },
    '.swift': { package: 'tree-sitter-swift', language: 'swift', extensions: ['.swift'] },
    '.jl': { package: 'tree-sitter-julia', language: 'julia', extensions: ['.jl'] },
    '.r': { package: 'tree-sitter-r', language: 'r', extensions: ['.r', '.R'] },
    '.ml': { package: 'tree-sitter-ocaml', language: 'ocaml', extensions: ['.ml', '.mli'] },
    '.mli': { package: 'tree-sitter-ocaml', language: 'ocaml', extensions: ['.ml', '.mli'] },
    '.fs': null, // F# doesn't have official tree-sitter
    '.rkt': { package: 'tree-sitter-racket', language: 'racket', extensions: ['.rkt'] },
    '.lua': { package: 'tree-sitter-lua', language: 'lua', extensions: ['.lua'] },
    '.zig': { package: 'tree-sitter-zig', language: 'zig', extensions: ['.zig'] },
    '.nim': null,
    '.cr': { package: 'tree-sitter-crystal', language: 'crystal', extensions: ['.cr'] },
    '.d': { package: 'tree-sitter-d', language: 'd', extensions: ['.d'] },
    '.dart': { package: 'tree-sitter-dart', language: 'dart', extensions: ['.dart'] },
  };
  return parserMap[extension] || null;
}

// Helper to get just the package name
function getTreeSitterParserName(extension) {
  const info = getTreeSitterParserInfo(extension);
  return info?.package || null;
}

// Install missing parsers
async function installMissingParsers(unsupportedLanguages) {
  const packagesToInstall = new Map(); // package -> [extensions]
  const unavailableLanguages = [];

  // Collect packages to install
  for (const lang of unsupportedLanguages) {
    const info = getTreeSitterParserInfo(lang.extension);
    if (info) {
      if (!packagesToInstall.has(info.package)) {
        packagesToInstall.set(info.package, { language: info.language, extensions: info.extensions });
      }
    } else {
      unavailableLanguages.push(lang);
    }
  }

  if (packagesToInstall.size === 0) {
    console.error('\n❌ No installable parsers available for your languages.');
    console.error('   These languages do not have tree-sitter parsers yet:');
    for (const lang of unavailableLanguages) {
      console.error(`   - ${lang.name}`);
    }
    console.error('\n   Please file an issue to request support at:');
    console.error('   https://github.com/devame/llm-context-tools/issues\n');
    return false;
  }

  // Show what will be installed
  console.log('\n📦 Available parser packages:\n');
  const packages = Array.from(packagesToInstall.keys());
  for (const pkg of packages) {
    const info = packagesToInstall.get(pkg);
    console.log(`   ${pkg.padEnd(30)} → ${info.language} (${info.extensions.join(', ')})`);
  }

  if (unavailableLanguages.length > 0) {
    console.log('\n⚠️  Not available (will still be skipped):');
    for (const lang of unavailableLanguages) {
      console.log(`   ${lang.name.padEnd(30)} → No parser exists yet`);
    }
  }

  // Show commands
  const installCmd = `npm install --save-dev ${packages.join(' ')}`;
  console.log('\n🔧 Commands to run:\n');
  console.log(`   ${installCmd}\n`);

  // Prompt user
  const shouldInstall = await promptYesNo('\n📥 Install these parser packages now?');

  if (!shouldInstall) {
    console.log('\n⚠️  Installation cancelled. Cannot proceed without language support.\n');
    return false;
  }

  // Install packages
  console.log('\n📥 Installing packages...\n');
  try {
    execSync(installCmd, { stdio: 'inherit', cwd: process.cwd() });
    console.log('\n✅ Packages installed successfully!\n');

    // Update parser-factory.js
    console.log('🔧 Updating parser configuration...\n');
    updateParserFactory(packagesToInstall);
    console.log('✅ Parser configuration updated!\n');

    return true;
  } catch (error) {
    console.error('\n❌ Installation failed:', error.message);
    console.error('   You may need to install manually with:');
    console.error(`   ${installCmd}\n`);
    return false;
  }
}

// Update parser-factory.js with new language support
function updateParserFactory(packagesToInstall) {
  const parserFactoryPath = join(__dirname, 'parser-factory.js');
  let content = readFileSync(parserFactoryPath, 'utf-8');

  // Add to GRAMMAR_PATHS
  for (const [pkg, info] of packagesToInstall) {
    const grammarEntry = `  ${info.language}: '${pkg}',`;

    // Check if already exists
    if (!content.includes(`${info.language}:`)) {
      // Add before the closing brace of GRAMMAR_PATHS
      content = content.replace(
        /(const GRAMMAR_PATHS = \{[^}]+)(}\;)/,
        `$1  ${info.language}: '${pkg}',\n$2`
      );
    }

    // Add extensions to EXTENSION_MAP
    for (const ext of info.extensions) {
      if (!content.includes(`'${ext}':`)) {
        content = content.replace(
          /(const EXTENSION_MAP = \{[^}]+)(}\;)/,
          `$1  '${ext}': '${info.language}',\n$2`
        );
      }
    }
  }

  writeFileSync(parserFactoryPath, content, 'utf-8');
}

log('=== LLM Context Analyzer ===\n');

// Main async function
(async () => {
  const args = process.argv.slice(2);
  const targetArg = args.find(arg => !arg.startsWith('-'));
  const targetDir = targetArg || '.';
  const isFullAnalysis = args.includes('--full');

  const manifestExists = existsSync(join(targetDir, '.llm-context/manifest.json'));
  const graphExists = existsSync(join(targetDir, '.llm-context/graph.jsonl'));

  if (isFullAnalysis || !manifestExists || !graphExists) {
    log(`🔍 ${isFullAnalysis ? 'Forced full analysis' : 'No previous analysis found'} in ${targetDir}...\n`);

  // Step 0: Detect languages
    log(`[0/7] Detecting project languages in ${targetDir}...\n`);
    const detection = detectLanguages(targetDir);
  const canAnalyze = printLanguageReport(detection);

  // Check if we have NO supported languages
  if (!shouldAnalyze(detection)) {
    console.error('\n❌ Cannot analyze: No supported source files found.');
    console.error('   This project appears to use languages not yet supported.');
    console.error('\n   Supported languages: JavaScript, TypeScript, Python, Go, Rust, Java, C/C++, Ruby, PHP, Bash');
    console.error('\n   To add support for other languages, please file an issue at:');
    console.error('   https://github.com/devame/llm-context-tools/issues\n');
    process.exit(1);
  }

  // Check if unsupported languages dominate
  if (detection.unsupported.length > 0) {
    const unsupportedTotal = detection.unsupported.reduce((sum, l) => sum + l.count, 0);
    const supportedTotal = detection.supported.reduce((sum, l) => sum + l.count, 0);

    if (unsupportedTotal > supportedTotal) {
      console.error('\n🚨 CRITICAL: Primary codebase uses unsupported languages!\n');
      console.error(`   Your project has ${unsupportedTotal} unsupported files vs ${supportedTotal} supported files.`);
      console.error('   Analysis would only cover a small fraction of your codebase.\n');

      // Offer to install missing parsers
      const installed = await installMissingParsers(detection.unsupported);

      if (!installed) {
        console.error('\n⚠️  Cannot proceed without language support.\n');
        process.exit(1);
      }

      // Re-run language detection after installing
      console.log('🔄 Re-detecting languages after installation...\n');
      const newDetection = detectLanguages();
      printLanguageReport(newDetection);

      if (!shouldAnalyze(newDetection)) {
        console.error('\n❌ Still cannot analyze after installation. Exiting.\n');
        process.exit(1);
      }
    }
  }

  if (!canAnalyze) {
    console.error('\n⚠️  Proceeding with caution: Analysis may not capture the primary codebase.\n');
  }

  // Step 1: Create .llm-context directory
  log('[1/7] Setting up analysis directory...');
    execSync('mkdir -p .llm-context', { stdio, cwd: targetDir });

  // Step 2: Run SCIP indexer (if needed for typed languages)
  log('\n[2/7] Running SCIP indexer...');
  try {
    execSync(`npx scip-typescript index --infer-tsconfig --output .llm-context/index.scip 2>/dev/null || echo "SCIP indexing skipped"`, { stdio, cwd: targetDir });
  } catch (error) {
    log('  ⚠ SCIP indexing failed (continuing with custom analysis only)');
  }

  // Step 3: Parse SCIP output (if available)
  log('\n[3/7] Parsing SCIP data...');
  if (existsSync('.llm-context/index.scip')) {
    try {
      execSync(`cp "${join(__dirname, '../data/scip.proto')}" .llm-context/ && node "${join(__dirname, 'parser/scip-parser.js')}"`, { stdio, cwd: targetDir });
    } catch (error) {
      log('  ⚠ SCIP parsing failed');
    }
  } else {
    log('  ⚠ No SCIP data to parse');
  }

  // Step 4: Run full analysis (Tree-sitter based)
  log('\n[4/7] Running full analysis...');
    execSync(`node "${join(__dirname, 'core/full-analysis.js')}"`, { stdio, cwd: targetDir });

  // Step 5: Generate initial manifest
  log('\n[5/7] Generating manifest...');
    execSync(`node "${join(__dirname, 'parser/manifest-generator.js')}"`, { stdio, cwd: targetDir });

  // Step 6: Generate summaries
  log('\n[6/7] Generating summaries...');
    execSync(`node "${join(__dirname, 'utils/summary-updater.js')}"`, { stdio, cwd: targetDir });

  // Step 7: Setup Claude Code integration
  log('\n[7/7] Setting up Claude Code integration...');
    setupClaudeIntegration({ cwd: targetDir });

  log('\n✅ Initial analysis complete!');
  log('\nNext steps:');
  log('  - Run "node query.js stats" to see statistics');
  log('  - Edit files and run "node analyze.js" again for incremental updates');

} else {
  log('🔍 Existing analysis found - checking for changes...\n');

    const targetDir = process.argv[2] && !process.argv[2].startsWith('-') ? process.argv[2] : '.';
    const changeReport = detectChanges(targetDir);

  const changedFiles = [...changeReport.added, ...changeReport.modified];

  if (changedFiles.length === 0) {
    log('\n✅ All files up to date - no analysis needed!');
    process.exit(0);
  }

  log(`\n📝 Detected ${changedFiles.length} changed files - running incremental analysis...\n`);

  // Run incremental analyzer
  const startTime = Date.now();
    execSync(`node "${join(__dirname, 'core/incremental-analyzer.js')}"`, { stdio });

  // Update summaries
  log('');
    execSync(`node "${join(__dirname, 'utils/summary-updater.js')}" ${changedFiles.join(' ')}`, { stdio });

  // Ensure Claude Code integration exists
    setupClaudeIntegration({ cwd: targetDir });

  const totalTime = Date.now() - startTime;

  log(`\n✅ Incremental analysis complete in ${totalTime}ms!`);
  log(`\nEfficiency:`);
  log(`  - Files analyzed: ${changedFiles.length}`);
  log(`  - Files skipped: ${changeReport.unchanged.length}`);
  log(`  - Time saved: ~${(changeReport.unchanged.length * 28).toFixed(0)}ms`);
  }
})().catch(error => {
  console.error('\n❌ Fatal error:', error.message);
  console.error(error.stack);
  process.exit(1);
});
