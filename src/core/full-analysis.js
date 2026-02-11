#!/usr/bin/env node
/**
 * Full Analysis - Initial graph.jsonl generation
 *
 * Creates the initial graph.jsonl file by analyzing all files.
 * This replaces transformer.js which used Babel.
 */

import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from 'fs';
import { join } from 'path';
import { ParserFactory } from '../parser/parser-factory.js';
import { createAdapter } from '../parser/ast-adapter.js';
import { createAnalyzer } from './side-effects-analyzer.js';
import { createSemanticAnalyzer } from './semantic-analyzer.js';
import { parseGitignore } from '../parser/gitignore-parser.js';

console.log('=== Full Analysis (Tree-sitter) ===\n');

/**
 * Load configuration
 */
function loadConfig() {
  const configPath = './llm-context.config.json';
  if (!existsSync(configPath)) {
    return {
      patterns: {
        include: [
          '**/*.js', '**/*.jsx', '**/*.ts', '**/*.tsx', // JS/TS
          '**/*.py', // Python
          '**/*.go', // Go
          '**/*.rs', // Rust
          '**/*.java', // Java
          '**/*.c', '**/*.h', '**/*.cpp', '**/*.hpp', // C/C++
          '**/*.rb', // Ruby
          '**/*.php', // PHP
          '**/*.sh', '**/*.bash', // Bash
          '**/*.clj', '**/*.cljs', '**/*.cljc', // Clojure
          '**/*.janet' // Janet
        ], exclude: ['node_modules', '.git', '.llm-context']
      }
    };
  }
  return JSON.parse(readFileSync(configPath, 'utf-8'));
}

/**
 * Find all source files based on config patterns
 */
function findSourceFiles() {
  const config = loadConfig();
  const files = [];

  // Create gitignore checker
  const isIgnored = parseGitignore();

  // Extensions to include based on config
  const extensions = new Set();
  for (const pattern of config.patterns.include) {
    const match = pattern.match(/\*\.(\w+)$/);
    if (match) {
      extensions.add('.' + match[1]);
    }
  }

  function walk(dir) {
    try {
      const entries = readdirSync(dir);

      for (const entry of entries) {
        const fullPath = join(dir, entry);
        const stat = statSync(fullPath);
        const isDirectory = stat.isDirectory();

        // Skip if ignored by .gitignore or default patterns
        if (isIgnored(fullPath, isDirectory)) {
          continue;
        }

        // Skip excluded directories/files from config
        const isExcluded = config.patterns.exclude.some(pattern => {
          return fullPath.includes('/' + pattern + '/') ||
                 fullPath.includes('\\' + pattern + '\\') ||
                 fullPath.endsWith('/' + pattern) ||
                 fullPath.endsWith('\\' + pattern) ||
                 entry === pattern;
        });

        if (isExcluded) {
          continue;
        }

        if (isDirectory) {
          walk(fullPath);
        } else if (stat.isFile()) {
          // Check if file has matching extension
          const ext = entry.substring(entry.lastIndexOf('.'));
          if (extensions.has(ext)) {
            files.push(fullPath);
          }
        }
      }
    } catch (error) {
      // Skip inaccessible directories
    }
  }

  walk('.');
  return files;
}

/**
 * Analyze a single file
 */
