#!/usr/bin/env node
/**
 * Hybrid Transformer: SCIP + Custom Analysis → LLM-Optimized Format
 *
 * Demonstrates:
 * 1. What SCIP gives us (structure, references)
 * 2. What we must add custom (function detection, side effects, intent)
 * 3. The final compact format for LLM consumption
 */

import { readFileSync, writeFileSync } from 'fs';
import { parse } from '@babel/parser';
import traverse from '@babel/traverse';

console.log('=== Hybrid Transformer: SCIP + Custom Analysis ===\n');

// Load SCIP data
const scipData = JSON.parse(readFileSync('.llm-context/scip-parsed.json', 'utf-8'));

console.log('[1] Loaded SCIP data:');
console.log(`    Documents: ${scipData.documents.length}`);
console.log(`    Total symbols: ${scipData.documents.reduce((sum, d) => sum + (d.symbols?.length || 0), 0)}`);
console.log(`    Total occurrences: ${scipData.documents.reduce((sum, d) => sum + (d.occurrences?.length || 0), 0)}`);

// Step 1: Extract what SCIP gives us
const scipSymbols = new Map(); // symbol path -> info
const scipReferences = new Map(); // file -> [references]

scipData.documents.forEach(doc => {
  const refs = [];

  (doc.occurrences || []).forEach(occ => {
    if (occ.symbolRoles === 4) { // Reference
      refs.push({
        symbol: occ.symbol,
        line: occ.range[0]
      });
    }
  });

  scipReferences.set(doc.relativePath, refs);

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
console.log(`    Files with references: ${scipReferences.size}`);

// Step 2: Custom Analysis - Parse JS files to find functions
console.log(`\n[3] Running custom analysis (AST parsing)...`);

const functions = [];
const callGraph = new Map(); // function -> calls[]
const sideEffects = new Map(); // function -> effects[]

scipData.documents
  .filter(doc => doc.relativePath.endsWith('.js') && !doc.relativePath.includes('test'))
  .forEach(doc => {
    try {
      const sourcePath = doc.relativePath;
      const source = readFileSync(sourcePath, 'utf-8');

      // Parse with Babel
      const ast = parse(source, {
        sourceType: 'module',
        plugins: []
      });

      // First pass: collect all functions
      traverse.default(ast, {
        FunctionDeclaration(path) {
          const funcName = path.node.id?.name || 'anonymous';
          const funcId = `${sourcePath}#${funcName}`;

          functions.push({
            id: funcId,
            name: funcName,
            type: 'function',
            file: sourcePath,
            line: path.node.loc?.start.line || 0,
            params: path.node.params.map(p => p.name || '?').join(', '),
            async: path.node.async,
            exported: false,
            path: path // Store for second pass
          });

          callGraph.set(funcId, []);
          sideEffects.set(funcId, []);
        },

        VariableDeclarator(path) {
          if (path.node.init?.type === 'ArrowFunctionExpression' ||
              path.node.init?.type === 'FunctionExpression') {
            const funcName = path.node.id?.name || 'anonymous';
            const funcId = `${sourcePath}#${funcName}`;

            functions.push({
              id: funcId,
              name: funcName,
              type: 'function',
              file: sourcePath,
              line: path.node.loc?.start.line || 0,
              params: path.node.init.params.map(p => p.name || '?').join(', '),
              async: path.node.init.async,
              exported: false,
              path: path
            });

            callGraph.set(funcId, []);
            sideEffects.set(funcId, []);
          }
        }
      });

      // Second pass: analyze each function's body
      functions.forEach(func => {
        if (!func.path) return;

        const funcId = func.id;

        func.path.traverse({
          CallExpression(path) {
            const callee = path.node.callee;
            let calledName = '';

            if (callee.type === 'Identifier') {
              calledName = callee.name;
            } else if (callee.type === 'MemberExpression') {
              const obj = callee.object.name || '';
              const prop = callee.property.name || '';
              calledName = obj ? `${obj}.${prop}` : prop;
            }

            if (calledName) {
              const calls = callGraph.get(funcId) || [];
              calls.push(calledName);
              callGraph.set(funcId, calls);

              // Detect side effects
              const effects = sideEffects.get(funcId) || [];

              // File I/O
              if (/read|write|append|unlink|mkdir|rmdir|fs\./i.test(calledName)) {
                effects.push({ type: 'file_io', at: calledName });
              }

              // Network
              if (/fetch|request|axios|http|socket/i.test(calledName)) {
                effects.push({ type: 'network', at: calledName });
              }

              // Console/logging
              if (/console\.|log\.|logger\.|debug|info|warn|error/i.test(calledName)) {
                effects.push({ type: 'logging', at: calledName });
              }

              // Database
              if (/query|execute|find|findOne|save|insert|update|delete|collection|db\./i.test(calledName)) {
                effects.push({ type: 'database', at: calledName });
              }

              // DOM manipulation
              if (/querySelector|getElementById|createElement|appendChild|innerHTML|textContent/i.test(calledName)) {
                effects.push({ type: 'dom', at: calledName });
              }

              sideEffects.set(funcId, effects);
            }
          }
        });

        // Clean up - remove path reference
        delete func.path;
      });

    } catch (error) {
      console.log(`    Warning: Could not parse ${doc.relativePath}: ${error.message}`);
    }
  });

console.log(`    Functions found: ${functions.length}`);
console.log(`    Call relationships: ${Array.from(callGraph.values()).flat().length}`);
console.log(`    Side effects detected: ${Array.from(sideEffects.values()).flat().length}`);

// Step 3: Build LLM-optimized graph (JSONL format)
console.log(`\n[4] Building LLM-optimized graph...`);

const llmGraph = functions.map(func => {
  const calls = callGraph.get(func.id) || [];
  const effects = sideEffects.get(func.id) || [];

  // Remove duplicates
  const uniqueCalls = [...new Set(calls)].filter(c => c !== func.name);
  const uniqueEffects = effects.reduce((acc, e) => {
    const key = `${e.type}:${e.at}`;
    if (!acc.has(key)) {
      acc.set(key, e);
    }
    return acc;
  }, new Map());

  return {
    id: func.name,
    type: func.type,
    file: func.file,
    line: func.line,
    sig: `(${func.params})`,
    async: func.async || false,
    calls: uniqueCalls.slice(0, 10), // Limit for compactness
    effects: Array.from(uniqueEffects.values()).map(e => e.type),
    // SCIP data if available
    scipDoc: scipSymbols.get(func.id)?.doc || ''
  };
});

// Write as JSONL (one function per line)
const jsonlContent = llmGraph.map(node => JSON.stringify(node)).join('\n');
writeFileSync('.llm-context/graph.jsonl', jsonlContent);

console.log(`    Generated ${llmGraph.length} nodes`);
console.log(`    Output: .llm-context/graph.jsonl`);

// Step 4: Show comparison
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
console.log(`  Functions detected: ${functions.length} (via custom AST parsing)`);
console.log(`  Side effects: Yes (${Array.from(sideEffects.values()).flat().length} detected)`);

console.log(`\nSize reduction: ${((1 - llmSize / scipSize) * 100).toFixed(1)}%`);

// Step 5: Show sample output
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
console.log(`  SCIP gave us: Symbol references and structure`);
console.log(`  Custom analysis added: Function detection, call graph, side effects`);
console.log(`  Result: ${((1 - llmSize / scipSize) * 100).toFixed(0)}% smaller, ${functions.length} functions identified`);
