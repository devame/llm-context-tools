#!/usr/bin/env node
/**
 * Multi-Level Summarizer - Generates L0/L1/L2 summaries for token-efficient loading
 */

import { readFileSync, writeFileSync, mkdirSync } from 'fs';

console.log('=== Multi-Level Summarizer ===\n');

// Load graph
const graphLines = readFileSync('.llm-context/graph.jsonl', 'utf-8').split('\n').filter(Boolean);
const functions = graphLines.map(line => JSON.parse(line));

console.log(`Loaded ${functions.length} functions from graph`);

// Group by file
const byFile = {};
functions.forEach(func => {
  const file = func.file;
  if (!byFile[file]) {
    byFile[file] = [];
  }
  byFile[file].push(func);
});

// Group by domain (directory)
const byDomain = {};
Object.keys(byFile).forEach(file => {
  const dir = file.split('/').slice(0, -1).join('/') || 'root';
  const module = file.split('/').pop().replace('.js', '');

  if (!byDomain[dir]) {
    byDomain[dir] = {};
  }
  byDomain[dir][module] = byFile[file];
});

console.log(`Files: ${Object.keys(byFile).length}`);
console.log(`Domains: ${Object.keys(byDomain).length}`);

// === L0: System Summary (500 tokens) ===
console.log('\n[L0] Generating system summary...');

const totalFuncs = functions.length;
const totalCalls = functions.reduce((sum, f) => sum + f.calls.length, 0);
const effectTypes = new Set();
functions.forEach(f => f.effects.forEach(e => effectTypes.add(e)));

const entryPoints = functions.filter(f =>
  f.name && (
    f.name.includes('main') ||
    f.name.includes('init') ||
    f.name.includes('eval') && f.file.includes('evaluator')
  )
);

const L0 = `# IBM CL Visualizer - System Overview

**Type**: Interactive visualizer for IBM Control Language (CL) programs
**Purpose**: Parse, evaluate, and visualize execution flow of CL programs
**Architecture**: JavaScript modules with event-driven UI

## Statistics
- **Files**: ${Object.keys(byFile).length} modules
- **Functions**: ${totalFuncs} total
- **Call relationships**: ${totalCalls}
- **Side effects**: ${Array.from(effectTypes).join(', ')}

## Key Components
${Object.keys(byDomain).map(domain => {
  const modules = Object.keys(byDomain[domain]);
  return `- **${domain}**: ${modules.join(', ')}`;
}).join('\n')}

## Entry Points
${entryPoints.slice(0, 5).map(f => `- \`${f.name}\` (${f.file}:${f.line})`).join('\n')}

## Architecture Pattern
- **Parser**: Converts CL source to AST (grammar.js, cmdParser.js)
- **Evaluator**: Executes AST and tracks state (evaluator.js, statementEvaluators.js)
- **State**: Manages variables and execution flow (state.js, stateStorage.js)
- **UI**: Visualizes execution and state (ui.js, formatting.js)
`;

mkdirSync('.llm-context/summaries', { recursive: true });
writeFileSync('.llm-context/summaries/L0-system.md', L0);
console.log(`   Wrote L0-system.md (${L0.length} chars, ~${Math.ceil(L0.length / 4)} tokens)`);

// === L1: Domain Summaries (50-100 tokens each) ===
console.log('\n[L1] Generating domain summaries...');

const L1Summaries = [];

Object.entries(byDomain).forEach(([domain, modules]) => {
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
      .map(f => ({ name: f.name || 'unknown', file: f.file, line: f.line }))
  };

  L1Summaries.push(summary);
});

writeFileSync('.llm-context/summaries/L1-domains.json', JSON.stringify(L1Summaries, null, 2));
console.log(`   Wrote L1-domains.json with ${L1Summaries.length} domains`);

// === L2: Module Summaries (20-50 tokens each) ===
console.log('\n[L2] Generating module summaries...');

const L2Summaries = [];

Object.entries(byFile).forEach(([file, funcs]) => {
  const module = file.split('/').pop().replace('.js', '');

  const exports = funcs.filter(f => f.exported || f.calls.length > 5);
  const effects = new Set();
  funcs.forEach(f => f.effects.forEach(e => effects.add(e)));

  const summary = {
    file,
    module,
    functionCount: funcs.length,
    exports: exports.map(f => f.name || `line${f.line}`),
    effects: Array.from(effects),
    entryPoints: funcs
      .filter(f => f.name && (f.name.includes('eval') || f.name.includes('process') || f.name.includes('init')))
      .map(f => f.name)
  };

  L2Summaries.push(summary);
});

writeFileSync('.llm-context/summaries/L2-modules.json', JSON.stringify(L2Summaries, null, 2));
console.log(`   Wrote L2-modules.json with ${L2Summaries.length} modules`);

// === Token Budget Example ===
console.log('\n\n=== Token Budget Example ===\n');

console.log('Scenario: LLM needs context for "fix bug in variable evaluation"');
console.log('');

console.log('[Traditional approach] Read all relevant files:');
const traditionalFiles = ['js/evaluator.js', 'js/state.js', 'js/expressions.js', 'js/statementEvaluators.js'];
let traditionalTokens = 0;
traditionalFiles.forEach(file => {
  try {
    const content = readFileSync(file, 'utf-8');
    const tokens = Math.ceil(content.length / 4);
    traditionalTokens += tokens;
    console.log(`  ${file}: ~${tokens} tokens`);
  } catch (e) {}
});
console.log(`  Total: ~${traditionalTokens} tokens`);

console.log('\n[LLM-context approach] Load hierarchically:');
const l0Tokens = Math.ceil(L0.length / 4);
const l1Tokens = Math.ceil(JSON.stringify(L1Summaries.find(d => d.domain === 'js')).length / 4);
const l2Tokens = Math.ceil(JSON.stringify(L2Summaries.filter(m => ['evaluator', 'state', 'expressions'].includes(m.module))).length / 4);
const graphTokens = Math.ceil(
  functions
    .filter(f => ['evaluator', 'state', 'expressions'].some(m => f.file.includes(m)))
    .map(f => JSON.stringify(f))
    .join('\n')
    .length / 4
);

console.log(`  L0 (system overview): ~${l0Tokens} tokens`);
console.log(`  L1 (js domain): ~${l1Tokens} tokens`);
console.log(`  L2 (3 relevant modules): ~${l2Tokens} tokens`);
console.log(`  Graph (relevant functions): ~${graphTokens} tokens`);
console.log(`  Total: ~${l0Tokens + l1Tokens + l2Tokens + graphTokens} tokens`);

console.log(`\nSavings: ${((1 - (l0Tokens + l1Tokens + l2Tokens + graphTokens) / traditionalTokens) * 100).toFixed(0)}%`);

console.log('\n✓ Multi-level summaries complete!');
console.log('\nGenerated files:');
console.log('  .llm-context/summaries/L0-system.md');
console.log('  .llm-context/summaries/L1-domains.json');
console.log('  .llm-context/summaries/L2-modules.json');
