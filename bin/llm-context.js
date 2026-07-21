#!/usr/bin/env node

import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const packageRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const jar = join(packageRoot, 'dist', 'llm-context.jar');
const command = existsSync(jar) ? 'java' : 'clojure';
const args = existsSync(jar)
  ? ['--enable-native-access=ALL-UNNAMED', '-jar', jar, ...process.argv.slice(2)]
  : ['-M', '-m', 'llm-context.main', ...process.argv.slice(2)];

const result = spawnSync(command, args, {
  cwd: process.cwd(),
  stdio: 'inherit',
  shell: false,
});

if (result.error) {
  console.error(`Unable to launch ${command}: ${result.error.message}`);
  process.exit(1);
}

process.exit(result.status ?? 1);
