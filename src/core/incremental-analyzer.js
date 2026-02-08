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
import { ParserFactory } from '../parser/parser-factory.js';
import { createAdapter } from '../parser/ast-adapter.js';
import { createAnalyzer } from './side-effects-analyzer.js';
import { createSemanticAnalyzer } from './semantic-analyzer.js';
import { detectChanges } from './change-detector.js';
import { detectAllFunctionChanges, printFunctionChangeSummary } from './function-change-detector.js';
import { analyzeImpact } from './dependency-analyzer.js';

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
 * @returns {Promise<object>} Analysis results
 */
async function analyzeSpecificFunctions(sourcePath, targetFunctions = null) {
  const startTime = Date.now();
  const source = readFileSync(sourcePath, 'utf-8');

  // Detect language and parse with Tree-sitter
  const language = ParserFactory.detectLanguage(sourcePath);
  if (!language) {
    console.warn(`Unsupported file type: ${sourcePath}`);
    return { entries: [], analysisTime: 0, totalFunctions: 0, analyzedFunctions: 0 };
  }

  const { tree } = await ParserFactory.parseFile(sourcePath);
  const adapter = createAdapter(tree, language, source, sourcePath);

  // Extract all functions
  const allFunctions = adapter.extractFunctionsWithNodes();

  // Filter to target functions if specified
  const functionsToAnalyze = targetFunctions
    ? allFunctions.filter(f => targetFunctions.includes(f.metadata.name))
    : allFunctions;

  // Extract imports for side effect analysis
  const imports = adapter.extractImports();
  const sideEffectAnalyzer = createAnalyzer(language, imports);
  const semanticAnalyzer = createSemanticAnalyzer(language);

  // Analyze each function
  const results = [];

  for (const { metadata } of functionsToAnalyze) {
    // Extract call graph
    const calls = adapter.extractCallGraph(metadata);
    const uniqueCalls = [...new Set(calls)].filter(c => c !== metadata.name);

    // Analyze side effects (AST-based, not regex!)
    const effectsWithConfidence = sideEffectAnalyzer.analyze(uniqueCalls, metadata.source);
    const uniqueEffects = [...new Set(effectsWithConfidence.map(e => e.type))];

    // Semantic tagging
    const tags = semanticAnalyzer.analyze(metadata.source);

    // Detect code patterns
    const patterns = [];

    // Parsing patterns
    if (uniqueCalls.some(c => c === 'parse' || c.includes('parse'))) {
      patterns.push({
        type: 'parsing',
        tool: uniqueCalls.find(c => c.includes('tree-sitter') || c.includes('parser')) ? 'tree-sitter' : 'unknown',
        description: 'Parses source code into AST'
      });
    }

    // Hash/crypto patterns
    if (uniqueCalls.some(c => /hash|md5|sha|digest|crypto/i.test(c))) {
      patterns.push({
        type: 'hashing',
        method: uniqueCalls.find(c => /md5|sha/i.test(c)) || 'hash',
        description: 'Computes file/content hash for change detection'
      });
    }

    // Side effect detection patterns (now AST-based!)
    if (effectsWithConfidence.length > 0) {
      patterns.push({
        type: 'side-effect-detection',
        method: 'ast-analysis',
        description: 'Detects side effects via AST analysis with import tracking'
      });
    }

    // Graph manipulation
    if (uniqueCalls.some(c => /map|filter|reduce|forEach/i.test(c)) &&
        uniqueCalls.some(c => /graph|entries|functions/i.test(c))) {
      patterns.push({
        type: 'graph-transformation',
        description: 'Transforms or filters call graph data'
      });
    }

    results.push({
      id: metadata.name,
      type: 'function',
      file: sourcePath,
      line: metadata.line,
      sig: `(${metadata.isAsync ? 'async ' : ''}${metadata.params || ''})`,
      async: metadata.isAsync,
      calls: uniqueCalls.slice(0, 10),
      effects: uniqueEffects,
      tags: tags,
      patterns: patterns.length > 0 ? patterns : undefined,
      scipDoc: '',
      functionHash: metadata.hash,
      language: language  // NEW: Include language
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
 * Analyze a single file using Tree-sitter AST parsing
 * @param {string} sourcePath - Path to file
 * @returns {Promise<object>} Analysis results {entries, analysisTime}
 */
async function analyzeSingleFile(sourcePath) {
  const startTime = Date.now();

  try {
    const source = readFileSync(sourcePath, 'utf-8');

    // Detect language and parse with Tree-sitter
    const language = ParserFactory.detectLanguage(sourcePath);
    if (!language) {
      console.warn(`Unsupported file type: ${sourcePath}`);
      return { entries: [], analysisTime: Date.now() - startTime };
    }

    const { tree } = await ParserFactory.parseFile(sourcePath);
    const adapter = createAdapter(tree, language, source, sourcePath);

    // Extract all functions
    const allFunctions = adapter.extractFunctionsWithNodes();

    // Extract imports for side effect analysis
    const imports = adapter.extractImports();
    const sideEffectAnalyzer = createAnalyzer(language, imports);
    const semanticAnalyzer = createSemanticAnalyzer(language);

    // Build output
    const result = allFunctions.map(({ metadata }) => {
      // Extract call graph
      const calls = adapter.extractCallGraph(metadata);
      const uniqueCalls = [...new Set(calls)].filter(c => c !== metadata.name);

      // Analyze side effects (AST-based!)
      const effectsWithConfidence = sideEffectAnalyzer.analyze(uniqueCalls, metadata.source);
      const uniqueEffects = [...new Set(effectsWithConfidence.map(e => e.type))];

      // Semantic tagging
      const tags = semanticAnalyzer.analyze(metadata.source);

      return {
        id: metadata.name,
        type: 'function',
        file: sourcePath,
        line: metadata.line,
        sig: `(${metadata.isAsync ? 'async ' : ''}${metadata.params || ''})`,
        async: metadata.isAsync || false,
        calls: uniqueCalls.slice(0, 10),
        effects: uniqueEffects,
        tags: tags,
        scipDoc: '',
        language: language  // NEW: Include language
      };
    });

    const analysisTime = Date.now() - startTime;
    console.log(`      Analysis complete: ${allFunctions.length} functions, ${analysisTime}ms`);

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
  const functionChanges = await detectAllFunctionChanges(changedFiles, changeReport.manifest);

  if (functionChanges.size === 0) {
    console.log('\n✓ No function-level changes detected!');
    return;
  }

  printFunctionChangeSummary(functionChanges);

  // Impact analysis (if enabled)
  const config = loadConfig();
  if (config.analysis?.trackDependencies) {
    const changedFunctionNames = [];
    for (const [filePath, changes] of functionChanges) {
      changedFunctionNames.push(...changes.modified.map(f => f.name));
      changedFunctionNames.push(...changes.added.map(f => f.name));
      if (changes.renames) {
        changedFunctionNames.push(...changes.renames.map(r => r.to));
      }
    }

    if (changedFunctionNames.length > 0) {
      try {
        analyzeImpact(changedFunctionNames);
      } catch (error) {
        console.log(`\nWarning: Impact analysis failed: ${error.message}`);
      }
    }
  }

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
      const result = await analyzeSpecificFunctions(filePath, targetFunctions);
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
  const storeSource = config.incremental?.storeSource || false;

  for (const filePath of changedFiles) {
    const hash = computeFileHash(filePath);
    const metadata = getFileMetadata(filePath);

    // Re-extract all function hashes for changed files
    const { extractFileFunctions } = await import('../parser/manifest-generator.js');
    const functionHashes = extractFileFunctions(filePath, storeSource);

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
    const result = await analyzeSingleFile(filePath);
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

main().catch(error => {
  console.error('\n❌ Incremental analysis failed:', error.message);
  console.error(error.stack);
  process.exit(1);
});
