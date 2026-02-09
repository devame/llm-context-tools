#!/usr/bin/env node
/**
 * Query Interface - Fast lookups on the LLM-optimized graph
 *
 * Note: This file is used to test incremental update detection
 */

import { readFileSync } from 'fs';

import MiniSearch from 'minisearch';

const graphLines = readFileSync('.llm-context/graph.jsonl', 'utf-8').split('\n').filter(Boolean);
const functions = graphLines.map((line, index) => {
  const parsed = JSON.parse(line);
  return { ...parsed, id: index, id_str: parsed.id }; // MiniSearch needs unique 'id', preserve original string ID as 'id_str'
});

// Build indices
const byName = new Map();
const byFile = new Map();
const callIndex = new Map(); // who calls what
const calledByIndex = new Map(); // reverse index

// Initialize MiniSearch
const miniSearch = new MiniSearch({
  fields: ['name', 'id_str', 'sig', 'scipDoc', 'file', 'tags'], // Include id_str for better partial matching
  storeFields: ['name', 'file', 'line', 'sig', 'scipDoc', 'tags', 'calls', 'effects', 'patterns', 'id_str'],
  searchOptions: {
    boost: { name: 12, id_str: 10, sig: 4, tags: 3, scipDoc: 2, file: 1 },
    fuzzy: 0.15, // Slightly stricter fuzzy matching for better precision
    prefix: true
  }
});

functions.forEach(func => {
  const name = func.name || func.id_str || "anonymous"; // Use functional name or original ID

  // Name index (for exact lookups)
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
  (func.calls || []).forEach(called => {
    const callerId = func.name || func.id_str || `anon-${func.id}`;
    // Forward: func calls 'called'
    if (!callIndex.has(callerId)) {
      callIndex.set(callerId, new Set());
    }
    callIndex.get(callerId).add(called);

    // Reverse: 'called' is called by func
    if (!calledByIndex.has(called)) {
      calledByIndex.set(called, new Set());
    }
    calledByIndex.get(called).add(name);
  });
});

// Index all functions in MiniSearch
miniSearch.addAll(functions.map(f => ({
  ...f,
  name: f.name || "anonymous",
  // Join tags for search but store original structure if needed
  // Note: we can also just use the array if we provide a custom tokenizer
})));

// Search function
function search(term) {
  if (!term) return [];
  
  let results = miniSearch.search(term);

  if (results.length > 0) {
    const maxScore = results[0].score;
    // Relative Thresholding: Filter out results that are too weak compared to the best match.
    // This removes the "noise" when common tokens like 'render' match thousands of functions.
    if (results.length > 15 && maxScore > 0.1) {
      results = results.filter(hit => hit.score > maxScore * 0.4);
    }
  }

  // Map back to original function objects, and normalize score
  return results.slice(0, 40).map(hit => {
    let score = hit.score;
    // Boost named functions over anonymous ones
    if (hit.name && hit.name !== 'anonymous' && !hit.name.startsWith('anon-')) {
      score *= 2.0;
    }

    return {
      ...hit,
      score: Math.round(score * 10) // Normalize for CLI display
    };
  }).sort((a, b) => b.score - a.score);
}

// Query functions
// This function provides fast lookups on the graph
function query(cmd, arg) {
  // Smart Entry Point: If cmd is not a known command, treat as search
  const knownCommands = [
    'find-function', 'functions-in-file', 'calls-to', 'called-by',
    'side-effects', 'entry-points', 'trace', 'stats', 'help'
  ];

  if (!knownCommands.includes(cmd)) {
    // Treat 'cmd' as the search term, and 'arg' as extra search terms if any
    const searchTerm = arg ? `${cmd} ${arg}` : cmd;
    return search(searchTerm);
  }

  switch (cmd) {
    case 'search':
      return search(arg);

    case 'find-function':
      const exactMatches = Array.from(byName.get(arg) || []);
      if (exactMatches.length > 0) return exactMatches;
      // Fallback to search if no exact match
      return search(arg);

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
      let targetFunc = Array.from(byName.get(arg) || [])[0];
      if (!targetFunc) {
        // Fallback to fuzzy search for trace entry point
        const fuzzyHits = search(arg);
        if (fuzzyHits.length > 0) {
          targetFunc = functions[fuzzyHits[0].id];
          console.log(`ℹ️  Trace: No exact match for "${arg}", using closest match: "${targetFunc.name || targetFunc.id_str}"`);
        }
      }
      return targetFunc ? traceCalls(targetFunc.name || targetFunc.id_str, 3) : { error: 'Function not found' };

    case 'stats':
      return {
        totalFunctions: functions.length,
        filesAnalyzed: byFile.size,
        totalCalls: functions.reduce((sum, f) => sum + f.calls.length, 0),
        withSideEffects: functions.filter(f => f.effects.length > 0).length,
        effectTypes: [...new Set(functions.flatMap(f => f.effects))],
        tags: [...new Set(functions.flatMap(f => f.tags || []))],
        taggedFunctions: functions.filter(f => f.tags && f.tags.length > 0).length
      };

    case 'help':
    default:
      return { error: 'Unknown query command' };
  }
}

function traceCalls(funcName, depth = 3, visited = new Set()) {
  if (depth === 0 || visited.has(funcName)) return [];

  visited.add(funcName);

  const func = functions.find(f => (f.name || f.id_str) === funcName);
  if (!func) return [];

  return {
    function: funcName,
    file: func.file,
    line: func.line,
    tags: func.tags,
    calls: func.calls.slice(0, 10).map(called => traceCalls(called, depth - 1, visited)).filter(Boolean)
  };
}

// CLI Interface
const [,, cmd, ...args] = process.argv;

if (!cmd) {
  console.log('LLM Context Query Interface\n');
  console.log('Usage: node query.js <command> [args]\n');
  console.log('Commands:');
  console.log('  <search-term>              - Smart Search (names, tags, files)');
  console.log('  search <term>              - Explicit search');
  console.log('  find-function <name>       - Find function by name');
  console.log('  functions-in-file <path>   - List functions in file');
  console.log('  calls-to <name>            - Who calls this function');
  console.log('  called-by <name>           - What does this function call');
  console.log('  side-effects               - Functions with side effects');
  console.log('  entry-points               - Find entry point functions');
  console.log('  trace <name>               - Trace call tree from function');
  console.log('  stats                      - Show statistics');
  console.log('\nExamples:');
  console.log('  node query.js navigation   # Smart search');
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
      if (item.tags && Array.isArray(item.tags) && item.tags.length > 0) {
        console.log(`     Tags: [${item.tags.join(', ')}]`);
      } else if (item.tags && typeof item.tags === 'string' && item.tags.length > 0) {
        console.log(`     Tags: [${item.tags}]`);
      }
      if (item.calls && item.calls.length > 0) {
        console.log(`     Calls: ${item.calls.slice(0, 5).join(', ')}`);
      }
      if (item.effects && item.effects.length > 0) {
        console.log(`     Effects: ${item.effects.join(', ')}`);
      }
      if (item.patterns && item.patterns.length > 0) {
        console.log(`     Patterns:`);
        item.patterns.forEach(p => {
          console.log(`       • ${p.type}: ${p.description}${p.tool ? ` (${p.tool})` : ''}${p.method ? ` [${p.method}]` : ''}`);
        });
      }
    }
  });
  if (result.length > 20) {
    console.log(`  ... and ${result.length - 20} more`);
  }
} else if (typeof result === 'object') {
  console.log(JSON.stringify(result, null, 2));
}
