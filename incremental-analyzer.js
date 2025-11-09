#!/usr/bin/env node
/**
 * Incremental Analyzer - Re-analyze only changed files/functions
 *
 * Purpose: Perform selective re-analysis of changed files/functions and update:
 * - graph.jsonl (remove old entries, add new ones)
 * - manifest.json (update hashes and metadata)
 * - summaries (only affected modules)
 *
 * Supports both file-level and function-level granularity.
 */

import { readFileSync, writeFileSync, existsSync, statSync } from 'fs';
import { createHash } from 'crypto';
import { parse } from '@babel/parser';
import traverse from '@babel/traverse';
import { detectChanges } from './change-detector.js';
import { detectAllFunctionChanges, printFunctionChangeSummary } from './function-change-detector.js';
import { extractFunctionMetadata } from './function-source-extractor.js';

console.log('=== Incremental Analyzer ===\n');

/**
 * Load configuration file
 */
function loadConfig() {
  const configPath = './llm-context.config.json';

  if (!existsSync(configPath)) {
    return { granularity: 'file' };
  }

  return JSON.parse(readFileSync(configPath, 'utf-8'));
}

/**
 * Compute MD5 hash of file content
 */
function computeFileHash(filePath) {
  const content = readFileSync(filePath);
  return createHash('md5').update(content).digest('hex');
}

/**
 * Get file metadata
 */
function getFileMetadata(filePath) {
  const stats = statSync(filePath);
  return {
    size: stats.size,
    lastModified: stats.mtime.toISOString()
  };
}

/**
 * Analyze specific functions in a file
 * @param {string} sourcePath - Path to file
 * @param {string[]} targetFunctions - Function names to analyze (null = all)
 * @returns {object} Analysis results
 */
function analyzeSpecificFunctions(sourcePath, targetFunctions = null) {
  const startTime = Date.now();
  const source = readFileSync(sourcePath, 'utf-8');

  // Parse with Babel
  const ast = parse(source, {
    sourceType: 'module',
    plugins: []
  });

  const allFunctions = [];
  const callGraph = new Map();
  const sideEffects = new Map();

  // First pass: collect all functions
  traverse.default(ast, {
    FunctionDeclaration(path) {
      const metadata = extractFunctionMetadata(path, source, sourcePath);
      allFunctions.push({ metadata, path });
    },

    VariableDeclarator(path) {
      if (path.node.init?.type === 'ArrowFunctionExpression' ||
          path.node.init?.type === 'FunctionExpression') {
        const metadata = extractFunctionMetadata(path, source, sourcePath);
        allFunctions.push({ metadata, path });
      }
    }
  });

  // Filter to target functions if specified
  const functionsToAnalyze = targetFunctions
    ? allFunctions.filter(f => targetFunctions.includes(f.metadata.name))
    : allFunctions;

  // Second pass: analyze function bodies
  const results = [];

  for (const { metadata, path } of functionsToAnalyze) {
    const funcId = metadata.id;
    callGraph.set(funcId, []);
    sideEffects.set(funcId, []);

    // Analyze calls and side effects
    path.traverse({
      CallExpression(callPath) {
        const callee = callPath.node.callee;
        let calledName = '';

        if (callee.type === 'Identifier') {
          calledName = callee.name;
        } else if (callee.type === 'MemberExpression') {
          const obj = callee.object.name || '';
          const prop = callee.property.name || '';
          calledName = obj ? `${obj}.${prop}` : prop;
        }

        if (calledName) {
          const calls = callGraph.get(funcId) || [];
          calls.push(calledName);
          callGraph.set(funcId, calls);

          // Detect side effects
          const effects = sideEffects.get(funcId) || [];

          if (/read|write|append|unlink|mkdir|rmdir|fs\./i.test(calledName)) {
            effects.push({ type: 'file_io', at: calledName });
          }
          if (/fetch|request|axios|http|socket/i.test(calledName)) {
            effects.push({ type: 'network', at: calledName });
          }
          if (/console\.|log\.|logger\.|debug|info|warn|error/i.test(calledName)) {
            effects.push({ type: 'logging', at: calledName });
          }
          if (/query|execute|find|findOne|save|insert|update|delete|collection|db\./i.test(calledName)) {
            effects.push({ type: 'database', at: calledName });
          }
          if (/querySelector|getElementById|createElement|appendChild|innerHTML|textContent/i.test(calledName)) {
            effects.push({ type: 'dom', at: calledName });
          }

          sideEffects.set(funcId, effects);
        }
      }
    });

    // Build entry
    const calls = callGraph.get(funcId) || [];
    const effects = sideEffects.get(funcId) || [];
    const uniqueCalls = [...new Set(calls)].filter(c => c !== metadata.name);
    const uniqueEffects = effects.reduce((acc, e) => {
      const key = `${e.type}:${e.at}`;
      if (!acc.has(key)) {
        acc.set(key, e);
      }
      return acc;
    }, new Map());

    results.push({
      id: metadata.name,
      type: 'function',
      file: sourcePath,
      line: metadata.line,
      sig: `(${metadata.isAsync ? 'async ' : ''})`,
      async: metadata.isAsync,
      calls: uniqueCalls.slice(0, 10),
      effects: Array.from(uniqueEffects.values()).map(e => e.type),
      scipDoc: '',
      functionHash: metadata.hash  // Include for reference
    });
  }

  return {
    entries: results,
    analysisTime: Date.now() - startTime,
    totalFunctions: allFunctions.length,
    analyzedFunctions: results.length
  };
}

