#!/usr/bin/env sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RELEASE_DIR=${1:-"$PROJECT_DIR/dist"}
TEST_DIR=$(mktemp -d "${TMPDIR:-/tmp}/llm-context-semantic-test.XXXXXX")
INSTALL_DIR="$TEST_DIR/bin"
MODEL_CACHE="$TEST_DIR/models"
FIXTURE="$TEST_DIR/project"

cleanup() {
  if [ -x "$INSTALL_DIR/llm-context" ] && [ -d "$FIXTURE" ]; then
    LLM_CONTEXT_MODEL_CACHE="$MODEL_CACHE" \
      "$INSTALL_DIR/llm-context" -C "$FIXTURE" service stop \
      >/dev/null 2>&1 || true
  fi
  rm -rf -- "$TEST_DIR"
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$FIXTURE/src"
printf '%s\n' \
  '(ns auth.core)' \
  '(defn authenticate-user [token]' \
  '  (when (seq token) {:user/id 42}))' \
  >"$FIXTURE/src/auth.clj"

LLM_CONTEXT_RELEASE_URL="file://$RELEASE_DIR" \
LLM_CONTEXT_INSTALL_DIR="$INSTALL_DIR" \
LLM_CONTEXT_MODEL_CACHE="$MODEL_CACHE" \
LLM_CONTEXT_SKIP_PATH_UPDATE=1 \
  sh "$PROJECT_DIR/install.sh"

LLM_CONTEXT_MODEL_CACHE="$MODEL_CACHE" \
  "$INSTALL_DIR/llm-context" -C "$FIXTURE" init --yes
LLM_CONTEXT_MODEL_CACHE="$MODEL_CACHE" \
  "$INSTALL_DIR/llm-context" -C "$FIXTURE" service start
LLM_CONTEXT_MODEL_CACHE="$MODEL_CACHE" \
  "$INSTALL_DIR/llm-context" -C "$FIXTURE" semantic sync --wait

RESULT=$(LLM_CONTEXT_MODEL_CACHE="$MODEL_CACHE" \
  "$INSTALL_DIR/llm-context" -C "$FIXTURE" query search \
  "where is user authentication handled?")
printf '%s\n' "$RESULT" | grep -F ':lateon' >/dev/null

LLM_CONTEXT_MODEL_CACHE="$MODEL_CACHE" \
  "$INSTALL_DIR/llm-context" -C "$FIXTURE" doctor
