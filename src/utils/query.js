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

// Search function
function search(term) {
  const results = [];
  const termLower = term.toLowerCase();
  
  // Tokenize for multi-word search
  const tokens = termLower.split(/[\s\-_:]+/).filter(t => t.length > 1);

  functions.forEach(func => {
    let score = 0;
    const name = func.name || func.id || "";
    const nameLower = name.toLowerCase();
    const sigLower = (func.sig || "").toLowerCase();
    const docLower = (func.scipDoc || "").toLowerCase();
    const fileLower = (func.file || "").toLowerCase();
    const tagsLower = (func.tags || []).map(t => t.toLowerCase());

    // 1. Exact Name Match (Highest Priority)
    if (nameLower === termLower) score += 100;
    else if (nameLower.includes(termLower)) score += 50;

    // 2. Token Matching (Multi-word discovery)
    if (tokens.length > 0) {
      let tokenMatches = 0;
      tokens.forEach(token => {
        let matched = false;
        if (nameLower.includes(token)) { score += 15; matched = true; }
        if (sigLower.includes(token)) { score += 10; matched = true; }
        if (docLower.includes(token)) { score += 5; matched = true; }
        if (tagsLower.some(t => t.includes(token))) { score += 10; matched = true; }
        if (matched) tokenMatches++;
      });
      
      // Bonus for matching all tokens in a multi-word query
      if (tokenMatches === tokens.length && tokens.length > 1) score += 30;
    }

    // 3. Signature Match (Semantic discovery)
    if (sigLower.includes(termLower)) score += 40;

    // 4. Tag Match
    if (tagsLower.some(t => t.includes(termLower))) score += 30;

    // 5. Docstring/Description Match
    if (docLower.includes(termLower)) score += 20;

    // 6. File Path Match
    if (fileLower.includes(termLower)) score += 10;

    if (score > 0) {
      results.push({ ...func, score });
    }
  });

  return results.sort((a, b) => b.score - a.score);
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
      return traceCalls(arg, 3);

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

  const func = functions.find(f => (f.name || f.id) === funcName);
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
      if (item.tags && item.tags.length > 0) {
        console.log(`     Tags: [${item.tags.join(', ')}]`);
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
