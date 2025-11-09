#!/usr/bin/env node
/**
 * Tree-sitter Parser - Universal language parser
 *
 * Purpose: Parse multiple languages using tree-sitter
 * Supports: Python, JavaScript (with fallback to Babel for better analysis)
 */

import { readFileSync } from 'fs';
import { createHash } from 'crypto';
import Parser from 'tree-sitter';
import Python from 'tree-sitter-python';
import JavaScript from 'tree-sitter-javascript';
import { parse as babelParse } from '@babel/parser';
import traverse from '@babel/traverse';
import { detectLanguage } from './language-detector.js';
import { extractFunctionMetadata } from './function-source-extractor.js';

/**
 * Initialize parsers for each language
 */
const parsers = {
  python: () => {
    const parser = new Parser();
    parser.setLanguage(Python);
    return parser;
  },
  javascript: () => {
    const parser = new Parser();
    parser.setLanguage(JavaScript);
    return parser;
  }
};

/**
 * Parse file and extract functions based on language
 * @param {string} filePath - Path to file
 * @param {object} options - Parsing options
 * @returns {object} Parsed functions and metadata
 */
export function parseFile(filePath, options = {}) {
  const language = detectLanguage(filePath);

  if (!language) {
    throw new Error(`Unsupported file type: ${filePath}`);
  }

  const sourceCode = readFileSync(filePath, 'utf8');

  // Use Babel for JavaScript/TypeScript for better analysis
  if (language === 'javascript' || language === 'typescript') {
    return parseJavaScriptBabel(filePath, sourceCode, options);
  }

  // Use tree-sitter for Python
  if (language === 'python') {
    return parsePython(filePath, sourceCode, options);
  }

  throw new Error(`Parser not implemented for language: ${language}`);
}

/**
 * Parse JavaScript using Babel (existing implementation)
 * @param {string} filePath - Path to file
 * @param {string} sourceCode - Source code
 * @param {object} options - Parsing options
 * @returns {object} Parsed data
 */
function parseJavaScriptBabel(filePath, sourceCode, options = {}) {
  const functions = [];
  const callGraph = new Map();
  const sideEffects = new Map();

  try {
    // Parse with Babel
    const ast = babelParse(sourceCode, {
      sourceType: 'module',
      plugins: ['jsx', 'typescript']
    });

    // First pass: collect all functions
    traverse.default(ast, {
      FunctionDeclaration(path) {
        const metadata = extractFunctionMetadata(path, sourceCode, filePath);
        functions.push({ metadata, path });
        callGraph.set(metadata.id, []);
        sideEffects.set(metadata.id, []);
      },

      VariableDeclarator(path) {
        if (path.node.init?.type === 'ArrowFunctionExpression' ||
            path.node.init?.type === 'FunctionExpression') {
          const metadata = extractFunctionMetadata(path, sourceCode, filePath);
          functions.push({ metadata, path });
          callGraph.set(metadata.id, []);
          sideEffects.set(metadata.id, []);
        }
      }
    });

    // Second pass: analyze function bodies
    functions.forEach(({ metadata, path }) => {
      path.traverse({
        CallExpression(callPath) {
          const callee = callPath.node.callee;
          let calledName = '';

          if (callee.type === 'Identifier') {
            calledName = callee.name;
          } else if (callee.type === 'MemberExpression') {
            const obj = callee.object.name || '';
            const prop = callee.property.name || '';
            calledName = obj ? `${obj}.${prop}` : prop;
          }

          if (calledName) {
            callGraph.get(metadata.id).push(calledName);

            // Detect side effects (JavaScript patterns)
            const effects = sideEffects.get(metadata.id);
            if (/read|write|append|unlink|mkdir|rmdir|fs\./i.test(calledName)) {
              effects.push({ type: 'file_io', at: calledName });
            }
            if (/fetch|request|axios|http|socket/i.test(calledName)) {
              effects.push({ type: 'network', at: calledName });
            }
            if (/console\.|log\.|logger\./i.test(calledName)) {
              effects.push({ type: 'logging', at: calledName });
            }
            if (/query|execute|find|findOne|save|insert|update|delete|collection|db\./i.test(calledName)) {
              effects.push({ type: 'database', at: calledName });
            }
            if (/querySelector|getElementById|createElement|appendChild/i.test(calledName)) {
              effects.push({ type: 'dom', at: calledName });
            }
          }
        }
      });
    });

    // Build result
    const entries = functions.map(({ metadata }) => {
      const calls = callGraph.get(metadata.id) || [];
      const effects = sideEffects.get(metadata.id) || [];

      const uniqueCalls = [...new Set(calls)].filter(c => c !== metadata.name);
      const uniqueEffects = effects.reduce((acc, e) => {
        const key = `${e.type}:${e.at}`;
        if (!acc.has(key)) {
          acc.set(key, e);
        }
        return acc;
      }, new Map());

      return {
        id: metadata.name,
        name: metadata.name,
        type: 'function',
        file: filePath,
        line: metadata.line,
        endLine: metadata.endLine,
        params: metadata.isAsync ? '(async)' : '()',
        async: metadata.isAsync,
        calls: uniqueCalls.slice(0, 10),
        effects: Array.from(uniqueEffects.values()).map(e => e.type),
        scipDoc: '',
        hash: metadata.hash,
        source: options.includeSource ? metadata.source : undefined
      };
    });

    return {
      language: 'javascript',
      functions: entries,
      count: entries.length
    };

  } catch (error) {
    console.log(`Warning: Could not parse ${filePath}: ${error.message}`);
    return {
      language: 'javascript',
      functions: [],
      count: 0,
      error: error.message
    };
  }
}

