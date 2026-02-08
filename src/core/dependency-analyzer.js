#!/usr/bin/env node
/**
 * Dependency Analyzer - Track cross-function dependencies
 *
 * Purpose: Analyze function call relationships to determine:
 * - Which functions depend on which others
 * - Impact analysis (if function X changes, what's affected?)
 * - Dependency graphs and cycles
 * - Entry points and leaf functions
 */

import { readFileSync, writeFileSync, existsSync } from 'fs';

/**
 * Load configuration file
 */
function loadConfig() {
  const configPath = './llm-context.config.json';

  if (!existsSync(configPath)) {
    return { analysis: { trackDependencies: false } };
  }

  return JSON.parse(readFileSync(configPath, 'utf-8'));
}

/**
 * Load graph from JSONL
 * @returns {Array} Array of function entries
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
 * Build dependency graph from function call graph
 * @param {Array} functions - Array of function entries
 * @returns {object} Dependency maps
 */
export function buildDependencyGraph(functions) {
  const dependencies = new Map(); // function -> functions it depends on
  const dependents = new Map();   // function -> functions that depend on it
  const functionMap = new Map();  // name -> full entry

  // Index functions by name
  for (const func of functions) {
    const name = func.name || func.id;
    functionMap.set(name, func);
    dependencies.set(name, new Set());
    dependents.set(name, new Set());
  }

  // Build dependency relationships
  for (const func of functions) {
    const name = func.name || func.id;
    const calls = func.calls || [];

    for (const calledName of calls) {
      // Only track dependencies to functions we know about
      if (functionMap.has(calledName)) {
        dependencies.get(name).add(calledName);
        dependents.get(calledName).add(name);
      }
    }
  }

  return {
    dependencies,  // what each function depends on
    dependents,    // what depends on each function
    functionMap
  };
}

/**
 * Compute impact set for a function change
 * @param {string} functionName - Name of changed function
 * @param {Map} dependents - Dependents map
 * @param {number} maxDepth - Maximum dependency depth to traverse
 * @returns {Set} Set of affected function names
 */
export function computeImpactSet(functionName, dependents, maxDepth = 10) {
  const impacted = new Set();
  const queue = [[functionName, 0]]; // [name, depth]
  const visited = new Set();

  while (queue.length > 0) {
    const [current, depth] = queue.shift();

    if (visited.has(current) || depth > maxDepth) {
      continue;
    }

    visited.add(current);

    if (current !== functionName) {
      impacted.add(current);
    }

    // Add all functions that depend on current
    const deps = dependents.get(current) || new Set();
    for (const dependent of deps) {
      if (!visited.has(dependent)) {
        queue.push([dependent, depth + 1]);
      }
    }
  }

  return impacted;
}

/**
 * Find entry points (functions called by few/no others)
 * @param {Map} dependents - Dependents map
 * @param {number} maxCallers - Max number of callers to be considered entry point
 * @returns {Array} Array of entry point function names
 */
export function findEntryPoints(dependents, maxCallers = 2) {
  const entryPoints = [];

  for (const [funcName, callers] of dependents) {
    if (callers.size <= maxCallers ||
        funcName.includes('main') ||
        funcName.includes('init') ||
        funcName.includes('start')) {
      entryPoints.push({
        name: funcName,
        callers: callers.size
      });
    }
  }

  return entryPoints.sort((a, b) => a.callers - b.callers);
}

/**
 * Find leaf functions (functions that call no others)
 * @param {Map} dependencies - Dependencies map
 * @returns {Array} Array of leaf function names
 */
export function findLeafFunctions(dependencies) {
  const leaves = [];

  for (const [funcName, deps] of dependencies) {
    if (deps.size === 0) {
      leaves.push(funcName);
    }
  }

  return leaves;
}

/**
 * Detect dependency cycles
 * @param {Map} dependencies - Dependencies map
 * @returns {Array} Array of cycles (each cycle is an array of function names)
 */
export function detectCycles(dependencies) {
  const cycles = [];
  const visited = new Set();
  const recursionStack = new Set();
  const path = [];

  function dfs(node) {
    visited.add(node);
    recursionStack.add(node);
    path.push(node);

    const deps = dependencies.get(node) || new Set();

    for (const dep of deps) {
      if (!visited.has(dep)) {
        if (dfs(dep)) {
          return true;
        }
      } else if (recursionStack.has(dep)) {
        // Found cycle
        const cycleStart = path.indexOf(dep);
        const cycle = path.slice(cycleStart);
        cycle.push(dep); // Close the cycle
        cycles.push(cycle);
      }
    }

    path.pop();
    recursionStack.delete(node);
    return false;
  }

  for (const node of dependencies.keys()) {
    if (!visited.has(node)) {
      dfs(node);
    }
  }

  return cycles;
}

/**
 * Compute dependency depth (longest path from entry points)
 * @param {string} functionName - Function name
 * @param {Map} dependencies - Dependencies map
 * @returns {number} Depth
 */