/**
 * Analyze a single file using Babel AST parsing
 * @param {string} sourcePath - Path to file
 * @returns {Array} Array of function entries for graph
 */
function analyzeSingleFile(sourcePath) {
  const startTime = Date.now();
  const functions = [];

  try {
    const source = readFileSync(sourcePath, 'utf-8');

    // Parse with Babel
    const ast = parse(source, {
      sourceType: 'module',
      plugins: []
    });

    const callGraph = new Map();
    const sideEffects = new Map();

    // First pass: collect all functions
    traverse.default(ast, {
      FunctionDeclaration(path) {
        const funcName = path.node.id?.name || 'anonymous';
        const funcId = `${sourcePath}#${funcName}`;

        functions.push({
          id: funcId,
          name: funcName,
          type: 'function',
          file: sourcePath,
          line: path.node.loc?.start.line || 0,
          params: path.node.params.map(p => p.name || '?').join(', '),
          async: path.node.async,
          path: path
        });

        callGraph.set(funcId, []);
        sideEffects.set(funcId, []);
      },

      VariableDeclarator(path) {
        if (path.node.init?.type === 'ArrowFunctionExpression' ||
            path.node.init?.type === 'FunctionExpression') {
          const funcName = path.node.id?.name || 'anonymous';
          const funcId = `${sourcePath}#${funcName}`;

          functions.push({
            id: funcId,
            name: funcName,
            type: 'function',
            file: sourcePath,
            line: path.node.loc?.start.line || 0,
            params: path.node.init.params.map(p => p.name || '?').join(', '),
            async: path.node.init.async,
            path: path
          });

          callGraph.set(funcId, []);
          sideEffects.set(funcId, []);
        }
      }
    });

    // Second pass: analyze each function's body
    functions.forEach(func => {
      if (!func.path) return;

      const funcId = func.id;

      func.path.traverse({
        CallExpression(path) {
          const callee = path.node.callee;
          let calledName = '';

          if (callee.type === 'Identifier') {
            calledName = callee.name;
          } else if (callee.type === 'MemberExpression') {
            const obj = callee.object.name || '';
            const prop = callee.property.name || '';
            calledName = obj ? `${obj}.${prop}` : prop;
          }

          if (calledName) {
            const calls = callGraph.get(funcId) || [];
            calls.push(calledName);
            callGraph.set(funcId, calls);

            // Detect side effects
            const effects = sideEffects.get(funcId) || [];

            if (/read|write|append|unlink|mkdir|rmdir|fs\./i.test(calledName)) {
              effects.push({ type: 'file_io', at: calledName });
            }
            if (/fetch|request|axios|http|socket/i.test(calledName)) {
              effects.push({ type: 'network', at: calledName });
            }
            if (/console\.|log\.|logger\.|debug|info|warn|error/i.test(calledName)) {
              effects.push({ type: 'logging', at: calledName });
            }
            if (/query|execute|find|findOne|save|insert|update|delete|collection|db\./i.test(calledName)) {
              effects.push({ type: 'database', at: calledName });
            }
            if (/querySelector|getElementById|createElement|appendChild|innerHTML|textContent/i.test(calledName)) {
              effects.push({ type: 'dom', at: calledName });
            }

            sideEffects.set(funcId, effects);
          }
        }
      });

      // Clean up
      delete func.path;
    });

    // Build output
    const result = functions.map(func => {
      const calls = callGraph.get(func.id) || [];
      const effects = sideEffects.get(func.id) || [];

      const uniqueCalls = [...new Set(calls)].filter(c => c !== func.name);
      const uniqueEffects = effects.reduce((acc, e) => {
        const key = `${e.type}:${e.at}`;
        if (!acc.has(key)) {
          acc.set(key, e);
        }
        return acc;
      }, new Map());

      return {
        id: func.name,
        type: func.type,
        file: func.file,
        line: func.line,
        sig: `(${func.params})`,
        async: func.async || false,
        calls: uniqueCalls.slice(0, 10),
        effects: Array.from(uniqueEffects.values()).map(e => e.type),
        scipDoc: ''
      };
    });

    const analysisTime = Date.now() - startTime;
    console.log(`      Analysis complete: ${functions.length} functions, ${analysisTime}ms`);

    return { entries: result, analysisTime };

  } catch (error) {
    console.log(`      Warning: Could not parse ${sourcePath}: ${error.message}`);
    return { entries: [], analysisTime: Date.now() - startTime };
  }
}

