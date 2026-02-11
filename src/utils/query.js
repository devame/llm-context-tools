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
    // We make it stricter when there are too many results.
    const threshold = results.length > 30 ? 0.6 : 0.4;
    if (results.length > 15 && maxScore > 0.1) {
      results = results.filter(hit => hit.score > maxScore * threshold);
    }
  }

  // Map back to original function objects, and normalize score
  return results.slice(0, 60).map(hit => {
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
    'side-effects', 'entry-points', 'domains', 'trace', 'stats', 'help'
  ];

  if (!knownCommands.includes(cmd)) {
    // Treat 'cmd' as the search term, and 'arg' as extra search terms if any
    const searchTerm = arg ? `${cmd} ${arg}` : cmd;
    return search(searchTerm);
  }

  switch (cmd) {
    case 'search':
      return search(arg);

    case 'find-function': {
      // Parse flags from arg
      const parts = (arg || '').split(/\s+/);
      let searchTerm = '';
      let fileFilter = null;
      let exactOnly = false;

      for (let i = 0; i < parts.length; i++) {
        if (parts[i] === '--in' && parts[i + 1]) {
          fileFilter = parts[++i];
        } else if (parts[i] === '--exact') {
          exactOnly = true;
        } else {
          searchTerm += (searchTerm ? ' ' : '') + parts[i];
        }
      }

      if (!searchTerm) return [];

      // 1. Try exact name match first
      let matches = Array.from(byName.get(searchTerm) || []);

      // 2. Try namespace-qualified match (e.g., "model.ingest/parse-source")
      if (matches.length === 0 && searchTerm.includes('/')) {
        const [ns, name] = searchTerm.split('/');
        matches = functions.filter(f => {
          const funcName = f.name || f.id_str;
          return funcName === name && f.file.includes(ns.replace(/\./g, '/'));
        });
      }

      // 3. If no exact match and not --exact, try strict substring matching
      if (matches.length === 0 && !exactOnly) {
        matches = functions.filter(f => {
          const funcName = f.name || f.id_str || '';
          return funcName.toLowerCase().includes(searchTerm.toLowerCase());
        });
      }

      // 4. Apply file filter if specified
      if (fileFilter) {
        matches = matches.filter(f => f.file.includes(fileFilter));
      }

      // 5. Sort by relevance: exact match first, then by name length (shorter = more specific)
      matches.sort((a, b) => {
        const aName = a.name || a.id_str || '';
        const bName = b.name || b.id_str || '';
        const aExact = aName.toLowerCase() === searchTerm.toLowerCase() ? 0 : 1;
        const bExact = bName.toLowerCase() === searchTerm.toLowerCase() ? 0 : 1;
        if (aExact !== bExact) return aExact - bExact;
        return aName.length - bName.length;
      });

      return matches;
    }

    case 'functions-in-file':
      return byFile.get(arg) || [];

    case 'calls-to':
      // Smart calls-to: If it doesn't find exact, try finding all that start with the prefix
      // This helps with finding calls in specific namespaces
      if (calledByIndex.has(arg)) {
        return Array.from(calledByIndex.get(arg));
      }

      const results = new Set();
      for (const [key, set] of calledByIndex.entries()) {
        if (key === arg || key.startsWith(arg + '/') || key.endsWith('/' + arg)) {
          set.forEach(item => results.add(item));
        }
      }
      return Array.from(results);

    case 'called-by':
      const func = functions.find(f => (f.name || f.id_str) === arg);
      return func ? func.calls : [];

    case 'side-effects':
      return functions.filter(f => f.effects.length > 0);

    case 'entry-points':
      // Functions called by few others (likely entry points)
      let list = functions.filter(f => {
        const name = f.name;
        const id = f.id_str;

      // Check exact name callers (likely internal or known calls)
        const callers = calledByIndex.get(name) || new Set();
        const idCallers = calledByIndex.get(id) || new Set();

        // Suffix matching: check if anyone calls "*.name"
        let suffixCallers = 0;
        for (const [key, set] of calledByIndex.entries()) {
          if (key.endsWith('/' + name)) {
            suffixCallers += set.size;
          }
        }

        return (callers.size === 0 && idCallers.size === 0 && suffixCallers === 0) ||
          name?.includes('main') || name?.includes('init');
      });

      if (process.argv.includes('--grouped')) {
        return groupByDomain(list);
      }
      return list;

    case 'domains':
      return groupByDomain(functions);

    case 'trace':
      // Trace call path from function
      let targetFunc = Array.from(byName.get(arg) || [])[0];
      if (!targetFunc) {
        // Fallback to fuzzy search for trace entry point
        const fuzzyHits = search(arg);
        if (fuzzyHits.length > 0) {
          targetFunc = functions.find(f => f.id === fuzzyHits[0].id);
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

function groupByDomain(funcList) {
  const domains = new Map();

  funcList.forEach(f => {
    // Extract domain from file path (e.g., frontend/src/cljs/cl_viz/ui -> ui)
    // Or just use the directory
    const parts = f.file.split('/');
    const domain = parts.length > 3 ? parts.slice(0, -1).join('/') : 'root';

    if (!domains.has(domain)) {
      domains.set(domain, []);
    }
    domains.get(domain).push(f);
  });

  const results = [];
  for (const [domain, items] of domains.entries()) {
    results.push({
      domain,
      count: items.length,
      functions: items.map(i => i.name || i.id_str).slice(0, 10)
    });
  }
  return results.sort((a, b) => b.count - a.count);
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
  console.log('  find-function <name> [--in <path>] [--exact]  - Find function (supports namespace/name)');
  console.log('  functions-in-file <path>   - List functions in file');
  console.log('  calls-to <name>            - Who calls this function');
  console.log('  called-by <name>           - What does this function call');
  console.log('  side-effects               - Functions with side effects');
  console.log('  entry-points [--grouped]   - Find entry point functions');
  console.log('  domains                    - Show functional grouping by directory');
  console.log('  trace <name>               - Trace call tree from function');
  console.log('  stats                      - Show statistics');
  console.log('\nExamples:');
  console.log('  node query.js navigation   # Smart search');
  console.log('  node query.js stats');
  console.log('  node query.js domains');
  process.exit(0);
}

const result = query(cmd, args.filter(a => a !== '--grouped').join(' '));

if (Array.isArray(result)) {
  console.log(`\nFound ${result.length} results:\n`);
  result.slice(0, 30).forEach((item, i) => {
    if (typeof item === 'string') {
      console.log(`  ${i + 1}. ${item}`);
    } else if (item.domain) {
      console.log(`  ${i + 1}. [Domain] ${item.domain} (${item.count} functions)`);
      console.log(`     Examples: ${item.functions.join(', ')}...`);
    } else {
      console.log(`  ${i + 1}. ${item.name || item.id_str} (${item.file}:${item.line})`);
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
  if (result.length > 30) {
    console.log(`  ... and ${result.length - 30} more`);
  }
} else if (typeof result === 'object') {
  console.log(JSON.stringify(result, null, 2));
}