async function analyzeFile(filePath) {
  try {
    const source = readFileSync(filePath, 'utf-8');

    // Detect language
    const language = ParserFactory.detectLanguage(filePath);
    if (!language) {
      console.log(`  ⚠ Skipping ${filePath} (unsupported language)`);
      return [];
    }

    // Parse
    const { tree } = await ParserFactory.parseFile(filePath);
    const adapter = createAdapter(tree, language, source, filePath);

    // Extract functions
    const functions = adapter.extractFunctionsWithNodes();
    if (functions.length === 0) {
      return [];
    }

    // Extract imports
    const imports = adapter.extractImports();
    const sideEffectAnalyzer = createAnalyzer(language, imports);
    const semanticAnalyzer = createSemanticAnalyzer(language);

    // Build graph entries
    const entries = functions.map(({ metadata }) => {
      const calls = adapter.extractCallGraph(metadata);
      const uniqueCalls = [...new Set(calls)].filter(c => c !== metadata.name);

      // Analyze side effects
      const effectsWithConfidence = sideEffectAnalyzer.analyze(uniqueCalls, metadata.source);
      const uniqueEffects = [...new Set(effectsWithConfidence.map(e => e.type))];

      // Semantic tagging
      const tags = semanticAnalyzer.analyze(metadata.source);

      // Detect code patterns (same as incremental-analyzer.js)
      const patterns = [];

      // Parsing patterns
      if (uniqueCalls.some(c => c === 'parse' || c.includes('parse'))) {
        patterns.push({
          type: 'parsing',
          tool: uniqueCalls.find(c => c.includes('tree-sitter') || c.includes('parser')) ? 'tree-sitter' : 'unknown',
          description: 'Parses source code into AST'
        });
      }

      // Hash/crypto patterns
      if (uniqueCalls.some(c => /hash|md5|sha|digest|crypto/i.test(c))) {
        patterns.push({
          type: 'hashing',
          method: uniqueCalls.find(c => /md5|sha/i.test(c)) || 'hash',
          description: 'Computes file/content hash for change detection'
        });
      }

      // Side effect detection patterns
      if (effectsWithConfidence.length > 0) {
        patterns.push({
          type: 'side-effect-detection',
          method: 'ast-analysis',
          description: 'Detects side effects via AST analysis with import tracking'
        });
      }

      // Graph manipulation
      if (uniqueCalls.some(c => /map|filter|reduce|forEach/i.test(c)) &&
        uniqueCalls.some(c => /graph|entries|functions/i.test(c))) {
        patterns.push({
          type: 'graph-transformation',
          description: 'Transforms or filters call graph data'
        });
      }

      return {
        id: metadata.id,
        name: metadata.name,
        type: 'function',
        file: filePath,
        line: metadata.line,
        sig: metadata.params ? `(${metadata.params})` : '()',
        async: metadata.isAsync || false,
        calls: uniqueCalls.slice(0, 50),
        effects: uniqueEffects,
        tags: tags,
        patterns: patterns.length > 0 ? patterns : undefined,
        scipDoc: '',
        language: language
      };
    });

    console.log(`  ✓ ${filePath}: ${entries.length} functions`);
    return entries;

  } catch (error) {
    console.log(`  ✗ ${filePath}: ${error.message}`);
    return [];
  }
}

/**
 * Main analysis
 */
async function main() {
  console.log('[1] Finding source files...');
  const files = findSourceFiles();
  console.log(`    Found ${files.length} files\n`);

  console.log('[2] Analyzing files...');
  const allEntries = [];

  for (const file of files) {
    const entries = await analyzeFile(file);
    allEntries.push(...entries);
  }

  console.log(`\n[3] Deduplicating and writing graph.jsonl...`);

  // Deduplicate by unique key (file#name)
  const seen = new Map();
  for (const entry of allEntries) {
    const key = `${entry.file}#${entry.name || entry.id}`;
    // Keep the first occurrence (or could keep latest if preferred)
    if (!seen.has(key)) {
      seen.set(key, entry);
    }
  }
  const dedupedEntries = Array.from(seen.values());

  console.log(`    Before dedup: ${allEntries.length}, After: ${dedupedEntries.length}`);

  const jsonlContent = dedupedEntries.map(entry => JSON.stringify(entry)).join('\n');
  writeFileSync('.llm-context/graph.jsonl', jsonlContent);

  console.log(`\n✅ Analysis complete!`);
  console.log(`   Files analyzed: ${files.length}`);
  console.log(`   Functions found: ${allEntries.length}`);
  console.log(`   Output: .llm-context/graph.jsonl`);
}

main().catch(error => {
  console.error('Error:', error);
  process.exit(1);
});
