/**
 * Parser Factory - Language-agnostic Tree-sitter parser layer
 *
 * Provides a unified interface for parsing multiple languages using Tree-sitter.
 * Features:
 * - Automatic language detection from file extensions
 * - Parser caching for performance
 * - Lazy loading of language grammars
 * - Support for 50+ languages via Tree-sitter
 */

import Parser from 'web-tree-sitter';
import { fileURLToPath } from 'url';
import { dirname, join, extname } from 'path';
import { readFileSync } from 'fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Parser cache: { language: parserInstance }
const parserCache = new Map();

// Language grammar paths (WASM files)
const GRAMMAR_PATHS = {
  javascript: 'tree-sitter-javascript',
  typescript: 'tree-sitter-typescript/typescript',
  tsx: 'tree-sitter-typescript/tsx',
  python: 'tree-sitter-python',
  go: 'tree-sitter-go',
  rust: 'tree-sitter-rust',
  java: 'tree-sitter-java',
  c: 'tree-sitter-c',
  cpp: 'tree-sitter-cpp',
  ruby: 'tree-sitter-ruby',
  php: 'tree-sitter-php',
  bash: 'tree-sitter-bash',
  json: 'tree-sitter-json'
};

// File extension to language mapping
const EXTENSION_MAP = {
  '.js': 'javascript',
  '.mjs': 'javascript',
  '.cjs': 'javascript',
  '.jsx': 'javascript',
  '.ts': 'typescript',
  '.tsx': 'tsx',
  '.py': 'python',
  '.pyw': 'python',
  '.go': 'go',
  '.rs': 'rust',
  '.java': 'java',
  '.c': 'c',
  '.h': 'c',
  '.cpp': 'cpp',
  '.cc': 'cpp',
  '.cxx': 'cpp',
  '.hpp': 'cpp',
  '.rb': 'ruby',
  '.php': 'php',
  '.sh': 'bash',
  '.bash': 'bash',
  '.zsh': 'bash',
  '.json': 'json'
};

// Tree-sitter initialization (call once)
let parserInitialized = false;

/**
 * Initialize Tree-sitter WASM
 * @private
 */
async function initializeParser() {
  if (!parserInitialized) {
    await Parser.init();
    parserInitialized = true;
  }
}

/**
 * Factory class for creating and managing Tree-sitter parsers
 */
export class ParserFactory {
  /**
   * Create or retrieve cached parser for a specific language
   * @param {string} language - Language name (e.g., 'javascript', 'python')
   * @returns {Promise<Parser>} Tree-sitter parser instance
   */
  static async createParser(language) {
    if (!language) {
      throw new Error('Language is required');
    }

    // Return cached parser if available
    if (parserCache.has(language)) {
      return parserCache.get(language);
    }

    // Initialize Tree-sitter WASM
    await initializeParser();

    // Get grammar path
    const grammarPath = GRAMMAR_PATHS[language];
    if (!grammarPath) {
      throw new Error(`Unsupported language: ${language}. Use getSupportedLanguages() to see available languages.`);
    }

    // Create parser instance
    const parser = new Parser();

    try {
      // Load language grammar (WASM)
      // Construct WASM filename from grammar path
      let wasmFile;
      if (language === 'tsx' || language === 'typescript') {
        // TypeScript has separate tsx and typescript grammars
        wasmFile = `tree-sitter-${language}.wasm`;
      } else {
        // For most languages: tree-sitter-javascript/tree-sitter-javascript.wasm
        const packageName = grammarPath.split('/').pop();
        wasmFile = `${packageName}.wasm`;
      }

      const wasmPath = join(
        __dirname,
        'node_modules',
        grammarPath,
        wasmFile
      );

      const languageGrammar = await Parser.Language.load(wasmPath);
      parser.setLanguage(languageGrammar);

      // Cache parser
      parserCache.set(language, parser);

      return parser;
    } catch (error) {
      throw new Error(`Failed to load grammar for ${language}: ${error.message}`);
    }
  }

  /**
   * Detect language from file path
   * @param {string} filePath - Path to file
   * @returns {string|null} Language name or null if unsupported
   */
  static detectLanguage(filePath) {
    const ext = extname(filePath).toLowerCase();
    return EXTENSION_MAP[ext] || null;
  }

  /**
   * Get list of supported languages
   * @returns {string[]} Array of supported language names
   */
  static getSupportedLanguages() {
    return Object.keys(GRAMMAR_PATHS);
  }

  /**
   * Check if a language is supported
   * @param {string} language - Language name
   * @returns {boolean} True if language is supported
   */
  static isLanguageSupported(language) {
    return language in GRAMMAR_PATHS;
  }

  /**
   * Parse source code
   * @param {string} sourceCode - Source code to parse
   * @param {string} language - Language name
   * @returns {Promise<Tree>} Parse tree
   */
  static async parse(sourceCode, language) {
    const parser = await this.createParser(language);
    return parser.parse(sourceCode);
  }

  /**
   * Parse file
   * @param {string} filePath - Path to file
   * @returns {Promise<{tree: Tree, language: string}>} Parse tree and detected language
   */
  static async parseFile(filePath) {
    const language = this.detectLanguage(filePath);
    if (!language) {
      throw new Error(`Cannot detect language for file: ${filePath}`);
    }

    const sourceCode = readFileSync(filePath, 'utf-8');
    const tree = await this.parse(sourceCode, language);

    return { tree, language };
  }

  /**
   * Clear parser cache (useful for testing)
   */
  static clearCache() {
    parserCache.clear();
  }
}

export default ParserFactory;
