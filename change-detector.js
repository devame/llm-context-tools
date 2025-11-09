#!/usr/bin/env node
/**
 * Change Detector - Identifies files that need re-analysis
 *
 * Purpose: Compare current file hashes against manifest to determine:
 * - Which files have changed (modified)
 * - Which files are new (added)
 * - Which files have been removed (deleted)
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'fs';
import { createHash } from 'crypto';
import { join, relative } from 'path';

console.log('=== Change Detector ===\n');

/**
 * Compute MD5 hash of file content
 * @param {string} filePath - Path to file
 * @returns {string} MD5 hash
 */
function computeFileHash(filePath) {
  const content = readFileSync(filePath);
  return createHash('md5').update(content).digest('hex');
}

/**
 * Recursively find all JS files
 * @param {string} dir - Directory to search
 * @param {string[]} ignore - Patterns to ignore
 * @returns {string[]} List of JS files
 */
function findJsFiles(dir = '.', ignore = ['node_modules', '.git', '.llm-context']) {
  const files = [];

  function walk(currentDir) {
    const entries = readdirSync(currentDir, { withFileTypes: true });

    for (const entry of entries) {
      const fullPath = join(currentDir, entry.name);
      const relativePath = relative('.', fullPath);

      if (ignore.some(pattern => relativePath.includes(pattern))) {
        continue;
      }

      if (entry.isDirectory()) {
        walk(fullPath);
      } else if (entry.isFile() && entry.name.endsWith('.js')) {
        files.push(relativePath);
      }
    }
  }

  walk(dir);
  return files;
}

/**
 * Load existing manifest
 * @returns {object|null} Manifest data or null if not found
 */
function loadManifest() {
  const manifestPath = '.llm-context/manifest.json';

  if (!existsSync(manifestPath)) {
    console.log('⚠ No manifest.json found - run manifest-generator.js first');
    return null;
  }

  return JSON.parse(readFileSync(manifestPath, 'utf-8'));
}

/**
 * Detect changes between current state and manifest
 * @returns {object} Change report
 */
function detectChanges() {
  console.log('[1] Loading manifest...');
  const manifest = loadManifest();

  if (!manifest) {
    return {
      added: [],
      modified: [],
      deleted: [],
      unchanged: [],
      needsFullAnalysis: true
    };
  }

  console.log(`    Last analysis: ${manifest.generated}`);
  console.log(`    Files tracked: ${Object.keys(manifest.files).length}\n`);

  console.log('[2] Discovering current files...');
  const currentFiles = findJsFiles();
  console.log(`    Found ${currentFiles.length} JavaScript files\n`);

  console.log('[3] Computing changes...');

  const manifestFiles = new Set(Object.keys(manifest.files));
  const currentFilesSet = new Set(currentFiles);

  const added = [];
  const modified = [];
  const deleted = [];
  const unchanged = [];

  // Check for new and modified files
  for (const filePath of currentFiles) {
    if (!manifestFiles.has(filePath)) {
      // New file
      added.push(filePath);
      console.log(`    + ${filePath} (NEW)`);
    } else {
      // Existing file - check hash
      const currentHash = computeFileHash(filePath);
      const manifestHash = manifest.files[filePath].hash;

      if (currentHash !== manifestHash) {
        modified.push(filePath);
        console.log(`    M ${filePath} (MODIFIED)`);
        console.log(`      Old: ${manifestHash.substring(0, 12)}...`);
        console.log(`      New: ${currentHash.substring(0, 12)}...`);
      } else {
        unchanged.push(filePath);
      }
    }
  }

  // Check for deleted files
  for (const filePath of manifestFiles) {
    if (!currentFilesSet.has(filePath)) {
      deleted.push(filePath);
      console.log(`    - ${filePath} (DELETED)`);
    }
  }

  if (added.length === 0 && modified.length === 0 && deleted.length === 0) {
    console.log('    ✓ No changes detected');
  }

  return {
    added,
    modified,
    deleted,
    unchanged,
    needsFullAnalysis: false,
    manifest
  };
}

/**
 * Print change report summary
 * @param {object} report - Change report
 */
function printSummary(report) {
  console.log('\n=== Change Summary ===');

  if (report.needsFullAnalysis) {
    console.log('Status: Full analysis needed (no existing manifest)');
    return;
  }

  const total = report.added.length + report.modified.length + report.deleted.length;

  console.log(`Total files: ${report.added.length + report.modified.length + report.unchanged.length}`);
  console.log(`Changes detected: ${total}`);
  console.log(`  Added: ${report.added.length}`);
  console.log(`  Modified: ${report.modified.length}`);
  console.log(`  Deleted: ${report.deleted.length}`);
  console.log(`  Unchanged: ${report.unchanged.length}`);

  if (total === 0) {
    console.log('\n✓ All files up to date - no re-analysis needed!');
  } else {
    const percentChanged = ((total / (total + report.unchanged.length)) * 100).toFixed(1);
    console.log(`\nRe-analysis needed for ${total} files (${percentChanged}% of codebase)`);

    // Estimate time savings
    const unchangedCount = report.unchanged.length;
    if (unchangedCount > 0) {
      console.log(`\nEstimated savings:`);
      console.log(`  Files skipped: ${unchangedCount}`);
      console.log(`  Approximate time saved: ${(unchangedCount * 0.5).toFixed(1)}s`);
      console.log(`  (Assuming ~500ms per file)`);
    }
  }
}

/**
 * Main execution
 */
function main() {
  const report = detectChanges();
  printSummary(report);
  return report;
}

// Run if called directly
if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}

// Export for use in other modules
export { detectChanges, loadManifest };
