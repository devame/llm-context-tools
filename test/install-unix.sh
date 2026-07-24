#!/usr/bin/env sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TEST_DIR=$(mktemp -d "${TMPDIR:-/tmp}/llm-context-installer-test.XXXXXX")
cleanup() { rm -rf -- "$TEST_DIR"; }
trap cleanup EXIT HUP INT TERM

mkdir -p "$TEST_DIR/release" "$TEST_DIR/bin"
cp "$PROJECT_DIR/dist/llm-context.jar" "$TEST_DIR/release/llm-context.jar"
(cd "$TEST_DIR/release" && sha256sum llm-context.jar > llm-context.jar.sha256)

LLM_CONTEXT_RELEASE_URL="file://$TEST_DIR/release" \
LLM_CONTEXT_INSTALL_DIR="$TEST_DIR/bin" \
LLM_CONTEXT_SKIP_SEMANTIC=1 \
  sh "$PROJECT_DIR/install.sh"

test -x "$TEST_DIR/bin/llm-context"
test -f "$TEST_DIR/bin/llm-context.jar"
EXPECTED_VERSION=$(java --enable-native-access=ALL-UNNAMED \
  -jar "$PROJECT_DIR/dist/llm-context.jar" version)
test "$("$TEST_DIR/bin/llm-context" version)" = "$EXPECTED_VERSION"

INSTALLED_HASH=$(sha256sum "$TEST_DIR/bin/llm-context.jar" | sed 's/[[:space:]].*//')
printf '%064d  llm-context.jar\n' 0 >"$TEST_DIR/release/llm-context.jar.sha256"
if LLM_CONTEXT_RELEASE_URL="file://$TEST_DIR/release" \
   LLM_CONTEXT_INSTALL_DIR="$TEST_DIR/bin" \
   LLM_CONTEXT_SKIP_SEMANTIC=1 \
     sh "$PROJECT_DIR/install.sh"; then
  echo "installer accepted an invalid checksum" >&2
  exit 1
fi
test "$(sha256sum "$TEST_DIR/bin/llm-context.jar" | sed 's/[[:space:]].*//')" = \
  "$INSTALLED_HASH"
