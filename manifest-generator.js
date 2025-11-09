#!/usr/bin/env node
/**
 * Manifest Generator - Tracks file hashes for incremental updates
 *
 * Purpose: Create and update a manifest.json file that tracks:
 * - File content hashes (for change detection)
 * - File metadata (size, last modified)
 * - Analysis results (functions found, analysis time)
 * - Global statistics
 */

import { readFileSync, writeFileSync, existsSync, statSync, readdirSync } from 'fs';
import { createHash } from 'crypto';
import { join, relative } from 'path';

console.log('=== Manifest Generator ===\n');

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
 * Get file metadata
 * @param {string} filePath - Path to file
 * @returns {object} File stats
 */
function getFileMetadata(filePath) {
  const stats = statSync(filePath);
  return {
    size: stats.size,
    lastModified: stats.mtime.toISOString()
  };
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

      // Skip ignored directories
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
 * Load existing graph to extract function metadata
 * @returns {Map} Map of file -> functions
 */
function loadGraphData() {
  const graphPath = '.llm-context/graph.jsonl';
  const fileToFunctions = new Map();

  if (!existsSync(graphPath)) {
    console.log('No existing graph.jsonl found - this will be the initial analysis');
    return fileToFunctions;
  }

  const lines = readFileSync(graphPath, 'utf-8').split('\n').filter(Boolean);

  for (const line of lines) {
    const func = JSON.parse(line);
    const file = func.file;

    if (!fileToFunctions.has(file)) {
      fileToFunctions.set(file, []);
    }

    fileToFunctions.get(file).push(func.id || func.name);
  }

  return fileToFunctions;
}

/**
 * Generate manifest from current codebase state
 * @returns {object} Manifest data
 */
function generateManifest() {
  console.log('[1] Discovering JavaScript files...');
  const jsFiles = findJsFiles();
  console.log(`    Found ${jsFiles.length} JavaScript files\n`);

  console.log('[2] Computing file hashes...');
  const fileToFunctions = loadGraphData();
  const files = {};
  let totalSize = 0;

  for (const filePath of jsFiles) {
    try {
      const hash = computeFileHash(filePath);
      const metadata = getFileMetadata(filePath);
      const functions = fileToFunctions.get(filePath) || [];

      files[filePath] = {
        hash,
        size: metadata.size,
        lastModified: metadata.lastModified,
        functions,
        analysisTime: null // Will be filled during analysis
      };

      totalSize += metadata.size;

      console.log(`    ${filePath}`);
      console.log(`      Hash: ${hash.substring(0, 12)}...`);
      console.log(`      Functions: ${functions.length > 0 ? functions.join(', ') : 'none yet'}`);

    } catch (error) {
      console.log(`    Warning: Could not process ${filePath}: ${error.message}`);
    }
  }

  console.log(`\n[3] Building manifest...`);

  // Load global stats from graph if available
  let globalStats = {
    totalFunctions: 0,
    totalCalls: 0,
    totalFiles: jsFiles.length,
    totalSize
  };

  if (existsSync('.llm-context/graph.jsonl')) {
    const lines = readFileSync('.llm-context/graph.jsonl', 'utf-8').split('\n').filter(Boolean);
    const functions = lines.map(line => JSON.parse(line));

    globalStats.totalFunctions = functions.length;
    globalStats.totalCalls = functions.reduce((sum, f) => sum + (f.calls?.length || 0), 0);
  }

  const manifest = {
    version: '1.0.0',
    generated: new Date().toISOString(),
    files,
    globalStats
  };

  return manifest;
}

/**
 * Save manifest to disk
 * @param {object} manifest - Manifest data
 */
function saveManifest(manifest) {
  const manifestPath = '.llm-context/manifest.json';
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  console.log(`\n✓ Manifest saved to ${manifestPath}`);

  // Print summary
  console.log('\n=== Manifest Summary ===');
  console.log(`Files tracked: ${Object.keys(manifest.files).length}`);
  console.log(`Total size: ${(manifest.globalStats.totalSize / 1024).toFixed(1)} KB`);
  console.log(`Functions: ${manifest.globalStats.totalFunctions}`);
  console.log(`Call relationships: ${manifest.globalStats.totalCalls}`);
  console.log(`Generated: ${manifest.generated}`);
}

/**
 * Main execution
 */
function main() {
  const manifest = generateManifest();
  saveManifest(manifest);
}

main();