/**
 * Load existing graph entries
 */
function loadGraph() {
  const graphPath = '.llm-context/graph.jsonl';

  if (!existsSync(graphPath)) {
    return [];
  }

  const lines = readFileSync(graphPath, 'utf-8').split('\n').filter(Boolean);
  return lines.map(line => JSON.parse(line));
}

/**
 * Update graph with function-level granularity
 */
function updateGraphFunctionLevel(functionChanges, newEntries) {
  console.log('\n[4] Updating graph.jsonl (function-level)...');

  const existingEntries = loadGraph();
  console.log(`    Current entries: ${existingEntries.length}`);

  // Build a set of (file, function) pairs to remove
  const toRemove = new Set();

  for (const [filePath, changes] of functionChanges) {
    // Remove modified and deleted functions
    for (const func of [...changes.modified, ...changes.deleted]) {
      toRemove.add(`${filePath}#${func.name}`);
    }
  }

  // Keep entries that aren't in the remove set
  const keptEntries = existingEntries.filter(entry => {
    const key = `${entry.file}#${entry.id}`;
    return !toRemove.has(key);
  });

  console.log(`    Entries kept (unchanged functions): ${keptEntries.length}`);
  console.log(`    Entries removed (changed/deleted functions): ${existingEntries.length - keptEntries.length}`);

  // Add new entries
  const updatedGraph = [...keptEntries, ...newEntries];
  console.log(`    New entries added: ${newEntries.length}`);
  console.log(`    Total entries: ${updatedGraph.length}`);

  // Write updated graph
  const jsonlContent = updatedGraph.map(node => JSON.stringify(node)).join('\n');
  writeFileSync('.llm-context/graph.jsonl', jsonlContent);

  console.log('    ✓ Graph updated (function-level)');

  return updatedGraph;
}

/**
 * Update graph by removing old entries for changed files and adding new ones (file-level)
 */
