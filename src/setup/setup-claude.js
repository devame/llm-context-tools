#!/usr/bin/env node
/**
 * Setup Claude Code Integration for llm-context-tools
 *
 * Installs hooks that automatically:
 * - Inject context at session start (SessionStart hook)
 * - Run incremental analysis before compaction (PreCompact hook)
 *
 * Usage:
 *   llm-context setup-claude           # Install hooks
 *   llm-context setup-claude --check   # Verify installation
 *   llm-context setup-claude --remove  # Uninstall hooks
 */

import { existsSync, mkdirSync, writeFileSync, readFileSync, unlinkSync } from 'fs';
import { join } from 'path';
import { homedir } from 'os';
import { execSync } from 'child_process';

const HOOKS_DIR = join(homedir(), '.claude', 'hooks');
const SESSION_START_HOOK = join(HOOKS_DIR, 'session-start.sh');
const PRE_COMPACT_HOOK = join(HOOKS_DIR, 'pre-compact.sh');

// Hook contents
const SESSION_START_CONTENT = `#!/bin/bash
# llm-context-tools: Inject context at session start
# This hook provides LLM-optimized codebase context automatically

if [ -d .llm-context ]; then
  echo "📊 Loading llm-context analysis..."
  llm-context prime 2>/dev/null || true
fi
`;

const PRE_COMPACT_CONTENT = `#!/bin/bash
# llm-context-tools: Run incremental analysis before compaction
# This ensures context is up-to-date before conversation history is compacted

if [ -d .llm-context ]; then
  echo "🔄 Running incremental analysis..."
  llm-context analyze --quiet 2>/dev/null || true
fi
`;

/**
 * Check if hooks are installed
 */
function checkInstallation() {
  const sessionStartExists = existsSync(SESSION_START_HOOK);
  const preCompactExists = existsSync(PRE_COMPACT_HOOK);

  console.log('\\n=== llm-context-tools Claude Code Integration ===\\n');

  if (sessionStartExists && preCompactExists) {
    console.log('✅ Hooks installed correctly');
    console.log('   SessionStart:', SESSION_START_HOOK);
    console.log('   PreCompact:', PRE_COMPACT_HOOK);

    // Check if they contain our content
    const sessionContent = readFileSync(SESSION_START_HOOK, 'utf-8');
    const compactContent = readFileSync(PRE_COMPACT_HOOK, 'utf-8');

    if (sessionContent.includes('llm-context prime') &&
        compactContent.includes('llm-context analyze')) {
      console.log('\\n✅ Hook contents verified');
      return true;
    } else {
      console.log('\\n⚠️  Hook files exist but have different content');
      console.log('   Run: llm-context setup-claude --force to overwrite');
      return false;
    }
  } else {
    console.log('❌ Hooks not installed');
    console.log('   Run: llm-context setup-claude');
    return false;
  }
}

/**
 * Install hooks
 */
function installHooks(force = false) {
  console.log('\\n=== Installing Claude Code Hooks ===\\n');

  // Create hooks directory if needed
  if (!existsSync(HOOKS_DIR)) {
    console.log('Creating hooks directory:', HOOKS_DIR);
    mkdirSync(HOOKS_DIR, { recursive: true });
  }

  // Check if hooks already exist
  if ((existsSync(SESSION_START_HOOK) || existsSync(PRE_COMPACT_HOOK)) && !force) {
    console.log('⚠️  Hooks already exist. Options:');
    console.log('   1. Run with --force to overwrite');
    console.log('   2. Run with --check to verify installation');
    console.log('   3. Manually merge with existing hooks');
    console.log('\\nExisting hooks:');
    if (existsSync(SESSION_START_HOOK)) {
      console.log('   -', SESSION_START_HOOK);
    }
    if (existsSync(PRE_COMPACT_HOOK)) {
      console.log('   -', PRE_COMPACT_HOOK);
    }
    return false;
  }

  // Install SessionStart hook
  console.log('Installing SessionStart hook...');
  writeFileSync(SESSION_START_HOOK, SESSION_START_CONTENT, { mode: 0o755 });
  console.log('✅', SESSION_START_HOOK);

  // Install PreCompact hook
  console.log('Installing PreCompact hook...');
  writeFileSync(PRE_COMPACT_HOOK, PRE_COMPACT_CONTENT, { mode: 0o755 });
  console.log('✅', PRE_COMPACT_HOOK);

  console.log('\\n✅ Claude Code integration installed!\\n');
  console.log('What happens now:');
  console.log('  • SessionStart: Injects .llm-context/ summary at session start');
  console.log('  • PreCompact: Runs incremental analysis before compaction');
  console.log('\\nNext steps:');
  console.log('  1. Run: llm-context analyze (in your project)');
  console.log('  2. Restart Claude Code');
  console.log('  3. Context will auto-inject on session start!\\n');

  return true;
}

/**
 * Remove hooks
 */
function removeHooks() {
  console.log('\\n=== Removing Claude Code Hooks ===\\n');

  let removed = false;

  if (existsSync(SESSION_START_HOOK)) {
    const content = readFileSync(SESSION_START_HOOK, 'utf-8');
    if (content.includes('llm-context prime')) {
      unlinkSync(SESSION_START_HOOK);
      console.log('✅ Removed:', SESSION_START_HOOK);
      removed = true;
    } else {
      console.log('⚠️  SessionStart hook exists but is not from llm-context-tools');
      console.log('   Skipping removal (manual cleanup required)');
    }
  }

  if (existsSync(PRE_COMPACT_HOOK)) {
    const content = readFileSync(PRE_COMPACT_HOOK, 'utf-8');
    if (content.includes('llm-context analyze')) {
      unlinkSync(PRE_COMPACT_HOOK);
      console.log('✅ Removed:', PRE_COMPACT_HOOK);
      removed = true;
    } else {
      console.log('⚠️  PreCompact hook exists but is not from llm-context-tools');
      console.log('   Skipping removal (manual cleanup required)');
    }
  }

  if (!removed) {
    console.log('No llm-context-tools hooks found to remove');
  }

  console.log();
}

/**
 * Main
 */
function main() {
  const args = process.argv.slice(2);
  const hasCheck = args.includes('--check');
  const hasRemove = args.includes('--remove');
  const hasForce = args.includes('--force');

  if (hasCheck) {
    checkInstallation();
  } else if (hasRemove) {
    removeHooks();
  } else {
    installHooks(hasForce);
  }
}

main();
