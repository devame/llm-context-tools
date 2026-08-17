#!/usr/bin/env bash
set -euo pipefail

# Run the repository-local release gates and prepare the Java/npm artifacts
# that do not depend on the platform-specific NextPlaid builds.

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DIST_DIR="$ROOT_DIR/dist"

usage() {
  cat >&2 <<'EOF'
Usage: scripts/build-release.sh

Runs the tests, builds the distribution JAR, verifies the packaged graph,
smoke-tests the Unix installer, checks the npm package, and writes local
checksums for the JAR and user guide into dist/.
EOF
}

if [[ $# -ne 0 ]]; then
  usage
  exit 2
fi

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "required command not found: $1" >&2
    exit 1
  }
}

for command in clojure java npm; do
  require_command "$command"
done

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d ' ' -f 1
  else
    echo "sha256sum or shasum is required" >&2
    exit 1
  fi
}

cd "$ROOT_DIR"

echo "==> Installing npm dependencies"
npm ci

echo "==> Running tests"
clojure -M:test

echo "==> Building distribution JAR"
clojure -T:build dist

echo "==> Verifying release quality"
scripts/verify-release-quality.sh "$DIST_DIR/llm-context.jar"

echo "==> Smoke-testing Unix installer"
test/install-unix.sh

echo "==> Checking npm package contents"
npm pack --dry-run

# npm's prepack hook rebuilds dist/, so checksums must be written after the
# package check rather than before it.
echo "==> Writing release checksums"
printf '%s  llm-context.jar\n' "$(sha256_file "$DIST_DIR/llm-context.jar")" \
  >"$DIST_DIR/llm-context.jar.sha256"
printf '%s  USER-GUIDE.md\n' "$(sha256_file "$DIST_DIR/USER-GUIDE.md")" \
  >"$DIST_DIR/USER-GUIDE.md.sha256"

echo "Release build prepared in $DIST_DIR"
