#!/usr/bin/env node
/**
 * Prime - Inject LLM-optimized context at session start
 *
 * This is called by the SessionStart hook to provide Claude Code
 * with immediate context about the codebase (similar to `bd prime`).
 *
 * Outputs 1-2k tokens of essential context:
 * - System overview (L0 summary)
 * - Key statistics
 * - Entry points
 * - Side effects summary
 */

import { existsSync, readFileSync } from 'fs';
import { join } from 'path';

/**
 * Check if .llm-context exists
 */
function checkInitialized() {
  const llmContextDir = join(process.cwd(), '.llm-context');
  if (!existsSync(llmContextDir)) {
    console.log('📊 LLM Context Tools: Not initialized in this project');
    console.log('   Run: llm-context analyze');
    return false;
  }
  return true;
}

/**
 * Load L0 system summary
 */
function loadSystemSummary() {
  const summaryPath = join(process.cwd(), '.llm-context', 'summaries', 'L0-system.md');
  if (!existsSync(summaryPath)) {
    return null;
  }
  return readFileSync(summaryPath, 'utf-8');
}

/**
 * Load statistics from graph.jsonl
 */
function loadStatistics() {
  const graphPath = join(process.cwd(), '.llm-context', 'graph.jsonl');
  if (!existsSync(graphPath)) {
    return null;
  }

  const lines = readFileSync(graphPath, 'utf-8').trim().split('\n');
  const entries = lines.map(line => JSON.parse(line));

  const stats = {
    totalFunctions: entries.length,
    languages: new Set(),
    files: new Set(),
    async: 0,
    sideEffects: {
      file_io: 0,
      network: 0,
      database: 0,
      logging: 0,
      dom: 0
    }
  };

  for (const entry of entries) {
    if (entry.language) stats.languages.add(entry.language);
    if (entry.file) stats.files.add(entry.file);
    if (entry.async) stats.async++;
    if (entry.effects) {
      for (const effect of entry.effects) {
        if (stats.sideEffects.hasOwnProperty(effect)) {
          stats.sideEffects[effect]++;
        }
      }
    }
  }

  stats.languages = Array.from(stats.languages);
  stats.totalFiles = stats.files.size;
  delete stats.files;

  return stats;
}

/**
 * Find entry points (functions with side effects, not called by others)
 */
function findEntryPoints() {
  const graphPath = join(process.cwd(), '.llm-context', 'graph.jsonl');
  if (!existsSync(graphPath)) {
    return [];
  }

  const lines = readFileSync(graphPath, 'utf-8').trim().split('\n');
  const entries = lines.map(line => JSON.parse(line));

  // Build caller map
  const callers = new Map();
  for (const entry of entries) {
    if (!callers.has(entry.id)) {
      callers.set(entry.id, 0);
    }
    if (entry.calls) {
      for (const call of entry.calls) {
        callers.set(call, (callers.get(call) || 0) + 1);
      }
    }
  }

  // Find entry points (has side effects, not called by others)
  const entryPoints = entries.filter(entry => {
    const hasSideEffects = entry.effects && entry.effects.length > 0;
    const notCalled = (callers.get(entry.id) || 0) === 0;
    return hasSideEffects && notCalled;
  });

  return entryPoints.slice(0, 10).map(e => ({
    name: e.id,
    file: e.file,
    line: e.line,
    effects: e.effects
  }));
}

/**
 * Generate prime output
 */
function generatePrimeOutput() {
  console.log('\n╔═══════════════════════════════════════════════════════════════╗');
  console.log('║                 LLM Context Tools - Primed                    ║');
  console.log('╚═══════════════════════════════════════════════════════════════╝\n');

  // Load L0 summary
  const systemSummary = loadSystemSummary();
  if (systemSummary) {
    console.log('## System Overview\n');
    console.log(systemSummary);
    console.log('\n---\n');
  }

  // Load statistics
  const stats = loadStatistics();
  if (stats) {
    console.log('## Statistics\n');
    console.log(`**Total Functions:** ${stats.totalFunctions}`);
    console.log(`**Files Analyzed:** ${stats.totalFiles}`);
    console.log(`**Languages:** ${stats.languages.join(', ')}`);
    console.log(`**Async Functions:** ${stats.async}`);
    console.log('\n**Side Effects:**');
    for (const [effect, count] of Object.entries(stats.sideEffects)) {
      if (count > 0) {
        console.log(`  - ${effect}: ${count} functions`);
      }
    }
    console.log('\n---\n');
  }

  // Find entry points
  const entryPoints = findEntryPoints();
  if (entryPoints.length > 0) {
    console.log('## Entry Points (Top 10)\n');
    for (const ep of entryPoints) {
      console.log(`**${ep.name}** (${ep.file}:${ep.line})`);
      console.log(`  Effects: ${ep.effects.join(', ')}`);
      console.log();
    }
    console.log('---\n');
  }

  // Usage instructions
  console.log('## Quick Commands\n');
  console.log('```bash');
  console.log('llm-context query find-function <name>  # Find function');
  console.log('llm-context query calls-to <name>       # Who calls this?');
  console.log('llm-context query trace <name>          # Call tree');
  console.log('llm-context stats                       # Full statistics');
  console.log('llm-context entry-points                # All entry points');
  console.log('llm-context side-effects                # Side effects report');
  console.log('```\n');

  console.log('📚 Full documentation: .claude/CLAUDE.md\n');
}

/**
 * Main
 */
function main() {
  if (!checkInitialized()) {
    return;
  }

  generatePrimeOutput();
}

main();
