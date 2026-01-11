#!/usr/bin/env node
/**
 * Summary Updater - Incrementally rebuild only affected summaries
 *
 * Purpose: After incremental analysis, update only the summaries
 * that are affected by changed files, rather than regenerating everything.
 *
 * Strategy:
 * - L0 (system): Regenerate if any file changed (lightweight anyway)
 * - L1 (domains): Regenerate only affected directories
 * - L2 (modules): Regenerate only changed files
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'fs';
import { dirname } from 'path';

console.log('=== Summary Updater (Incremental) ===\n');

/**
 * Load graph data
 */
function loadGraph() {
  const graphPath = '.llm-context/graph.jsonl';
  if (!existsSync(graphPath)) {
    console.log('⚠ No graph.jsonl found');
    return [];
  }

  const lines = readFileSync(graphPath, 'utf-8').split('\n').filter(Boolean);
  return lines.map(line => JSON.parse(line));
}

/**
 * Generate L0: System overview
 */
function generateL0(functions) {
  console.log('[1] Generating L0 (system overview)...');

  // Group by file
  const byFile = {};
  functions.forEach(func => {
    if (!byFile[func.file]) byFile[func.file] = [];
    byFile[func.file].push(func);
  });

  // Group by domain (directory)
  const byDomain = {};
  Object.keys(byFile).forEach(file => {
    const dir = dirname(file) || 'root';
    if (!byDomain[dir]) byDomain[dir] = {};

    const module = file.split('/').pop().replace('.js', '');
    byDomain[dir][module] = byFile[file];
  });

  const totalFuncs = functions.length;
  const totalCalls = functions.reduce((sum, f) => sum + f.calls.length, 0);
  const effectTypes = new Set();
  functions.forEach(f => f.effects.forEach(e => effectTypes.add(e)));

  const entryPoints = functions.filter(f =>
    f.name && (
      f.name.includes('main') ||
      f.name.includes('init') ||
      f.name.includes('eval')
    )
  );

  const L0 = `# LLM Context Tools - System Overview

**Type**: Code analysis system for LLM-optimized context generation
**Purpose**: Generate compact, semantically-rich code representations for LLM consumption
**Architecture**: JavaScript modules with incremental update support

## ⚡ Quick Queries (USE THESE before grep/read)

**To understand this codebase, try these queries FIRST:**

\`\`\`bash
# Find any function
llm-context query find-function <name>

# Understand dependencies
llm-context query calls-to <name>      # Who calls this?
llm-context query trace <name>         # Full call tree

# Discover patterns
llm-context entry-points               # ${entryPoints.length} entry points
llm-context side-effects               # Functions with I/O

# Statistics
llm-context stats                      # Full statistics
\`\`\`

**Why queries > grep:**
- ✅ Show call relationships (grep can't)
- ✅ Detect side effects (grep misses these)
- ✅ Trace call trees (grep shows only text matches)
- ✅ 80-95% fewer tokens needed

## Statistics
- **Files**: ${Object.keys(byFile).length} modules
- **Functions**: ${totalFuncs} total
- **Call relationships**: ${totalCalls}
- **Side effects**: ${Array.from(effectTypes).join(', ') || 'none'}

## Key Components
${Object.keys(byDomain).map(domain => {
  const modules = Object.keys(byDomain[domain]);
  return `- **${domain}**: ${modules.join(', ')}`;
}).join('\n')}

## Entry Points
${entryPoints.length > 0 ? entryPoints.slice(0, 5).map(f => `- \`${f.name}\` (${f.file}:${f.line})`).join('\n') : '- None detected'}

