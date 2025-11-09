#!/usr/bin/env node
/**
 * Function Change Detector - Detect changes at function level
 *
 * Purpose: Compare function-level hashes to identify which specific
 * functions have changed, been added, or deleted within files.
 */

import { readFileSync, existsSync } from 'fs';
import { parse } from '@babel/parser';
import traverse from '@babel/traverse';
import { extractFunctionMetadata } from './function-source-extractor.js';

/**
 * Load configuration file
 * @returns {object} Configuration
 */
function loadConfig() {
  const configPath = './llm-context.config.json';

  if (!existsSync(configPath)) {
    return { granularity: 'file' };
  }

  return JSON.parse(readFileSync(configPath, 'utf-8'));
}

/**
 * Extract current function metadata from a file
 * @param {string} filePath - Path to file
 * @returns {Map} Map of function name to metadata
 */
export function extractCurrentFunctions(filePath) {
  const functionMap = new Map();

  try {
    const source = readFileSync(filePath, 'utf-8');

    // Parse with Babel
    const ast = parse(source, {
      sourceType: 'module',
      plugins: []
    });

    // Collect all functions
    traverse.default(ast, {
      FunctionDeclaration(path) {
        const metadata = extractFunctionMetadata(path, source, filePath);
        functionMap.set(metadata.name, metadata);
      },

      VariableDeclarator(path) {
        if (path.node.init?.type === 'ArrowFunctionExpression' ||
            path.node.init?.type === 'FunctionExpression') {
          const metadata = extractFunctionMetadata(path, source, filePath);
          functionMap.set(metadata.name, metadata);
        }
      }
    });

  } catch (error) {
    console.log(`    Warning: Could not parse ${filePath}: ${error.message}`);
  }

  return functionMap;
}

/**
 * Detect function-level changes in a file
 * @param {string} filePath - Path to file
 * @param {object} manifest - Manifest data
 * @returns {object} Change report with added, modified, deleted, unchanged functions
 */
export async function detectFunctionChanges(filePath, manifest) {
  const changes = {
    filePath,
    added: [],
    modified: [],
    deleted: [],
    unchanged: [],
    renames: []
  };

  // Get current functions
  const currentFunctions = extractCurrentFunctions(filePath);

  // Get manifest functions
  const fileEntry = manifest.files[filePath];
  if (!fileEntry || !fileEntry.functionHashes) {
    // No previous function data - all current functions are "added"
    for (const [name, metadata] of currentFunctions) {
      changes.added.push({
        name,
        hash: metadata.hash,
        line: metadata.line,
        size: metadata.size
      });
    }
    return changes;
  }

  const manifestFunctions = fileEntry.functionHashes;

  // Compare current vs manifest
  for (const [name, metadata] of currentFunctions) {
    const manifestFunc = manifestFunctions[name];

    if (!manifestFunc) {
      // New function
      changes.added.push({
        name,
        hash: metadata.hash,
        line: metadata.line,
        size: metadata.size
      });
    } else if (manifestFunc.hash !== metadata.hash) {
      // Modified function
      changes.modified.push({
        name,
        oldHash: manifestFunc.hash,
        newHash: metadata.hash,
        oldLine: manifestFunc.line,
        newLine: metadata.line,
        sizeDelta: metadata.size - manifestFunc.size
      });
    } else {
      // Unchanged function
      changes.unchanged.push(name);
    }
  }

  // Find deleted functions
  for (const name in manifestFunctions) {
    if (!currentFunctions.has(name)) {
      changes.deleted.push({
        name,
        hash: manifestFunctions[name].hash,
        line: manifestFunctions[name].line
      });
    }
  }

  // Detect potential renames (deleted + added with similar code)
  if (changes.deleted.length > 0 && changes.added.length > 0) {
    const config = loadConfig();
    const detectRenames = config.incremental?.detectRenames || false;

    if (detectRenames && fileEntry.functionHashes) {
      // Check if we have source stored for similarity comparison
      const hasStoredSource = Object.values(fileEntry.functionHashes).some(f => f.source);

      if (hasStoredSource) {
        const { computeSimilarity } = await import('./function-source-extractor.js');
        const threshold = config.incremental?.similarityThreshold || 0.85;

        for (const deletedFunc of changes.deleted) {
          const oldSource = manifestFunctions[deletedFunc.name]?.source;

          if (!oldSource) continue;

          // Compare with each added function
          for (const addedFunc of changes.added) {
            const newMetadata = currentFunctions.get(addedFunc.name);
            if (!newMetadata || !newMetadata.source) continue;

            const similarity = computeSimilarity(oldSource, newMetadata.source);

            if (similarity >= threshold) {
              changes.renames.push({
                from: deletedFunc.name,
                to: addedFunc.name,
                similarity: similarity.toFixed(3),
                oldLine: deletedFunc.line,
                newLine: addedFunc.line
              });

              // Remove from deleted and added since it's a rename
              changes.deleted = changes.deleted.filter(f => f.name !== deletedFunc.name);
              changes.added = changes.added.filter(f => f.name !== addedFunc.name);
              break;
            }
          }
        }
      }
    }
  }

  return changes;
}

