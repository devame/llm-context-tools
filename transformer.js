#!/usr/bin/env node
/**
 * Hybrid Transformer: SCIP + Custom Analysis → LLM-Optimized Format
 *
 * Demonstrates:
 * 1. What SCIP gives us (structure, references)
 * 2. What we must add custom (function detection, side effects, intent)
 * 3. The final compact format for LLM consumption
 */

import { readFileSync, writeFileSync, readdirSync, statSync } from 'fs';
import { join, relative } from 'path';
import { parseFile } from './tree-sitter-parser.js';
import { isSupported } from './language-detector.js';

console.log('=== Hybrid Transformer: SCIP + Custom Analysis ===\n');

// Try to load SCIP data (optional)
let scipData = null;
let scipSymbols = new Map();

try {
  scipData = JSON.parse(readFileSync('.llm-context/scip-parsed.json', 'utf-8'));
  console.log('[1] Loaded SCIP data:');
  console.log(`    Documents: ${scipData.documents.length}`);
  console.log(`    Total symbols: ${scipData.documents.reduce((sum, d) => sum + (d.symbols?.length || 0), 0)}`);

  // Extract SCIP symbols for documentation
  scipData.documents.forEach(doc => {
    (doc.symbols || []).forEach(sym => {
      scipSymbols.set(sym.symbol, {
        kind: sym.kind,
        doc: sym.documentation?.[0] || '',
        sig: sym.signatureDocumentation?.text || ''
      });
    });
  });

  console.log(`\n[2] SCIP extracted:`);
  console.log(`    Unique symbols: ${scipSymbols.size}`);
} catch (error) {
  console.log('[1] No SCIP data available (proceeding with custom analysis only)');
}

// Step 2: Discover all supported source files
console.log(`\n[${scipData ? '3' : '2'}] Discovering source files...`);

function findSourceFiles(dir = '.', ignore = ['node_modules', '.git', '.llm-context', '__pycache__', '.venv', 'venv']) {
  const files = [];

  function walk(currentDir) {
    const entries = readdirSync(currentDir, { withFileTypes: true });

    for (const entry of entries) {
      const fullPath = join(currentDir, entry.name);
      const relativePath = relative('.', fullPath);

      if (ignore.some(pattern => relativePath.includes(pattern))) {
        continue;
      }

      if (entry.isDirectory()) {
        walk(fullPath);
      } else if (entry.isFile() && isSupported(entry.name)) {
        files.push(relativePath);
      }
    }
  }

  walk(dir);
  return files;
}

const sourceFiles = findSourceFiles();
console.log(`    Found ${sourceFiles.length} source files\n`);

// Step 3: Parse all files using tree-sitter
console.log(`[${scipData ? '4' : '3'}] Running custom analysis (multi-language parsing)...`);

const allFunctions = [];

for (const filePath of sourceFiles) {
  try {
    const result = parseFile(filePath, { includeSource: false });

    // Convert to graph format
    for (const func of result.functions) {
      allFunctions.push({
        id: func.name,
        name: func.name,
        type: 'function',
        file: filePath,
        line: func.line,
        params: func.params,
        async: func.async,
        calls: func.calls,
        effects: func.effects
      });
    }

  } catch (error) {
    console.log(`    Warning: Could not parse ${filePath}: ${error.message}`);
  }
}

console.log(`    Functions found: ${allFunctions.length}`);
console.log(`    Call relationships: ${allFunctions.reduce((sum, f) => sum + f.calls.length, 0)}`);
console.log(`    Side effects detected: ${allFunctions.reduce((sum, f) => sum + f.effects.length, 0)}`);

// Step 4: Build LLM-optimized graph (JSONL format)
console.log(`\n[${scipData ? '5' : '4'}] Building LLM-optimized graph...`);

const llmGraph = allFunctions.map(func => ({
  id: func.name,
  type: func.type,
  file: func.file,
  line: func.line,
  sig: func.params,
  async: func.async || false,
  calls: func.calls,
  effects: func.effects,
  scipDoc: scipSymbols.get(func.id)?.doc || ''
}));

// Write as JSONL (one function per line)
const jsonlContent = llmGraph.map(node => JSON.stringify(node)).join('\n');
writeFileSync('.llm-context/graph.jsonl', jsonlContent);

console.log(`    Generated ${llmGraph.length} nodes`);
console.log(`    Output: .llm-context/graph.jsonl`);

// Step 5: Show comparison (if SCIP available)
if (scipData) {
  console.log(`\n\n=== COMPARISON ===\n`);

  const scipSize = Buffer.byteLength(JSON.stringify(scipData));
  const llmSize = Buffer.byteLength(jsonlContent);

  console.log('SCIP Format:');
  console.log(`  Size: ${(scipSize / 1024).toFixed(1)} KB`);
  console.log(`  Structure: Nested JSON with all references`);
  console.log(`  Functions detected: 0 (all marked as "Unknown")`);
  console.log(`  Side effects: No`);

  console.log('\nLLM-Optimized Format:');
  console.log(`  Size: ${(llmSize / 1024).toFixed(1)} KB`);
  console.log(`  Structure: JSONL (one function per line)`);
  console.log(`  Functions detected: ${allFunctions.length} (via tree-sitter parsing)`);
  console.log(`  Side effects: Yes (${allFunctions.reduce((sum, f) => sum + f.effects.length, 0)} detected)`);

  console.log(`\nSize reduction: ${((1 - llmSize / scipSize) * 100).toFixed(1)}%`);
} else {
  console.log(`\n\n=== RESULTS ===\n`);
  console.log(`  Functions detected: ${allFunctions.length}`);
  console.log(`  Side effects detected: ${allFunctions.reduce((sum, f) => sum + f.effects.length, 0)}`);
  console.log(`  Output size: ${(Buffer.byteLength(jsonlContent) / 1024).toFixed(1)} KB`);
}

// Step 6: Show sample output
console.log(`\n\n=== SAMPLE OUTPUT ===\n`);

llmGraph.slice(0, 5).forEach((node, i) => {
  console.log(`[${i + 1}] ${node.id} (${node.file}:${node.line})`);
  console.log(`    Signature: ${node.sig}`);
  console.log(`    Calls: ${node.calls.slice(0, 5).join(', ') || 'none'}`);
  console.log(`    Effects: ${node.effects.join(', ') || 'none'}`);
  console.log();
});

console.log(`\n✓ Transformation complete!`);
console.log(`\nKey Insight:`);
console.log(`  Tree-sitter gave us: Multi-language AST parsing (JavaScript, TypeScript, Python)`);
console.log(`  Custom analysis added: Function detection, call graph, side effects`);
console.log(`  Result: ${allFunctions.length} functions identified from ${sourceFiles.length} files`);
