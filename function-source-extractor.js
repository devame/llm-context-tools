#!/usr/bin/env node
/**
 * Function Source Extractor - Extract source code for individual functions
 *
 * Purpose: Extract the actual source text of functions from AST nodes
 * for function-level hashing and change detection.
 */

import { createHash } from 'crypto';

/**
 * Extract source text for a function from its AST node
 * @param {object} path - Babel path object
 * @param {string} sourceCode - Full file source code
 * @returns {string} Function source code
 */
export function extractFunctionSource(path, sourceCode) {
  const { start, end } = path.node.loc;

  if (!start || !end) {
    return '';
  }

  const lines = sourceCode.split('\n');

  // Extract lines from start.line to end.line (1-indexed)
  const funcLines = lines.slice(start.line - 1, end.line);

  // Handle single-line functions
  if (funcLines.length === 1) {
    return funcLines[0].substring(start.column, end.column);
  }

  // Multi-line functions
  // First line: from start.column to end
  funcLines[0] = funcLines[0].substring(start.column);

  // Last line: from beginning to end.column
  const lastIdx = funcLines.length - 1;
  funcLines[lastIdx] = funcLines[lastIdx].substring(0, end.column);

  return funcLines.join('\n');
}

/**
 * Compute hash of function source code
 * @param {string} source - Function source code
 * @returns {string} MD5 hash
 */
export function hashFunctionSource(source) {
  // Normalize whitespace to avoid spurious changes from reformatting
  const normalized = source
    .replace(/\s+/g, ' ')  // Collapse whitespace
    .trim();

  return createHash('md5').update(normalized).digest('hex');
}

/**
 * Generate a unique function ID
 * @param {string} filePath - File path
 * @param {string} funcName - Function name
 * @param {number} line - Line number
 * @returns {string} Unique function ID
 */
export function generateFunctionId(filePath, funcName, line) {
  // For anonymous functions, use line number
  if (!funcName || funcName === 'anonymous') {
    return `${filePath}#L${line}`;
  }

  return `${filePath}#${funcName}`;
}

/**
 * Extract function metadata including source and hash
 * @param {object} path - Babel path object
 * @param {string} sourceCode - Full file source code
 * @param {string} filePath - File path
 * @returns {object} Function metadata
 */
export function extractFunctionMetadata(path, sourceCode, filePath) {
  const node = path.node;

  // Get function name
  let funcName = 'anonymous';
  if (node.id?.name) {
    funcName = node.id.name;
  } else if (path.parent?.type === 'VariableDeclarator' && path.parent.id?.name) {
    funcName = path.parent.id.name;
  }

  // Extract source
  const source = extractFunctionSource(path, sourceCode);
  const hash = hashFunctionSource(source);

  // Get location
  const line = node.loc?.start.line || 0;
  const endLine = node.loc?.end.line || 0;

  // Generate unique ID
  const funcId = generateFunctionId(filePath, funcName, line);

  return {
    id: funcId,
    name: funcName,
    line,
    endLine,
    source,
    hash,
    size: source.length,
    isAsync: node.async || false,
    isGenerator: node.generator || false
  };
}

/**
 * Compare two function sources to detect if they're similar (for rename detection)
 * @param {string} source1 - First function source
 * @param {string} source2 - Second function source
 * @returns {number} Similarity score (0-1)
 */
export function computeSimilarity(source1, source2) {
  // Simple similarity: compare normalized versions
  const norm1 = source1.replace(/\s+/g, ' ').trim();
  const norm2 = source2.replace(/\s+/g, ' ').trim();

  if (norm1 === norm2) return 1.0;

  // Levenshtein distance would be better, but this is simple
  const maxLen = Math.max(norm1.length, norm2.length);
  if (maxLen === 0) return 1.0;

  let matches = 0;
  const minLen = Math.min(norm1.length, norm2.length);

  for (let i = 0; i < minLen; i++) {
    if (norm1[i] === norm2[i]) matches++;
  }

  return matches / maxLen;
}

/**
 * Detect if a function was likely renamed by comparing sources
 * @param {object} deletedFunc - Deleted function metadata
 * @param {object[]} addedFuncs - List of added functions
 * @param {number} threshold - Similarity threshold (default 0.9)
 * @returns {object|null} Best match or null
 */
export function detectRename(deletedFunc, addedFuncs, threshold = 0.9) {
  let bestMatch = null;
  let bestScore = threshold;

  for (const addedFunc of addedFuncs) {
    const score = computeSimilarity(deletedFunc.source, addedFunc.source);

    if (score > bestScore) {
      bestScore = score;
      bestMatch = {
        from: deletedFunc.name,
        to: addedFunc.name,
        similarity: score
      };
    }
  }

  return bestMatch;
}
