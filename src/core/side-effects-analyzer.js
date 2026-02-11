/**
 * Side Effects Analyzer - AST-based side effect detection
 *
 * Replaces fragile regex-based detection with import-aware AST analysis.
 * Features:
 * - Import/require tracking
 * - Language-specific patterns
 * - Confidence scoring
 * - Multi-language support
 */

import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// Load effect patterns
let EFFECT_PATTERNS;
try {
  const patternsPath = join(__dirname, '../../data/effect-patterns.json');
  EFFECT_PATTERNS = JSON.parse(readFileSync(patternsPath, 'utf-8'));
} catch (error) {
  console.error('Failed to load effect patterns:', error.message);
  EFFECT_PATTERNS = {};
}

/**
 * Side Effect Analyzer class
 */
export class SideEffectAnalyzer {
  /**
   * @param {string} language - Language name
   * @param {Array<string>} imports - Imported modules/packages
   */
  constructor(language, imports = []) {
    this.language = language;
    this.imports = new Set(imports);
    this.patterns = EFFECT_PATTERNS[language] || {};
  }

  /**
   * Analyze a function for side effects
   * @param {Array<string>} calls - Function calls found in the function
   * @param {string} functionSource - Function source code (for pattern matching)
   * @returns {Array<{type: string, at: string, confidence: string, line?: number}>} Detected side effects
   */
  analyze(calls, functionSource = '') {
    const effects = [];

    // Analyze each call
    for (const call of calls) {
      const callEffects = this.analyzeCall(call, functionSource);
      effects.push(...callEffects);
    }

    // Deduplicate effects (same type + at)
    return this.deduplicateEffects(effects);
  }

  /**
   * Analyze a single function call for side effects
   * @param {string} call - Function/method call name
   * @param {string} context - Surrounding source code for context
   * @returns {Array<object>} Detected effects
   * @private
   */
  analyzeCall(call, context = '') {
    const effects = [];
    const effectTypes = ['file_io', 'network', 'logging', 'database', 'dom', 'mutation'];

    for (const effectType of effectTypes) {
      const patterns = this.patterns[effectType];
      if (!patterns) continue;

      const confidence = this.detectEffect(call, patterns, context);
      if (confidence) {
        effects.push({
          type: effectType,
          at: call,
          confidence: confidence
        });
      }
    }

    return effects;
  }

  /**
   * Detect if a call matches an effect pattern
   * @param {string} call - Function call name
   * @param {object} patterns - Effect patterns for this type
   * @param {string} context - Source code context
   * @returns {string|null} Confidence level ('high', 'medium', 'low') or null
   * @private
   */
  detectEffect(call, patterns, context) {
    // High confidence: Direct import match
    if (patterns.imports && this.hasImport(patterns.imports)) {
      if (this.matchesCall(call, patterns.calls)) {
        return 'high';
      }
    }

    // High confidence: Built-in/global function
    if (patterns.builtins && patterns.builtins.some(b => call.includes(b))) {
      return 'high';
    }

    if (patterns.globals && patterns.globals.some(g => context.includes(g))) {
      return 'high';
    }

    // Medium confidence: Direct call match (no import verification)
    if (patterns.calls && this.matchesCall(call, patterns.calls)) {
      return 'medium';
    }

    // Medium confidence: Namespace match (e.g., fs.readFile, console.log)
    if (patterns.namespaces && patterns.namespaces.some(ns => call.startsWith(ns + '.'))) {
      return 'medium';
    }

    // Low confidence: Pattern/regex match
    if (patterns.patterns && this.matchesPattern(call, patterns.patterns)) {
      return 'low';
    }

    // C/C++ includes
    if ((patterns.includes || patterns.requires || patterns.commands)) {
      // These are matched differently, return medium confidence if call matches
      if (patterns.calls && this.matchesCall(call, patterns.calls)) {
        return 'medium';
      }
    }

    return null;
  }

