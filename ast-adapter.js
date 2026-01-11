/**
 * AST Adapter - Unified abstraction layer for Tree-sitter ASTs
 *
 * Converts language-specific Tree-sitter ASTs into a unified function metadata format.
 * This allows the rest of the codebase to work with a consistent interface regardless
 * of the source language.
 */

import { getFunctionQuery, getCallQuery, getImportQuery } from './language-queries.js';
import { createHash } from 'crypto';

/**
 * AST Adapter for extracting function metadata from Tree-sitter parse trees
 */
export class ASTAdapter {
  /**
   * @param {Tree} tree - Tree-sitter parse tree
   * @param {string} language - Language name (e.g., 'javascript', 'python')
   * @param {string} sourceCode - Original source code
   * @param {string} filePath - Path to source file
   */
  constructor(tree, language, sourceCode, filePath) {
    this.tree = tree;
    this.language = language;
    this.sourceCode = sourceCode;
    this.filePath = filePath;
    this.rootNode = tree.rootNode;
  }

  /**
   * Extract all functions from the parse tree
   * @returns {Array<object>} Array of function metadata objects
   */
  extractFunctions() {
    const functionQuery = getFunctionQuery(this.language);
    if (!functionQuery || functionQuery.trim() === '') {
      // Language doesn't have functions (e.g., JSON)
      return [];
    }

    try {
      const Query = this.rootNode.language.query;
      const query = new Query(functionQuery);
      const matches = query.matches(this.rootNode);

      const functions = [];

      for (const match of matches) {
        const metadata = this.extractFunctionMetadata(match);
        if (metadata) {
          functions.push(metadata);
        }
      }

      return functions;
    } catch (error) {
      console.warn(`Failed to extract functions for ${this.language}: ${error.message}`);
      return [];
    }
  }

  /**
   * Extract metadata for a single function from a query match
   * @param {QueryMatch} match - Tree-sitter query match
   * @returns {object|null} Function metadata or null
   * @private
   */
  extractFunctionMetadata(match) {
    const captures = {};

    // Organize captures by name
    for (const capture of match.captures) {
      captures[capture.name] = capture.node;
    }

    const functionNode = captures.function;
    if (!functionNode) {
      return null;
    }

    // Extract function name
    const nameNode = captures.name;
    const funcName = nameNode ? nameNode.text : 'anonymous';

    // Extract parameters
    const paramsNode = captures.params;
    const params = paramsNode ? paramsNode.text : '';

    // Extract function body
    const bodyNode = captures.body || functionNode;

    // Get location information
    const startLine = functionNode.startPosition.row + 1;  // Tree-sitter uses 0-based rows
    const endLine = functionNode.endPosition.row + 1;
    const startCol = functionNode.startPosition.column;
    const endCol = functionNode.endPosition.column;

    // Extract source code
    const source = functionNode.text;

    // Compute hash
    const hash = this.hashFunctionSource(source);

    // Check for async/generator markers (JavaScript/TypeScript specific)
    const isAsync = this.isAsyncFunction(functionNode);
    const isGenerator = this.isGeneratorFunction(functionNode);

    // Generate unique ID
    const funcId = `${this.filePath}#${funcName}`;

    return {
      id: funcId,
      name: funcName,
      line: startLine,
      endLine: endLine,
      source: source,
      hash: hash,
      size: source.length,
      params: params,
      isAsync: isAsync,
      isGenerator: isGenerator,
      language: this.language,
      node: functionNode,  // Keep reference for further analysis
      bodyNode: bodyNode   // Keep body reference for call graph extraction
    };
  }