function updateGraph(changedFiles, newEntries) {
  console.log('\n[4] Updating graph.jsonl (file-level)...');

  // Load existing graph
  const existingEntries = loadGraph();
  console.log(`    Current entries: ${existingEntries.length}`);

  // Create set of changed files for fast lookup
  const changedSet = new Set(changedFiles);

  // Keep only entries from unchanged files
  const keptEntries = existingEntries.filter(entry => !changedSet.has(entry.file));
  console.log(`    Entries kept (unchanged files): ${keptEntries.length}`);
  console.log(`    Entries removed (changed files): ${existingEntries.length - keptEntries.length}`);

  // Add all new entries
  const updatedGraph = [...keptEntries, ...newEntries];
  console.log(`    New entries added: ${newEntries.length}`);
  console.log(`    Total entries: ${updatedGraph.length}`);

  // Write updated graph
  const jsonlContent = updatedGraph.map(node => JSON.stringify(node)).join('\n');
  writeFileSync('.llm-context/graph.jsonl', jsonlContent);

  console.log('    ✓ Graph updated (file-level)');

  return updatedGraph;
}

/**
 * Update manifest with new hashes and metadata
 */
function updateManifest(changeReport, analysisResults) {
  console.log('\n[5] Updating manifest.json...');

  const manifest = changeReport.manifest;

  // Remove deleted files
  for (const filePath of changeReport.deleted) {
    delete manifest.files[filePath];
    console.log(`    - Removed: ${filePath}`);
  }

  // Update changed and new files
  const allChangedFiles = [...changeReport.added, ...changeReport.modified];

  for (const filePath of allChangedFiles) {
    const hash = computeFileHash(filePath);
    const metadata = getFileMetadata(filePath);
    const result = analysisResults.get(filePath);

    manifest.files[filePath] = {
      hash,
      size: metadata.size,
      lastModified: metadata.lastModified,
      functions: result ? result.entries.map(e => e.id) : [],
      analysisTime: result ? result.analysisTime : null
    };

    console.log(`    ✓ Updated: ${filePath}`);
  }

  // Update global stats
  const graph = loadGraph();
  manifest.globalStats.totalFunctions = graph.length;
  manifest.globalStats.totalCalls = graph.reduce((sum, f) => sum + (f.calls?.length || 0), 0);
  manifest.globalStats.totalFiles = Object.keys(manifest.files).length;
  manifest.generated = new Date().toISOString();

  // Save manifest
  writeFileSync('.llm-context/manifest.json', JSON.stringify(manifest, null, 2));
  console.log('    ✓ Manifest updated');

  return manifest;
}

/**
 * Main incremental analysis workflow (function-level)
 */
async function mainFunctionLevel() {
  console.log('[1] Detecting file-level changes...');
  const changeReport = detectChanges();

  if (changeReport.needsFullAnalysis) {
    console.log('\n⚠ No manifest found - run full analysis first');
    return;
  }

  const changedFiles = [...changeReport.added, ...changeReport.modified];

  if (changedFiles.length === 0) {
    console.log('\n✓ No changes detected - all files up to date!');
    return;
  }

  console.log(`\n[2] Detecting function-level changes in ${changedFiles.length} files...`);
  const functionChanges = detectAllFunctionChanges(changedFiles, changeReport.manifest);

  if (functionChanges.size === 0) {
    console.log('\n✓ No function-level changes detected!');
    return;
  }

  printFunctionChangeSummary(functionChanges);

  console.log('\n[3] Re-analyzing changed/added functions...');

  const allNewEntries = [];
  const analysisResults = new Map();

  for (const [filePath, changes] of functionChanges) {
    // Get names of functions to analyze (modified + added)
    const targetFunctions = [
      ...changes.modified.map(f => f.name),
      ...changes.added.map(f => f.name)
    ];

    if (targetFunctions.length === 0) {
      continue;
    }

    console.log(`    ${filePath}: analyzing ${targetFunctions.length} functions`);

    try {
      const result = analyzeSpecificFunctions(filePath, targetFunctions);
      analysisResults.set(filePath, result);
      allNewEntries.push(...result.entries);

      console.log(`      Found: ${result.entries.length} entries, ${result.analysisTime}ms`);
      console.log(`      Skipped: ${result.totalFunctions - result.analyzedFunctions} unchanged functions`);
    } catch (error) {
      console.log(`      Error: ${error.message}`);
    }
  }

  // Update graph (function-level)
  const updatedGraph = updateGraphFunctionLevel(functionChanges, allNewEntries);

  // Update manifest (note: still need to update function hashes)
  console.log('\n[5] Updating manifest.json...');
  const manifest = changeReport.manifest;

  for (const filePath of changedFiles) {
    const hash = computeFileHash(filePath);
    const metadata = getFileMetadata(filePath);

    // Re-extract all function hashes for changed files
    const { extractFileFunctions } = await import('./manifest-generator.js');
    const functionHashes = extractFileFunctions(filePath);

    if (manifest.files[filePath]) {
      manifest.files[filePath].hash = hash;
      manifest.files[filePath].size = metadata.size;
      manifest.files[filePath].lastModified = metadata.lastModified;
      manifest.files[filePath].functionHashes = functionHashes;
    }

    console.log(`    ✓ Updated: ${filePath}`);
  }

  // Update global stats
  manifest.globalStats.totalFunctions = updatedGraph.length;
  manifest.globalStats.totalCalls = updatedGraph.reduce((sum, f) => sum + (f.calls?.length || 0), 0);
  manifest.generated = new Date().toISOString();

  writeFileSync('.llm-context/manifest.json', JSON.stringify(manifest, null, 2));
  console.log('    ✓ Manifest updated');

  console.log('\n=== Incremental Analysis Complete (Function-Level) ===');
  console.log(`Functions re-analyzed: ${allNewEntries.length}`);
  console.log(`Total functions in graph: ${updatedGraph.length}`);
  console.log(`Total calls tracked: ${manifest.globalStats.totalCalls}`);
}