  /**
   * Check if any of the required imports are present
   * @param {Array<string>} requiredImports - Required import names
   * @returns {boolean} True if at least one import matches
   * @private
   */
  hasImport(requiredImports) {
    for (const required of requiredImports) {
      for (const imported of this.imports) {
        // Exact match or starts with (for sub-modules)
        if (imported === required || imported.startsWith(required + '/')) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Check if call matches any of the expected calls
   * @param {string} call - Function call name
   * @param {Array<string>} expectedCalls - Expected call names
   * @returns {boolean} True if matches
   * @private
   */
  matchesCall(call, expectedCalls) {
    if (!expectedCalls) return false;

    for (const expected of expectedCalls) {
      // Exact match
      if (call === expected) return true;

      // Method call (e.g., fs.readFile matches readFile)
      if (call.includes('.' + expected)) return true;

      // Partial match (e.g., readFileSync matches readFile)
      if (call.includes(expected)) return true;
    }

    return false;
  }

  /**
   * Check if call matches any regex pattern
   * @param {string} call - Function call name
   * @param {Array<string>} patterns - Regex pattern strings
   * @returns {boolean} True if matches
   * @private
   */
  matchesPattern(call, patterns) {
    if (!patterns) return false;

    for (const pattern of patterns) {
      try {
        const regex = new RegExp(pattern, 'i');
        if (regex.test(call)) return true;
      } catch (error) {
        // Invalid regex, skip
        console.warn(`Invalid pattern: ${pattern}`);
      }
    }

    return false;
  }

  /**
   * Deduplicate effects (same type + at)
   * @param {Array<object>} effects - Effects to deduplicate
   * @returns {Array<object>} Deduplicated effects
   * @private
   */
  deduplicateEffects(effects) {
    const seen = new Map();

    for (const effect of effects) {
      const key = `${effect.type}:${effect.at}`;

      if (!seen.has(key)) {
        seen.set(key, effect);
      } else {
        // Keep higher confidence
        const existing = seen.get(key);
        const confidenceOrder = { high: 3, medium: 2, low: 1 };

        if (confidenceOrder[effect.confidence] > confidenceOrder[existing.confidence]) {
          seen.set(key, effect);
        }
      }
    }

    return Array.from(seen.values());
  }

  /**
   * Add an import to the analyzer
   * @param {string} importPath - Import path
   */
  addImport(importPath) {
    this.imports.add(importPath);
  }

  /**
   * Get all detected effect types
   * @param {Array<object>} effects - Effects array
   * @returns {Array<string>} Unique effect types
   */
  static getEffectTypes(effects) {
    return [...new Set(effects.map(e => e.type))];
  }

  /**
   * Filter effects by confidence level
   * @param {Array<object>} effects - Effects array
   * @param {string} minConfidence - Minimum confidence ('low', 'medium', 'high')
   * @returns {Array<object>} Filtered effects
   */
  static filterByConfidence(effects, minConfidence = 'low') {
    const confidenceOrder = { low: 1, medium: 2, high: 3 };
    const minLevel = confidenceOrder[minConfidence] || 1;

    return effects.filter(e => confidenceOrder[e.confidence] >= minLevel);
  }

  /**
   * Format effects for output (backward compatible)
   * @param {Array<object>} effects - Effects with confidence
   * @returns {Array<object>} Formatted effects [{type, at}]
   */
  static formatForOutput(effects) {
    return effects.map(e => ({
      type: e.type,
      at: e.at
    }));
  }
}

/**
 * Create a side effect analyzer for a language
 * @param {string} language - Language name
 * @param {Array<string>} imports - Imported modules
 * @returns {SideEffectAnalyzer} Analyzer instance
 */
export function createAnalyzer(language, imports = []) {
  return new SideEffectAnalyzer(language, imports);
}

/**
 * Quick analysis helper
 * @param {string} language - Language name
 * @param {Array<string>} calls - Function calls
 * @param {Array<string>} imports - Imports
 * @param {string} functionSource - Function source code
 * @returns {Array<object>} Detected side effects
 */
export function analyzeSideEffects(language, calls, imports = [], functionSource = '') {
  const analyzer = new SideEffectAnalyzer(language, imports);
  return analyzer.analyze(calls, functionSource);
}

export default SideEffectAnalyzer;
