#!/usr/bin/env node
/**
 * Query Interface - Fast lookups on the LLM-optimized graph
 *
 * Note: This file is used to test incremental update detection
 */

import { readFileSync } from 'fs';

const graphLines = readFileSync('.llm-context/graph.jsonl', 'utf-8').split('\n').filter(Boolean);
const functions = graphLines.map(line => JSON.parse(line));

// Build indices
const byName = new Map();
const byFile = new Map();
const callIndex = new Map(); // who calls what
const calledByIndex = new Map(); // reverse index

functions.forEach(func => {
  const name = func.name || func.id;

  // Name index
  if (!byName.has(name)) {
    byName.set(name, []);
  }
  byName.get(name).push(func);

  // File index
  if (!byFile.has(func.file)) {
    byFile.set(func.file, []);
  }
  byFile.get(func.file).push(func);

  // Call graph indices
  func.calls.forEach(called => {
    // Forward: func calls 'called'
    if (!callIndex.has(func.id)) {
      callIndex.set(func.id, new Set());
    }
    callIndex.get(func.id).add(called);

    // Reverse: 'called' is called by func
    if (!calledByIndex.has(called)) {
      calledByIndex.set(called, new Set());
    }
    calledByIndex.get(called).add(name);
  });
});

// Query functions
// This function provides fast lookups on the graph
function query(cmd, arg) {
  switch (cmd) {
    case 'find-function':
      return Array.from(byName.get(arg) || []);

    case 'functions-in-file':
      return byFile.get(arg) || [];

    case 'calls-to':
      return Array.from(calledByIndex.get(arg) || []);

    case 'called-by':
      const func = functions.find(f => (f.name || f.id) === arg);
      return func ? func.calls : [];

    case 'side-effects':
      return functions.filter(f => f.effects.length > 0);

    case 'entry-points':
      // Functions called by few others (likely entry points)
      return functions.filter(f => {
        const name = f.name || f.id;
        const callers = calledByIndex.get(name) || new Set();
        return callers.size === 0 || f.name?.includes('main') || f.name?.includes('init');
      });

    case 'trace':
      // Trace call path from function
      return traceCalls(arg, 3);

    case 'stats':
      return {
        totalFunctions: functions.length,
        filesAnalyzed: byFile.size,
        totalCalls: functions.reduce((sum, f) => sum + f.calls.length, 0),
        withSideEffects: functions.filter(f => f.effects.length > 0).length,
        effectTypes: [...new Set(functions.flatMap(f => f.effects))]
      };

    default:
      return { error: 'Unknown query command' };
  }
}

function traceCalls(funcName, depth = 3, visited = new Set()) {
  if (depth === 0 || visited.has(funcName)) return [];

  visited.add(funcName);

  const func = functions.find(f => (f.name || f.id) === funcName);
  if (!func) return [];

  return {
    function: funcName,
    file: func.file,
    line: func.line,
    calls: func.calls.slice(0, 10).map(called => traceCalls(called, depth - 1, visited)).filter(Boolean)
  };
}

// CLI Interface
const [,, cmd, ...args] = process.argv;

if (!cmd) {
  console.log('LLM Context Query Interface\n');
  console.log('Usage: node query.js <command> [args]\n');
  console.log('Commands:');
  console.log('  find-function <name>       - Find function by name');
  console.log('  functions-in-file <path>   - List functions in file');
  console.log('  calls-to <name>            - Who calls this function');
  console.log('  called-by <name>           - What does this function call');
  console.log('  side-effects               - Functions with side effects');
  console.log('  entry-points               - Find entry point functions');
  console.log('  trace <name>               - Trace call tree from function');
  console.log('  stats                      - Show statistics');
  console.log('\nExamples:');
  console.log('  node query.js stats');
  console.log('  node query.js find-function evalAST');
  console.log('  node query.js side-effects');
  console.log('  node query.js trace evalAST');
  process.exit(0);
}

const result = query(cmd, args.join(' '));

if (Array.isArray(result)) {
  console.log(`\nFound ${result.length} results:\n`);
  result.slice(0, 20).forEach((item, i) => {
    if (typeof item === 'string') {
      console.log(`  ${i + 1}. ${item}`);
    } else {
      console.log(`  ${i + 1}. ${item.name || item.id} (${item.file}:${item.line})`);
      if (item.calls && item.calls.length > 0) {
        console.log(`     Calls: ${item.calls.slice(0, 5).join(', ')}`);
      }
      if (item.effects && item.effects.length > 0) {
        console.log(`     Effects: ${item.effects.join(', ')}`);
      }
    }
  });
  if (result.length > 20) {
    console.log(`  ... and ${result.length - 20} more`);
  }
} else if (typeof result === 'object') {
  console.log(JSON.stringify(result, null, 2));
}