/**
 * Parse Python using tree-sitter
 * @param {string} filePath - Path to file
 * @param {string} sourceCode - Source code
 * @param {object} options - Parsing options
 * @returns {object} Parsed data
 */
function parsePython(filePath, sourceCode, options = {}) {
  const parser = parsers.python();
  const tree = parser.parse(sourceCode);

  const functions = [];
  const lines = sourceCode.split('\n');

  // Find all function definitions
  const functionNodes = tree.rootNode.descendantsOfType('function_definition');

  functionNodes.forEach(node => {
    try {
      // Get function name
      const nameNode = node.childForFieldName('name');
      const funcName = nameNode ? nameNode.text : 'anonymous';

      // Get parameters
      const paramsNode = node.childForFieldName('parameters');
      const params = paramsNode ? extractPythonParams(paramsNode) : [];

      // Get line numbers
      const startLine = node.startPosition.row + 1;
      const endLine = node.endPosition.row + 1;

      // Extract source code
      const funcSource = lines.slice(startLine - 1, endLine).join('\n');

      // Extract function calls
      const calls = extractPythonCalls(node);

      // Detect side effects
      const effects = detectPythonSideEffects(node, sourceCode);

      // Check if async
      const isAsync = node.text.startsWith('async def') || node.text.includes('async def');

      // Compute hash
      const normalized = funcSource.replace(/\s+/g, ' ').trim();
      const hash = createHash('md5').update(normalized).digest('hex');

      functions.push({
        id: funcName,
        name: funcName,
        type: 'function',
        file: filePath,
        line: startLine,
        endLine: endLine,
        params: `(${params.join(', ')})`,
        async: isAsync,
        calls: [...new Set(calls)].slice(0, 10),
        effects,
        scipDoc: '',
        hash,
        source: options.includeSource ? funcSource : undefined
      });

    } catch (error) {
      console.log(`Warning: Error parsing function in ${filePath}: ${error.message}`);
    }
  });

  return {
    language: 'python',
    functions,
    count: functions.length
  };
}

/**
 * Extract parameter names from Python parameters node
 * @param {object} paramsNode - Tree-sitter parameters node
 * @returns {string[]} Parameter names
 */
function extractPythonParams(paramsNode) {
  const params = [];

  paramsNode.namedChildren.forEach(child => {
    if (child.type === 'identifier') {
      params.push(child.text);
    } else if (child.type === 'typed_parameter' || child.type === 'default_parameter') {
      const nameNode = child.childForFieldName('name');
      if (nameNode) {
        params.push(nameNode.text);
      }
    }
  });

  return params.filter(p => p !== 'self' && p !== 'cls'); // Filter common Python params
}

/**
 * Extract function calls from Python function node
 * @param {object} functionNode - Tree-sitter function node
 * @returns {string[]} Called function names
 */
function extractPythonCalls(functionNode) {
  const calls = [];

  const callNodes = functionNode.descendantsOfType('call');

  callNodes.forEach(callNode => {
    const funcNode = callNode.childForFieldName('function');
    if (funcNode) {
      // Handle simple calls: func()
      if (funcNode.type === 'identifier') {
        calls.push(funcNode.text);
      }
      // Handle attribute calls: obj.method()
      else if (funcNode.type === 'attribute') {
        const obj = funcNode.childForFieldName('object');
        const attr = funcNode.childForFieldName('attribute');
        if (obj && attr) {
          calls.push(`${obj.text}.${attr.text}`);
        }
      }
    }
  });

  return calls;
}

/**
 * Detect side effects in Python code
 * @param {object} functionNode - Tree-sitter function node
 * @param {string} sourceCode - Full source code
 * @returns {string[]} Side effect types
 */
function detectPythonSideEffects(functionNode, sourceCode) {
  const effects = new Set();
  const funcText = functionNode.text;

  // File I/O patterns
  if (/\bopen\(|\.read\(|\.write\(|os\.path|pathlib|shutil/i.test(funcText)) {
    effects.add('file_io');
  }

  // Network patterns
  if (/\brequests\.|urllib|http\.|socket|aiohttp|httpx/i.test(funcText)) {
    effects.add('network');
  }

  // Database patterns
  if (/\bsqlite3|psycopg|pymongo|execute\(|cursor\.|\.query\(|\.save\(/i.test(funcText)) {
    effects.add('database');
  }

  // Logging patterns
  if (/\blogging\.|\.log\(|\.debug\(|\.info\(|\.warning\(|\.error\(|print\(/i.test(funcText)) {
    effects.add('logging');
  }

  // Global/nonlocal mutations
  if (/\bglobal\s+\w+|nonlocal\s+\w+/i.test(funcText)) {
    effects.add('state_mutation');
  }

  return Array.from(effects);
}

/**
 * Extract functions from file (convenience wrapper)
 * @param {string} filePath - Path to file
 * @param {boolean} includeSource - Whether to include source code
 * @returns {object[]} Array of function entries
 */
export function extractFunctions(filePath, includeSource = false) {
  const result = parseFile(filePath, { includeSource });
  return result.functions;
}
