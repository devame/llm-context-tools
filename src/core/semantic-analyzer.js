/**
 * Semantic Analyzer
 *
 * Analyzes code comments and strings for semantic meaning and tags.
 * Helps identify patterns like:
 * - Debouncing/Throttling
 * - Animations
 * - Performance optimizations
 * - Hacks/TODOs
 * - React Hooks usage
 */

const SEMANTIC_PATTERNS = [
  // Timing / Performance
  { tag: 'debounce', regex: /debounce/i },
  { tag: 'throttle', regex: /throttle/i },
  { tag: 'batch', regex: /batch/i },
  { tag: 'cache', regex: /cache|memoiz/i },
  { tag: 'optimization', regex: /optimiz|perf/i },

  // UI / Animation
  { tag: 'animation', regex: /animat|transition/i },
  { tag: 'scroll', regex: /scroll/i },
  { tag: 'layout', regex: /layout|reflow/i },
  { tag: 'render', regex: /render|paint/i },

  // State / Effects
  { tag: 'state-mutation', regex: /mutat|setstate|reset!|swap!/i },
  { tag: 'side-effect', regex: /side-effect|api call|fetch/i },
  { tag: 'event-handler', regex: /handle|on[A-Z]/ },

  // Code Quality
  { tag: 'hack', regex: /hack|workaround|fixme/i },
  { tag: 'todo', regex: /todo/i },
  { tag: 'deprecated', regex: /deprecated/i },

  // React specific
  { tag: 'react-hook', regex: /use[A-Z]/ },
  { tag: 'context', regex: /provider|context/i }
];

export function createSemanticAnalyzer(language) {
  return {
    analyze(sourceCode) {
      if (!sourceCode) return [];

      const tags = new Set();

      // Simple regex scan over source (comments are part of source)
      // For more precision, we could only scan comment nodes, but scanning
      // the whole function source is a good heuristic for "semantic relevance".

      for (const pattern of SEMANTIC_PATTERNS) {
        if (pattern.regex.test(sourceCode)) {
          tags.add(pattern.tag);
        }
      }

      return Array.from(tags);
    }
  };
}
