/**
 * Language Detector
 *
 * Scans project to identify primary programming languages before analysis.
 * Helps prevent analyzing wrong files (e.g., compiled JS instead of ClojureScript source).
 */

import { readdirSync, statSync } from 'fs';
import { join, extname } from 'path';
import { parseGitignore } from '../parser/gitignore-parser.js';
import { ParserFactory } from '../parser/parser-factory.js';

/**
 * Language metadata
 */
const LANGUAGE_INFO = {
  // Currently supported by tree-sitter
  '.js': { name: 'JavaScript', supported: true, isSource: true },
  '.jsx': { name: 'JavaScript (JSX)', supported: true, isSource: true },
  '.ts': { name: 'TypeScript', supported: true, isSource: true },
  '.tsx': { name: 'TypeScript (TSX)', supported: true, isSource: true },
  '.py': { name: 'Python', supported: true, isSource: true },
  '.go': { name: 'Go', supported: true, isSource: true },
  '.rs': { name: 'Rust', supported: true, isSource: true },
  '.java': { name: 'Java', supported: true, isSource: true },
  '.c': { name: 'C', supported: true, isSource: true },
  '.h': { name: 'C Header', supported: true, isSource: true },
  '.cpp': { name: 'C++', supported: true, isSource: true },
  '.cc': { name: 'C++', supported: true, isSource: true },
  '.cxx': { name: 'C++', supported: true, isSource: true },
  '.hpp': { name: 'C++ Header', supported: true, isSource: true },
  '.rb': { name: 'Ruby', supported: true, isSource: true },
  '.php': { name: 'PHP', supported: true, isSource: true },
  '.sh': { name: 'Bash', supported: true, isSource: true },
  '.bash': { name: 'Bash', supported: true, isSource: true },
  '.json': { name: 'JSON', supported: true, isSource: false },

  // Clojure/ClojureScript support
  '.clj': { name: 'Clojure', supported: true, isSource: true },
  '.cljs': { name: 'ClojureScript', supported: true, isSource: true },
  '.cljc': { name: 'Clojure/ClojureScript', supported: true, isSource: true },

  // Janet support
  '.janet': { name: 'Janet', supported: true, isSource: true },
  '.jdn': { name: 'Janet Data Notation', supported: true, isSource: true },

  // Common unsupported source languages
  '.elm': { name: 'Elm', supported: false, isSource: true },
  '.ex': { name: 'Elixir', supported: false, isSource: true },
  '.exs': { name: 'Elixir Script', supported: false, isSource: true },
  '.erl': { name: 'Erlang', supported: false, isSource: true },
  '.hrl': { name: 'Erlang Header', supported: false, isSource: true },
  '.hs': { name: 'Haskell', supported: false, isSource: true },
  '.scala': { name: 'Scala', supported: false, isSource: true },
  '.kt': { name: 'Kotlin', supported: false, isSource: true },
  '.swift': { name: 'Swift', supported: false, isSource: true },
  '.jl': { name: 'Julia', supported: false, isSource: true },
  '.r': { name: 'R', supported: false, isSource: true },
  '.ml': { name: 'OCaml', supported: false, isSource: true },
  '.mli': { name: 'OCaml Interface', supported: false, isSource: true },
  '.fs': { name: 'F#', supported: false, isSource: true },
  '.fsx': { name: 'F# Script', supported: false, isSource: true },
  '.rkt': { name: 'Racket', supported: false, isSource: true },
  '.scm': { name: 'Scheme', supported: false, isSource: true },
  '.lisp': { name: 'Common Lisp', supported: false, isSource: true },
  '.lua': { name: 'Lua', supported: false, isSource: true },
  '.v': { name: 'V/Verilog', supported: false, isSource: true },
  '.sv': { name: 'SystemVerilog', supported: false, isSource: true },
  '.vhd': { name: 'VHDL', supported: false, isSource: true },
  '.vhdl': { name: 'VHDL', supported: false, isSource: true },
  '.zig': { name: 'Zig', supported: false, isSource: true },
  '.nim': { name: 'Nim', supported: false, isSource: true },
  '.cr': { name: 'Crystal', supported: false, isSource: true },
  '.d': { name: 'D', supported: false, isSource: true },
  '.dart': { name: 'Dart', supported: false, isSource: true },
};

/**
 * Detect languages in a project
 */
