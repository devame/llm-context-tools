#!/usr/bin/env node
/**
 * Incremental Analyzer - Re-analyze only changed files
 *
 * Purpose: Perform selective re-analysis of changed files and update:
 * - graph.jsonl (remove old entries, add new ones)
 * - manifest.json (update hashes and metadata)
 * - summaries (only affected modules)
 */

import { readFileSync, writeFileSync, existsSync, statSync } from 'fs';
import { createHash } from 'crypto';
import { parse } from '@babel/parser';
import traverse from '@babel/traverse';
import { detectChanges } from './change-detector.js';

console.log('=== Incremental Analyzer ===\n');

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
 * Update graph by removing old entries for changed files and adding new ones
 */
function updateGraph(changedFiles, newEntries) {
  console.log('\n[4] Updating graph.jsonl...');

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

  console.log('    ✓ Graph updated');

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
 * Main incremental analysis workflow
 */
async function main() {
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

  console.log('\n=== Incremental Analysis Complete ===');
  console.log(`Files re-analyzed: ${changedFiles.length}`);
  console.log(`Files skipped: ${changeReport.unchanged.length}`);
  console.log(`Total functions in graph: ${updatedGraph.length}`);
  console.log(`Total calls tracked: ${updatedManifest.globalStats.totalCalls}`);

  const percentSkipped = ((changeReport.unchanged.length / (changedFiles.length + changeReport.unchanged.length)) * 100).toFixed(1);
  console.log(`\n✓ Efficiency: ${percentSkipped}% of files skipped!`);
}

main();
