/**
 * .gitignore Parser
 *
 * Parses .gitignore files and provides pattern matching for file exclusion.
 * Supports common gitignore patterns including:
 * - Simple names (node_modules)
 * - Paths (dist/build)
 * - Wildcards (* and **)
 * - Directory indicators (trailing /)
 * - Negation (! prefix)
 * - Comments (#)
 */

import { readFileSync, existsSync } from 'fs';
import { join, relative, sep } from 'path';

/**
 * Smart default ignore patterns
 * These are common build artifacts and dependencies that should
 * be ignored even if .gitignore doesn't exist
 */
const DEFAULT_IGNORE_PATTERNS = [
  // Version control
  '.git',
  '.svn',
  '.hg',

  // Dependencies
  'node_modules',
  'vendor',
  'venv',
  '.venv',
  'virtualenv',
  '__pycache__',

  // Build artifacts
  'dist',
  'build',
  'target',
  'out',
  '.output',

  // Language-specific build dirs
  '.shadow-cljs',
  '.cljs_cache',
  '.cpcache',
  'elm-stuff',
  'jpm_tree',
  '.tox',
  '.pytest_cache',
  '.mypy_cache',

  // Tool-specific
  '.llm-context',
  '.deciduous',
  '.beads',

  // OS
  '.DS_Store',
  'Thumbs.db'
];

/**
 * Parse a .gitignore file
 */
export function parseGitignore(gitignorePath = '.gitignore') {
  const patterns = [...DEFAULT_IGNORE_PATTERNS];

  if (existsSync(gitignorePath)) {
    const content = readFileSync(gitignorePath, 'utf-8');
    const lines = content.split('\n');

    for (const line of lines) {
      const trimmed = line.trim();

      // Skip empty lines and comments
      if (!trimmed || trimmed.startsWith('#')) {
        continue;
      }

      patterns.push(trimmed);
    }
  }

  return createMatcher(patterns);
}

/**
 * Create a matcher function from patterns
 */
function createMatcher(patterns) {
  const rules = patterns.map(pattern => {
    const negated = pattern.startsWith('!');
    const cleanPattern = negated ? pattern.slice(1) : pattern;
    const isDirectory = cleanPattern.endsWith('/');
    const matchPattern = isDirectory ? cleanPattern.slice(0, -1) : cleanPattern;

    return {
      pattern: matchPattern,
      negated,
      isDirectory,
      regex: patternToRegex(matchPattern)
    };
  });

  /**
   * Test if a path should be ignored
   * @param {string} filePath - Path to test (relative or absolute)
   * @param {boolean} isDirectory - Whether the path is a directory
   * @returns {boolean} - true if the path should be ignored
   */
  return function isIgnored(filePath, isDirectory = false) {
    // Normalize path to use forward slashes
    const normalizedPath = filePath.replace(/\\/g, '/');
    const pathParts = normalizedPath.split('/').filter(p => p && p !== '.');

    let ignored = false;

    for (const rule of rules) {
      // Skip directory-specific rules if testing a file
      if (rule.isDirectory && !isDirectory) {
        continue;
      }

      // Test against full path
      if (rule.regex.test(normalizedPath)) {
        ignored = !rule.negated;
        continue;
      }

      // Test against any path segment (for patterns like "node_modules")
      for (const part of pathParts) {
        if (rule.regex.test(part)) {
          ignored = !rule.negated;
          break;
        }
      }

      // Test against relative segments (for patterns like "dist/build")
      for (let i = 0; i < pathParts.length; i++) {
        const segment = pathParts.slice(i).join('/');
        if (rule.regex.test(segment)) {
          ignored = !rule.negated;
          break;
        }
      }
    }

    return ignored;
  };
}

/**
 * Convert gitignore pattern to regex
 */
function patternToRegex(pattern) {
  let regex = pattern;

  // Escape special regex characters except * and ?
  regex = regex.replace(/[.+^${}()|[\]\\]/g, '\\$&');

  // Handle **/ (matches zero or more directories)
  regex = regex.replace(/\*\*\//g, '(?:.*\/)?');

  // Handle /** (matches all nested files/dirs)
  regex = regex.replace(/\/\*\*/g, '(?:\/.*)?');

  // Handle * (matches anything except /)
  regex = regex.replace(/\*/g, '[^/]*');

  // Handle ? (matches single character)
  regex = regex.replace(/\?/g, '[^/]');

  // Anchor the pattern
  // If pattern doesn't start with /, it can match anywhere
  if (!pattern.startsWith('/')) {
    regex = '(?:^|/)' + regex;
  } else {
    regex = '^' + regex.slice(1);
  }

  // If pattern doesn't end with /, add end anchor or allow trailing path
  if (!pattern.endsWith('*')) {
    regex = regex + '(?:$|/)';
  }

  return new RegExp(regex);
}

/**
 * Create a default ignore checker (uses .gitignore + defaults)
 */
export function createDefaultIgnoreChecker(gitignorePath = '.gitignore') {
  return parseGitignore(gitignorePath);
}
