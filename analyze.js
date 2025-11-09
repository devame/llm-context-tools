#!/usr/bin/env node
/**
 * Main Analysis Script - Unified workflow for code analysis
 *
 * Usage:
 *   node analyze.js          - Initial full analysis
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
import { detectChanges } from './change-detector.js';
import { updateSummaries } from './summary-updater.js';

console.log('=== LLM Context Analyzer ===\n');

const manifestExists = existsSync('.llm-context/manifest.json');
const graphExists = existsSync('.llm-context/graph.jsonl');

if (!manifestExists || !graphExists) {
  console.log('🔍 No previous analysis found - running initial full analysis...\n');

  // Step 1: Create .llm-context directory
  console.log('[1/5] Setting up analysis directory...');
  execSync('mkdir -p .llm-context', { stdio: 'inherit' });

  // Step 2: Run SCIP indexer (if needed for typed languages)
  console.log('\n[2/5] Running SCIP indexer...');
  try {
    execSync('npx scip-typescript index --infer-tsconfig --output .llm-context/index.scip 2>/dev/null || echo "SCIP indexing skipped"', { stdio: 'inherit' });
  } catch (error) {
    console.log('  ⚠ SCIP indexing failed (continuing with custom analysis only)');
  }

  // Step 3: Parse SCIP output (if available)
  console.log('\n[3/5] Parsing SCIP data...');
  if (existsSync('.llm-context/index.scip')) {
    try {
      execSync('cp scip.proto .llm-context/ && node scip-parser.js', { stdio: 'inherit' });
    } catch (error) {
      console.log('  ⚠ SCIP parsing failed');
    }
  } else {
    console.log('  ⚠ No SCIP data to parse');
  }

  // Step 4: Run full transformer
  console.log('\n[4/5] Running full analysis...');
  execSync('node transformer.js', { stdio: 'inherit' });

  // Step 5: Generate initial manifest
  console.log('\n[5/5] Generating manifest...');
  execSync('node manifest-generator.js', { stdio: 'inherit' });

  // Step 6: Generate summaries
  console.log('\n[6/6] Generating summaries...');
  execSync('node summary-updater.js', { stdio: 'inherit' });

  console.log('\n✅ Initial analysis complete!');
  console.log('\nNext steps:');
  console.log('  - Run "node query.js stats" to see statistics');
  console.log('  - Edit files and run "node analyze.js" again for incremental updates');

} else {
  console.log('🔍 Existing analysis found - checking for changes...\n');

  const changeReport = detectChanges();

  const changedFiles = [...changeReport.added, ...changeReport.modified];

  if (changedFiles.length === 0) {
    console.log('\n✅ All files up to date - no analysis needed!');
    process.exit(0);
  }

  console.log(`\n📝 Detected ${changedFiles.length} changed files - running incremental analysis...\n`);

  // Run incremental analyzer
  const startTime = Date.now();
  execSync('node incremental-analyzer.js', { stdio: 'inherit' });

  // Update summaries
  console.log('');
  execSync(`node summary-updater.js ${changedFiles.join(' ')}`, { stdio: 'inherit' });

  const totalTime = Date.now() - startTime;

  console.log(`\n✅ Incremental analysis complete in ${totalTime}ms!`);
  console.log(`\nEfficiency:`);
  console.log(`  - Files analyzed: ${changedFiles.length}`);
  console.log(`  - Files skipped: ${changeReport.unchanged.length}`);
  console.log(`  - Time saved: ~${(changeReport.unchanged.length * 28).toFixed(0)}ms`);
}