export function detectLanguages(rootDir = '.') {
  const isIgnored = parseGitignore();
  const extensionCounts = new Map();
  const sampleFiles = new Map(); // Store sample files for each extension

  function walk(dir) {
    try {
      const entries = readdirSync(dir);

      for (const entry of entries) {
        const fullPath = join(dir, entry);
        const stat = statSync(fullPath);
        const isDirectory = stat.isDirectory();

        // Skip if ignored
        if (isIgnored(fullPath, isDirectory)) {
          continue;
        }

        if (isDirectory) {
          walk(fullPath);
        } else {
          const ext = extname(entry).toLowerCase();
          if (ext) {
            extensionCounts.set(ext, (extensionCounts.get(ext) || 0) + 1);

            // Store sample files (up to 3 per extension)
            if (!sampleFiles.has(ext)) {
              sampleFiles.set(ext, []);
            }
            const samples = sampleFiles.get(ext);
            if (samples.length < 3) {
              samples.push(fullPath);
            }
          }
        }
      }
    } catch (error) {
      // Skip inaccessible directories
    }
  }

  walk(rootDir);

  // Analyze results
  const languages = [];
  const supported = [];
  const unsupported = [];

  for (const [ext, count] of extensionCounts.entries()) {
    const info = LANGUAGE_INFO[ext];
    const languageEntry = {
      extension: ext,
      count,
      name: info?.name || 'Unknown',
      supported: info?.supported || false,
      isSource: info?.isSource ?? true,
      samples: sampleFiles.get(ext) || []
    };

    languages.push(languageEntry);

    if (languageEntry.isSource) {
      if (languageEntry.supported) {
        supported.push(languageEntry);
      } else if (info) {
        unsupported.push(languageEntry);
      }
    }
  }

  // Sort by count (descending)
  languages.sort((a, b) => b.count - a.count);
  supported.sort((a, b) => b.count - a.count);
  unsupported.sort((a, b) => b.count - a.count);

  return {
    all: languages,
    supported,
    unsupported,
    hasUnsupported: unsupported.length > 0,
    totalFiles: Array.from(extensionCounts.values()).reduce((sum, count) => sum + count, 0)
  };
}

/**
 * Print language detection report
 */
export function printLanguageReport(detection) {
  console.log('📊 Language Detection Results\n');
  console.log(`   Total files found: ${detection.totalFiles}\n`);

  if (detection.supported.length > 0) {
    console.log('✅ Supported Languages:');
    for (const lang of detection.supported) {
      console.log(`   ${lang.name.padEnd(25)} ${lang.count.toString().padStart(5)} files  (${lang.extension})`);
      if (lang.samples.length > 0) {
        console.log(`      Examples: ${lang.samples.slice(0, 2).join(', ')}`);
      }
    }
    console.log();
  }

  if (detection.unsupported.length > 0) {
    console.log('⚠️  Unsupported Languages (will be skipped):');
    for (const lang of detection.unsupported) {
      console.log(`   ${lang.name.padEnd(25)} ${lang.count.toString().padStart(5)} files  (${lang.extension})`);
      if (lang.samples.length > 0) {
        console.log(`      Examples: ${lang.samples.slice(0, 2).join(', ')}`);
      }
    }
    console.log();
    console.log('💡 These languages are not yet supported by tree-sitter parsers.');
    console.log('   To add support, install the appropriate tree-sitter parser.\n');
  }

  // Warn if unsupported languages are primary
  if (detection.unsupported.length > 0 && detection.supported.length === 0) {
    console.log('🚨 WARNING: No supported languages detected!');
    console.log('   The project appears to use only unsupported languages.');
    console.log('   Analysis will not produce useful results.\n');
    return false;
  }

  if (detection.unsupported.length > 0 && detection.supported.length > 0) {
    const unsupportedTotal = detection.unsupported.reduce((sum, l) => sum + l.count, 0);
    const supportedTotal = detection.supported.reduce((sum, l) => sum + l.count, 0);

    if (unsupportedTotal > supportedTotal) {
      console.log('⚠️  WARNING: Most source files are in unsupported languages!');
      console.log(`   Unsupported: ${unsupportedTotal} files vs Supported: ${supportedTotal} files`);
      console.log('   Analysis may miss the primary codebase.\n');
    }
  }

  return true;
}

/**
 * Check if project should be analyzed
 */
export function shouldAnalyze(detection) {
  // Don't analyze if no supported languages
  if (detection.supported.length === 0) {
    return false;
  }

  // Don't analyze if only supported files are JSON/config
  const nonConfigSupported = detection.supported.filter(l =>
    !['.json'].includes(l.extension)
  );

  return nonConfigSupported.length > 0;
}
