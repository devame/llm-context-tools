#!/usr/bin/env node
/**
 * Content Search - grep-style code searching
 *
 * Provides fast content search across codebase with context
 */

import { readdirSync, readFileSync, statSync } from 'fs';
import { join, relative } from 'path';

/**
 * Search for pattern in code files
 */
export async function grep(args) {
  const options = parseGrepArgs(args);

  if (!options.pattern) {
    console.error('❌ Error: Pattern required');
    console.error('Usage: llm-context grep <pattern> [options]');
    process.exit(1);
  }

  const results = searchFiles(options);

  displayResults(results, options);
}

/**
 * Parse grep command arguments
 */
function parseGrepArgs(args) {
  const options = {
    pattern: null,
    contextBefore: 0,
    contextAfter: 0,
    caseInsensitive: false,
    showLineNumbers: true,
    filesPattern: null,
    maxResults: 100
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];

    if (!arg.startsWith('-')) {
      if (!options.pattern) {
        options.pattern = arg;
      }
      continue;
    }

    switch (arg) {
      case '-i':
      case '--ignore-case':
        options.caseInsensitive = true;
        break;

      case '-n':
      case '--line-number':
        options.showLineNumbers = true;
        break;

      case '-C':
      case '--context':
        const context = parseInt(args[++i]);
        options.contextBefore = context;
        options.contextAfter = context;
        break;

      case '-B':
      case '--before-context':
        options.contextBefore = parseInt(args[++i]);
        break;

      case '-A':
      case '--after-context':
        options.contextAfter = parseInt(args[++i]);
        break;

      case '--files':
        options.filesPattern = args[++i];
        break;

      case '--max':
        options.maxResults = parseInt(args[++i]);
        break;
    }
  }

  return options;
}

/**
 * Search files for pattern
 */
function searchFiles(options) {
  const results = [];
  const files = findJavaScriptFiles('.', options.filesPattern);

  const regex = new RegExp(
    options.pattern,
    options.caseInsensitive ? 'gi' : 'g'
  );

  for (const file of files) {
    try {
      const content = readFileSync(file, 'utf-8');
      const lines = content.split('\n');

      lines.forEach((line, index) => {
        if (regex.test(line)) {
          // Get context lines
          const contextLines = [];

          for (let i = Math.max(0, index - options.contextBefore);
               i <= Math.min(lines.length - 1, index + options.contextAfter);
               i++) {
            contextLines.push({
              lineNumber: i + 1,
              content: lines[i],
              isMatch: i === index
            });
          }

          results.push({
            file: relative('.', file),
            lineNumber: index + 1,
            line: line.trim(),
            context: contextLines
          });

          if (results.length >= options.maxResults) {
            return results;
          }
        }
      });

      // Reset regex for next file
      regex.lastIndex = 0;
    } catch (err) {
      // Skip files that can't be read
    }
  }

  return results;
}

/**
 * Find JavaScript files matching pattern
 */
function findJavaScriptFiles(dir, pattern) {
  const files = [];
  const entries = readdirSync(dir);

  for (const entry of entries) {
    const fullPath = join(dir, entry);

    // Skip node_modules and hidden directories
    if (entry === 'node_modules' || entry.startsWith('.')) {
      continue;
    }

    const stat = statSync(fullPath);

    if (stat.isDirectory()) {
      files.push(...findJavaScriptFiles(fullPath, pattern));
    } else if (entry.endsWith('.js') || entry.endsWith('.mjs')) {
      if (!pattern || matchesPattern(fullPath, pattern)) {
        files.push(fullPath);
      }
    }
  }

  return files;
}

/**
 * Check if file matches pattern
 */
function matchesPattern(file, pattern) {
  if (!pattern) return true;

  // Simple glob-like matching
  const regex = new RegExp(
    pattern
      .replace(/\./g, '\\.')
      .replace(/\*/g, '.*')
      .replace(/\?/g, '.')
  );

  return regex.test(file);
}

/**
 * Display search results
 */
function displayResults(results, options) {
  if (results.length === 0) {
    console.log('No matches found.');
    return;
  }

  console.log(`\n🔍 Found ${results.length} match${results.length === 1 ? '' : 'es'}:\n`);

  let lastFile = null;

  for (const result of results) {
    // Show file header if different from last
    if (result.file !== lastFile) {
      if (lastFile !== null) console.log(''); // Blank line between files
      console.log(`\x1b[1m${result.file}\x1b[0m`);
      lastFile = result.file;
    }

    // Show context
    if (options.contextBefore > 0 || options.contextAfter > 0) {
      for (const ctx of result.context) {
        const lineNum = options.showLineNumbers
          ? `\x1b[36m${String(ctx.lineNumber).padStart(4)}\x1b[0m: `
          : '';

        if (ctx.isMatch) {
          // Highlight match line
          console.log(`${lineNum}\x1b[33m${ctx.content}\x1b[0m`);
        } else {
          console.log(`${lineNum}${ctx.content}`);
        }
      }
      console.log(''); // Blank line after context
    } else {
      // Just show match line
      const lineNum = options.showLineNumbers
        ? `\x1b[36m${result.lineNumber}\x1b[0m: `
        : '';
      console.log(`  ${lineNum}${result.line}`);
    }
  }

  if (results.length >= options.maxResults) {
    console.log(`\n⚠️  Showing first ${options.maxResults} results. Use --max to see more.`);
  }
}

// If run directly
if (import.meta.url === `file://${process.argv[1]}`) {
  const args = process.argv.slice(2);
  await grep(args);
}