/**
 * Main incremental analysis workflow (file-level)
 */
async function mainFileLevel() {
  console.log('[1] Detecting changes...');
  const changeReport = detectChanges();

  if (changeReport.needsFullAnalysis) {
    console.log('\n⚠ No manifest found - run full analysis first:');
    console.log('  1. node manifest-generator.js');
    console.log('  2. Ensure graph.jsonl exists');
    console.log('  3. Run this script again');
    return;
  }

  const changedFiles = [...changeReport.added, ...changeReport.modified];

  if (changedFiles.length === 0) {
    console.log('\n✓ No changes detected - all files up to date!');
    return;
  }

  console.log(`\n[2] Re-analyzing ${changedFiles.length} changed files...`);

  const analysisResults = new Map();
  const allNewEntries = [];

  for (const filePath of changedFiles) {
    console.log(`    Analyzing: ${filePath}`);
    const result = analyzeSingleFile(filePath);
    analysisResults.set(filePath, result);
    allNewEntries.push(...result.entries);
  }

  console.log(`\n[3] Summary of re-analysis:`);
  console.log(`    Files analyzed: ${changedFiles.length}`);
  console.log(`    Functions found: ${allNewEntries.length}`);
  console.log(`    Total time: ${Array.from(analysisResults.values()).reduce((sum, r) => sum + r.analysisTime, 0)}ms`);

  // Update graph
  const updatedGraph = updateGraph(changedFiles, allNewEntries);

  // Update manifest
  const updatedManifest = updateManifest(changeReport, analysisResults);

  console.log('\n=== Incremental Analysis Complete (File-Level) ===');
  console.log(`Files re-analyzed: ${changedFiles.length}`);
  console.log(`Files skipped: ${changeReport.unchanged.length}`);
  console.log(`Total functions in graph: ${updatedGraph.length}`);
  console.log(`Total calls tracked: ${updatedManifest.globalStats.totalCalls}`);

  const percentSkipped = ((changeReport.unchanged.length / (changedFiles.length + changeReport.unchanged.length)) * 100).toFixed(1);
  console.log(`\n✓ Efficiency: ${percentSkipped}% of files skipped!`);
}

/**
 * Main entry point - route based on granularity config
 */
async function main() {
  const config = loadConfig();
  const granularity = config.granularity || 'file';

  console.log(`Granularity mode: ${granularity}\n`);

  if (granularity === 'function') {
    await mainFunctionLevel();
  } else {
    await mainFileLevel();
  }
}

main();