## Architecture Pattern
- **Manifest System**: Tracks file hashes for change detection
- **Incremental Analysis**: Re-analyze only changed files
- **Graph Management**: JSONL format for efficient updates
- **Query Interface**: Fast lookups on function call graphs
`;

  mkdirSync('.llm-context/summaries', { recursive: true });
  writeFileSync('.llm-context/summaries/L0-system.md', L0);
  console.log(`    ✓ L0-system.md (${L0.length} chars, ~${Math.ceil(L0.length / 4)} tokens)`);

  return L0;
}

/**
 * Generate L1: Domain summaries (only for affected domains)
 */
function generateL1(functions, changedFiles = null) {
  console.log('[2] Generating L1 (domain summaries)...');

  // Group by file
  const byFile = {};
  functions.forEach(func => {
    if (!byFile[func.file]) byFile[func.file] = [];
    byFile[func.file].push(func);
  });

  // Group by domain
  const byDomain = {};
  Object.keys(byFile).forEach(file => {
    const dir = dirname(file) || 'root';
    const module = file.split('/').pop().replace('.js', '');

    if (!byDomain[dir]) byDomain[dir] = {};
    byDomain[dir][module] = byFile[file];
  });

  // Load existing L1 if available
  let existingL1 = [];
  if (existsSync('.llm-context/summaries/L1-domains.json')) {
    existingL1 = JSON.parse(readFileSync('.llm-context/summaries/L1-domains.json', 'utf-8'));
  }

  // Determine which domains were affected
  const affectedDomains = new Set();
  if (changedFiles) {
    changedFiles.forEach(file => {
      const dir = dirname(file) || 'root';
      affectedDomains.add(dir);
    });
  } else {
    // If no changed files specified, regenerate all
    Object.keys(byDomain).forEach(dir => affectedDomains.add(dir));
  }

  console.log(`    Affected domains: ${Array.from(affectedDomains).join(', ')}`);

  // Build L1 summaries
  const L1Summaries = [];

  // Keep existing summaries for unchanged domains
  if (changedFiles) {
    existingL1.forEach(summary => {
      if (!affectedDomains.has(summary.domain)) {
        L1Summaries.push(summary);
        console.log(`    ↻ Kept: ${summary.domain} (unchanged)`);
      }
    });
  }

  // Generate summaries for affected domains
  Object.entries(byDomain).forEach(([domain, modules]) => {
    if (!affectedDomains.has(domain) && changedFiles) return;

    const domainFuncs = Object.values(modules).flat();
    const funcCount = domainFuncs.length;
    const moduleList = Object.keys(modules);

    const domainEffects = new Set();
    domainFuncs.forEach(f => f.effects.forEach(e => domainEffects.add(e)));

    const summary = {
      domain,
      modules: moduleList,
      functionCount: funcCount,
      effects: Array.from(domainEffects),
      keyFunctions: domainFuncs
        .filter(f => f.calls.length > 3 || f.effects.length > 0)
        .slice(0, 5)
        .map(f => ({ name: f.name, file: f.file, line: f.line }))
    };

    L1Summaries.push(summary);
    console.log(`    ✓ Updated: ${domain} (${funcCount} functions)`);
  });

  writeFileSync('.llm-context/summaries/L1-domains.json', JSON.stringify(L1Summaries, null, 2));
  console.log(`    ✓ L1-domains.json (${L1Summaries.length} domains)`);

  return L1Summaries;
}

/**
 * Generate L2: Module summaries (only for affected modules)
 */
function generateL2(functions, changedFiles = null) {
  console.log('[3] Generating L2 (module summaries)...');

  // Group by file
  const byFile = {};
  functions.forEach(func => {
    if (!byFile[func.file]) byFile[func.file] = [];
    byFile[func.file].push(func);
  });

  // Load existing L2 if available
  let existingL2 = [];
  if (existsSync('.llm-context/summaries/L2-modules.json')) {
    existingL2 = JSON.parse(readFileSync('.llm-context/summaries/L2-modules.json', 'utf-8'));
  }

  // Determine which files were affected
  const affectedFiles = changedFiles ? new Set(changedFiles) : new Set(Object.keys(byFile));

  console.log(`    Affected modules: ${affectedFiles.size}`);

  const L2Summaries = [];

  // Keep existing summaries for unchanged files
  if (changedFiles) {
    existingL2.forEach(summary => {
      if (!affectedFiles.has(summary.file)) {
        L2Summaries.push(summary);
        console.log(`    ↻ Kept: ${summary.module} (unchanged)`);
      }
    });
  }

  // Generate summaries for affected files
  Object.entries(byFile).forEach(([file, funcs]) => {
    if (!affectedFiles.has(file) && changedFiles) return;

    const module = file.split('/').pop().replace('.js', '');
    const exports = funcs.filter(f => f.calls.length > 5);
    const effects = new Set();
    funcs.forEach(f => f.effects.forEach(e => effects.add(e)));

    const summary = {
      file,
      module,
      functionCount: funcs.length,
      exports: exports.map(f => f.name),
      effects: Array.from(effects),
      entryPoints: funcs
        .filter(f => f.name && (
          f.name.includes('main') ||
          f.name.includes('process') ||
          f.name.includes('init')
        ))
        .map(f => f.name)
    };

    L2Summaries.push(summary);
    console.log(`    ✓ Updated: ${module} (${funcs.length} functions)`);
  });

  writeFileSync('.llm-context/summaries/L2-modules.json', JSON.stringify(L2Summaries, null, 2));
  console.log(`    ✓ L2-modules.json (${L2Summaries.length} modules)`);

  return L2Summaries;
}

/**
 * Main function
 * @param {string[]} changedFiles - Optional list of changed files for incremental update
 */
function updateSummaries(changedFiles = null) {
  const functions = loadGraph();

  if (functions.length === 0) {
    console.log('⚠ No functions in graph - nothing to summarize');
    return;
  }

  console.log(`Loaded ${functions.length} functions from graph\n`);

  if (changedFiles && changedFiles.length > 0) {
    console.log(`Incremental mode: ${changedFiles.length} files changed`);
    console.log(`Changed files: ${changedFiles.join(', ')}\n`);
  } else {
    console.log('Full regeneration mode\n');
  }

  const L0 = generateL0(functions);
  const L1 = generateL1(functions, changedFiles);
  const L2 = generateL2(functions, changedFiles);

  console.log('\n=== Summary Update Complete ===');
  console.log('Generated:');
  console.log('  - L0-system.md');
  console.log('  - L1-domains.json');
  console.log('  - L2-modules.json');

  if (changedFiles && changedFiles.length > 0) {
    const domainsUpdated = new Set(changedFiles.map(f => dirname(f) || 'root')).size;
    console.log(`\nEfficiency:`);
    console.log(`  - Domains regenerated: ${domainsUpdated}`);
    console.log(`  - Modules regenerated: ${changedFiles.length}`);
  }
}

// Run if called directly
if (import.meta.url === `file://${process.argv[1]}`) {
  // Check if changed files were passed as arguments
  const changedFiles = process.argv.slice(2);
  updateSummaries(changedFiles.length > 0 ? changedFiles : null);
}

// Export for use in other modules
export { updateSummaries };
