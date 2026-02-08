/**
 * Semantic Analyzer - Pattern-based code tagging
 *
 * Adds semantic tags to functions based on regex patterns defined in
 * semantic-patterns.json.
 */

import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Load semantic patterns
let SEMANTIC_PATTERNS;
try {
  const patternsPath = join(__dirname, '../../data/semantic-patterns.json');
  SEMANTIC_PATTERNS = JSON.parse(readFileSync(patternsPath, 'utf-8'));
} catch (error) {
  console.warn('Failed to load semantic patterns:', error.message);
  SEMANTIC_PATTERNS = {};
}

/**
 * Semantic Analyzer class
 */
export class SemanticAnalyzer {
  /**
   * @param {string} language - Language name
   */
  constructor(language) {
    this.language = language;
    this.patterns = SEMANTIC_PATTERNS[language] || {};
  }

  /**
   * Analyze function source for semantic tags
   * @param {string} source - Function source code
   * @returns {Array<string>} Detected tags
   */
  analyze(source) {
    const tags = [];

    for (const [tag, config] of Object.entries(this.patterns)) {
      if (this.matchesTag(source, config)) {
        tags.push(tag);
      }
    }

    return tags;
  }

  /**
   * Check if source matches a tag configuration
   * @param {string} source - Source code
   * @param {object} config - Tag configuration {patterns, antiPatterns}
   * @returns {boolean} True if matches
   */
  matchesTag(source, config) {
    // Check anti-patterns first
    if (config.antiPatterns) {
      for (const pattern of config.antiPatterns) {
        if (new RegExp(pattern, 'm').test(source)) {
          return false;
        }
      }
    }

    // Check positive patterns
    if (config.patterns) {
      for (const pattern of config.patterns) {
        if (new RegExp(pattern, 'm').test(source)) {
          return true;
        }
      }
    }

    return false;
  }
}

/**
 * Create a semantic analyzer for a language
 * @param {string} language - Language name
 * @returns {SemanticAnalyzer} Analyzer instance
 */
export function createSemanticAnalyzer(language) {
  return new SemanticAnalyzer(language);
}

export default SemanticAnalyzer;
