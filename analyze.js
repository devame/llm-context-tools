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

import { existsSync } from 'fs';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { detectChanges } from './change-detector.js';
import { updateSummaries } from './summary-updater.js';
import { setupClaudeIntegration } from './claude-setup.js';

// Get package directory
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Check for --quiet flag
const isQuiet = process.argv.includes('--quiet');
const stdio = isQuiet ? 'ignore' : 'inherit';

// Logging helper
function log(...args) {
  if (!isQuiet) {
    log(...args);
  }
}

log('=== LLM Context Analyzer ===\n');

const manifestExists = existsSync('.llm-context/manifest.json');
const graphExists = existsSync('.llm-context/graph.jsonl');

if (!manifestExists || !graphExists) {
  log('🔍 No previous analysis found - running initial full analysis...\n');

  // Step 1: Create .llm-context directory
  log('[1/5] Setting up analysis directory...');
  execSync('mkdir -p .llm-context', { stdio });

  // Step 2: Run SCIP indexer (if needed for typed languages)
  log('\n[2/5] Running SCIP indexer...');
  try {
    execSync('npx scip-typescript index --infer-tsconfig --output .llm-context/index.scip 2>/dev/null || echo "SCIP indexing skipped"', { stdio });
  } catch (error) {
    log('  ⚠ SCIP indexing failed (continuing with custom analysis only)');
  }

  // Step 3: Parse SCIP output (if available)
  log('\n[3/5] Parsing SCIP data...');
  if (existsSync('.llm-context/index.scip')) {
    try {
      execSync(`cp "${join(__dirname, 'scip.proto')}" .llm-context/ && node "${join(__dirname, 'scip-parser.js')}"`, { stdio });
    } catch (error) {
      log('  ⚠ SCIP parsing failed');
    }
  } else {
    log('  ⚠ No SCIP data to parse');
  }

  // Step 4: Run full analysis (Tree-sitter based)
  log('\n[4/6] Running full analysis...');
  execSync(`node "${join(__dirname, 'full-analysis.js')}"`, { stdio });

  // Step 5: Generate initial manifest
  log('\n[5/6] Generating manifest...');
  execSync(`node "${join(__dirname, 'manifest-generator.js')}"`, { stdio });

  // Step 6: Generate summaries
  log('\n[6/7] Generating summaries...');
  execSync(`node "${join(__dirname, 'summary-updater.js')}"`, { stdio });

  // Step 7: Setup Claude Code integration
  log('\n[7/7] Setting up Claude Code integration...');
  setupClaudeIntegration();

  log('\n✅ Initial analysis complete!');
  log('\nNext steps:');
  log('  - Run "node query.js stats" to see statistics');
  log('  - Edit files and run "node analyze.js" again for incremental updates');

} else {
  log('🔍 Existing analysis found - checking for changes...\n');

  const changeReport = detectChanges();

  const changedFiles = [...changeReport.added, ...changeReport.modified];

  if (changedFiles.length === 0) {
    log('\n✅ All files up to date - no analysis needed!');
    process.exit(0);
  }

  log(`\n📝 Detected ${changedFiles.length} changed files - running incremental analysis...\n`);

  // Run incremental analyzer
  const startTime = Date.now();
  execSync(`node "${join(__dirname, 'incremental-analyzer.js')}"`, { stdio });

  // Update summaries
  log('');
  execSync(`node "${join(__dirname, 'summary-updater.js')}" ${changedFiles.join(' ')}`, { stdio });

  // Ensure Claude Code integration exists
  setupClaudeIntegration();

  const totalTime = Date.now() - startTime;

  log(`\n✅ Incremental analysis complete in ${totalTime}ms!`);
  log(`\nEfficiency:`);
  log(`  - Files analyzed: ${changedFiles.length}`);
  log(`  - Files skipped: ${changeReport.unchanged.length}`);
  log(`  - Time saved: ~${(changeReport.unchanged.length * 28).toFixed(0)}ms`);
}