export function computeDependencyDepth(functionName, dependencies) {
  const visited = new Set();

  function dfs(node, depth) {
    if (visited.has(node)) {
      return 0; // Avoid cycles
    }

    visited.add(node);

    const deps = dependencies.get(node) || new Set();
    if (deps.size === 0) {
      return depth;
    }

    let maxDepth = depth;
    for (const dep of deps) {
      const childDepth = dfs(dep, depth + 1);
      maxDepth = Math.max(maxDepth, childDepth);
    }

    return maxDepth;
  }

  return dfs(functionName, 0);
}

/**
 * Analyze dependencies and generate report
 */
export function analyzeDependencies() {
  console.log('=== Dependency Analyzer ===\n');

  const config = loadConfig();
  const trackDeps = config.analysis?.trackDependencies || false;

  if (!trackDeps) {
    console.log('Dependency tracking disabled in config');
    console.log('Set analysis.trackDependencies = true to enable');
    return null;
  }

  const functions = loadGraph();
  console.log(`[1] Loaded ${functions.length} functions from graph\n`);

  const { dependencies, dependents, functionMap } = buildDependencyGraph(functions);
  console.log(`[2] Built dependency graph`);
  console.log(`    Total dependencies: ${Array.from(dependencies.values()).reduce((sum, deps) => sum + deps.size, 0)}\n`);

  // Find entry points
  const entryPoints = findEntryPoints(dependents);
  console.log(`[3] Entry points (${entryPoints.length}):`);
  for (const ep of entryPoints.slice(0, 10)) {
    console.log(`    - ${ep.name} (${ep.callers} callers)`);
  }
  if (entryPoints.length > 10) {
    console.log(`    ... and ${entryPoints.length - 10} more`);
  }

  // Find leaf functions
  const leaves = findLeafFunctions(dependencies);
  console.log(`\n[4] Leaf functions (${leaves.length}):`);
  console.log(`    ${leaves.slice(0, 20).join(', ')}`);
  if (leaves.length > 20) {
    console.log(`    ... and ${leaves.length - 20} more`);
  }

  // Detect cycles
  const cycles = detectCycles(dependencies);
  if (cycles.length > 0) {
    console.log(`\n[5] ⚠ Dependency cycles detected (${cycles.length}):`);
    for (const cycle of cycles.slice(0, 5)) {
      console.log(`    ${cycle.join(' → ')}`);
    }
    if (cycles.length > 5) {
      console.log(`    ... and ${cycles.length - 5} more`);
    }
  } else {
    console.log(`\n[5] ✓ No dependency cycles detected`);
  }

  // Save dependency graph
  const depGraph = {
    version: '1.0.0',
    generated: new Date().toISOString(),
    stats: {
      totalFunctions: functions.length,
      totalDependencies: Array.from(dependencies.values()).reduce((sum, deps) => sum + deps.size, 0),
      entryPoints: entryPoints.length,
      leafFunctions: leaves.length,
      cycles: cycles.length
    },
    entryPoints,
    leaves,
    cycles,
    dependencies: Object.fromEntries(
      Array.from(dependencies.entries()).map(([name, deps]) => [
        name,
        Array.from(deps)
      ])
    ),
    dependents: Object.fromEntries(
      Array.from(dependents.entries()).map(([name, deps]) => [
        name,
        Array.from(deps)
      ])
    )
  };

  writeFileSync('.llm-context/dependencies.json', JSON.stringify(depGraph, null, 2));
  console.log(`\n✓ Dependency graph saved to .llm-context/dependencies.json`);

  return { dependencies, dependents, functionMap, cycles, entryPoints, leaves };
}

/**
 * Compute impact analysis for changed functions
 * @param {string[]} changedFunctions - Names of changed functions
 * @returns {object} Impact report
 */
export function analyzeImpact(changedFunctions) {
  const functions = loadGraph();
  const { dependencies, dependents, functionMap } = buildDependencyGraph(functions);

  const config = loadConfig();
  const maxDepth = config.analysis?.maxCallDepth || 10;

  const impactReport = {
    changedFunctions,
    totalImpacted: new Set(),
    perFunctionImpact: {}
  };

  console.log('\n=== Impact Analysis ===\n');

  for (const funcName of changedFunctions) {
    const impacted = computeImpactSet(funcName, dependents, maxDepth);

    impactReport.perFunctionImpact[funcName] = {
      directCallers: Array.from(dependents.get(funcName) || new Set()),
      totalImpacted: impacted.size,
      impactedFunctions: Array.from(impacted)
    };

    // Add to total impacted set
    for (const imp of impacted) {
      impactReport.totalImpacted.add(imp);
    }

    console.log(`${funcName}:`);
    console.log(`  Direct callers: ${impactReport.perFunctionImpact[funcName].directCallers.length}`);
    console.log(`  Total impacted: ${impacted.size}`);

    if (impacted.size > 0 && impacted.size <= 10) {
      console.log(`  Affected functions: ${Array.from(impacted).join(', ')}`);
    } else if (impacted.size > 10) {
      console.log(`  Affected functions: ${Array.from(impacted).slice(0, 10).join(', ')}...`);
    }

    console.log('');
  }

  impactReport.totalImpacted = Array.from(impactReport.totalImpacted);
  console.log(`Total unique functions impacted: ${impactReport.totalImpacted.length}\n`);

  return impactReport;
}

// CLI
if (import.meta.url === `file://${process.argv[1]}`) {
  analyzeDependencies();
}