/**
 * Detect all function-level changes across multiple files
 * @param {string[]} changedFiles - List of changed file paths
 * @param {object} manifest - Manifest data
 * @returns {Map} Map of file path to change report
 */
export async function detectAllFunctionChanges(changedFiles, manifest) {
  const allChanges = new Map();

  for (const filePath of changedFiles) {
    const changes = await detectFunctionChanges(filePath, manifest);

    // Only include files with actual function changes
    if (changes.added.length > 0 ||
        changes.modified.length > 0 ||
        changes.deleted.length > 0 ||
        (changes.renames && changes.renames.length > 0)) {
      allChanges.set(filePath, changes);
    }
  }

  return allChanges;
}

/**
 * Print function change summary
 * @param {Map} functionChanges - Map of file to changes
 */
export function printFunctionChangeSummary(functionChanges) {
  let totalAdded = 0;
  let totalModified = 0;
  let totalDeleted = 0;
  let totalRenamed = 0;
  let totalUnchanged = 0;

  console.log('\n=== Function-Level Changes ===\n');

  for (const [filePath, changes] of functionChanges) {
    console.log(`${filePath}:`);

    if (changes.renames && changes.renames.length > 0) {
      console.log(`  Renamed (${changes.renames.length}):`);
      for (const rename of changes.renames) {
        console.log(`    ≈ ${rename.from} → ${rename.to} (${(rename.similarity * 100).toFixed(1)}% similar, line ${rename.oldLine}→${rename.newLine})`);
      }
      totalRenamed += changes.renames.length;
    }

    if (changes.added.length > 0) {
      console.log(`  Added (${changes.added.length}):`);
      for (const func of changes.added) {
        console.log(`    + ${func.name} (line ${func.line}, ${func.size} bytes)`);
      }
      totalAdded += changes.added.length;
    }

    if (changes.modified.length > 0) {
      console.log(`  Modified (${changes.modified.length}):`);
      for (const func of changes.modified) {
        const delta = func.sizeDelta >= 0 ? `+${func.sizeDelta}` : `${func.sizeDelta}`;
        console.log(`    ~ ${func.name} (line ${func.oldLine}→${func.newLine}, ${delta} bytes)`);
      }
      totalModified += changes.modified.length;
    }

    if (changes.deleted.length > 0) {
      console.log(`  Deleted (${changes.deleted.length}):`);
      for (const func of changes.deleted) {
        console.log(`    - ${func.name} (was line ${func.line})`);
      }
      totalDeleted += changes.deleted.length;
    }

    if (changes.unchanged.length > 0) {
      console.log(`  Unchanged: ${changes.unchanged.length} functions`);
      totalUnchanged += changes.unchanged.length;
    }

    console.log('');
  }

  console.log('=== Summary ===');
  if (totalRenamed > 0) {
    console.log(`Total functions renamed: ${totalRenamed}`);
  }
  console.log(`Total functions added: ${totalAdded}`);
  console.log(`Total functions modified: ${totalModified}`);
  console.log(`Total functions deleted: ${totalDeleted}`);
  console.log(`Total functions unchanged: ${totalUnchanged}`);

  const totalChanged = totalAdded + totalModified + totalDeleted + totalRenamed;
  const totalFunctions = totalChanged + totalUnchanged;
  const percentUnchanged = totalFunctions > 0
    ? ((totalUnchanged / totalFunctions) * 100).toFixed(1)
    : 0;

  console.log(`\n✓ Efficiency: ${percentUnchanged}% of functions skipped!`);
}