  /**
   * Extract call graph for a specific function
   * @param {object} functionMetadata - Function metadata with node reference
   * @returns {Array<string>} Array of called function names
   */
  extractCallGraph(functionMetadata) {
    if (!functionMetadata.bodyNode) {
      return [];
    }

    const callQuery = getCallQuery(this.language);
    if (!callQuery || callQuery.trim() === '') {
      return [];
    }

    try {
      const Query = this.rootNode.language.query;
      const query = new Query(callQuery);
      const matches = query.matches(functionMetadata.bodyNode);

      const calls = new Set();

      for (const match of matches) {
        for (const capture of match.captures) {
          if (capture.name === 'call') {
            calls.add(capture.node.text);
          }
        }
      }

      return Array.from(calls);
    } catch (error) {
      console.warn(`Failed to extract call graph: ${error.message}`);
      return [];
    }
  }

  /**
   * Extract imports/requires from the file
   * @returns {Array<string>} Array of imported module names
   */
  extractImports() {
    const importQuery = getImportQuery(this.language);
    if (!importQuery || importQuery.trim() === '') {
      return [];
    }

    try {
      const Query = this.rootNode.language.query;
      const query = new Query(importQuery);
      const matches = query.matches(this.rootNode);

      const imports = new Set();

      for (const match of matches) {
        for (const capture of match.captures) {
          if (capture.name === 'source') {
            // Remove quotes from string literals
            let importPath = capture.node.text;
            importPath = importPath.replace(/^['"]|['"]$/g, '');
            imports.add(importPath);
          }
        }
      }

      return Array.from(imports);
    } catch (error) {
      console.warn(`Failed to extract imports: ${error.message}`);
      return [];
    }
  }

  /**
   * Check if function is async (JavaScript/TypeScript specific)
   * @param {Node} node - Function node
   * @returns {boolean} True if async
   * @private
   */
  isAsyncFunction(node) {
    if (!['javascript', 'typescript', 'tsx'].includes(this.language)) {
      return false;
    }

    // Check if node has 'async' keyword
    // This is a heuristic check - Tree-sitter nodes don't always expose this directly
    const source = node.text;
    return /^\s*async\s+(function|[\w]+\s*=>|\(.*\)\s*=>)/.test(source);
  }

  /**
   * Check if function is a generator (JavaScript/TypeScript specific)
   * @param {Node} node - Function node
   * @returns {boolean} True if generator
   * @private
   */
  isGeneratorFunction(node) {
    if (!['javascript', 'typescript', 'tsx'].includes(this.language)) {
      return false;
    }

    // Check if node has generator marker (*)
    const source = node.text;
    return /^\s*function\s*\*/.test(source) || /\*\s*\w+\s*\(/.test(source);
  }

  /**
   * Hash function source for change detection
   * @param {string} source - Function source code
   * @returns {string} MD5 hash
   * @private
   */
  hashFunctionSource(source) {
    // Normalize source (remove leading/trailing whitespace)
    const normalized = source.trim();
    return createHash('md5').update(normalized).digest('hex');
  }

  /**
   * Get all function nodes with their metadata
   * @returns {Array<{metadata: object, node: Node}>} Functions with nodes
   */
  extractFunctionsWithNodes() {
    const functions = this.extractFunctions();
    return functions.map(metadata => ({
      metadata,
      node: metadata.node,
      bodyNode: metadata.bodyNode
    }));
  }

  /**
   * Extract a specific node's text by line range
   * @param {number} startLine - Start line (1-based)
   * @param {number} endLine - End line (1-based)
   * @returns {string} Extracted source code
   */
  extractSourceByLines(startLine, endLine) {
    const lines = this.sourceCode.split('\n');
    return lines.slice(startLine - 1, endLine).join('\n');
  }
}

/**
 * Helper function to create an AST adapter from a Tree-sitter parse tree
 * @param {Tree} tree - Tree-sitter parse tree
 * @param {string} language - Language name
 * @param {string} sourceCode - Source code
 * @param {string} filePath - File path
 * @returns {ASTAdapter} AST adapter instance
 */
export function createAdapter(tree, language, sourceCode, filePath) {
  return new ASTAdapter(tree, language, sourceCode, filePath);
}

export default ASTAdapter;
