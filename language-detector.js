/**
 * Language Detector - Detect programming language from file extension
 *
 * Purpose: Determine which parser to use based on file extension
 */

/**
 * Detect language from file path
 * @param {string} filePath - Path to file
 * @returns {string|null} Language identifier or null if unsupported
 */
export function detectLanguage(filePath) {
  const ext = filePath.split('.').pop().toLowerCase();

  const extensionMap = {
    // JavaScript/TypeScript
    'js': 'javascript',
    'jsx': 'javascript',
    'mjs': 'javascript',
    'cjs': 'javascript',
    'ts': 'typescript',
    'tsx': 'typescript',

    // Python
    'py': 'python',
    'pyw': 'python',
    'pyi': 'python',

    // Future languages
    // 'go': 'go',
    // 'rs': 'rust',
    // 'java': 'java',
  };

  return extensionMap[ext] || null;
}

/**
 * Check if file is supported
 * @param {string} filePath - Path to file
 * @returns {boolean} True if language is supported
 */
export function isSupported(filePath) {
  return detectLanguage(filePath) !== null;
}

/**
 * Get all supported extensions
 * @returns {string[]} Array of supported extensions
 */
export function getSupportedExtensions() {
  return ['js', 'jsx', 'mjs', 'cjs', 'ts', 'tsx', 'py', 'pyw', 'pyi'];
}

/**
 * Get supported languages
 * @returns {string[]} Array of supported language identifiers
 */
export function getSupportedLanguages() {
  return ['javascript', 'typescript', 'python'];
}

/**
 * Filter files by supported languages
 * @param {string[]} files - Array of file paths
 * @returns {string[]} Filtered array of supported files
 */
export function filterSupportedFiles(files) {
  return files.filter(isSupported);
}
