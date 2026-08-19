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
LLM_CONTEXT_ACCELERATOR_PACKAGE=cpu \
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

DOCTOR_OUTPUT=""
DOCTOR_STATUS=0
if DOCTOR_OUTPUT=$(LLM_CONTEXT_MODEL_CACHE="$MODEL_CACHE" \
  "$INSTALL_DIR/llm-context" -C "$FIXTURE" doctor 2>&1); then
  :
else
  DOCTOR_STATUS=$?
fi
printf '%s\n' "$DOCTOR_OUTPUT"

# Release CI intentionally runs on ordinary Linux runners without a CUDA
# device.  In that environment doctor correctly reports the project as
# degraded because semantic inference fell back to CPU, even though the
# runtime, models, worker, and indexed queue are all healthy.  Accept only
# that bounded capability warning; any indexing, model, service, or other
# doctor failure remains a release failure.
if [ "$DOCTOR_STATUS" -ne 0 ]; then
  printf '%s\n' "$DOCTOR_OUTPUT" |
    grep -E '^warn semantic-accelerator cpu/' >/dev/null
  printf '%s\n' "$DOCTOR_OUTPUT" |
    grep -F 'warn cuda-host GPU: not detected' >/dev/null
  printf '%s\n' "$DOCTOR_OUTPUT" |
    grep -F 'ok semantic-execution' >/dev/null
  printf '%s\n' "$DOCTOR_OUTPUT" |
    grep -E '^ok semantic-queue +2 indexed, 0 pending, 0 leased, 0 failed, 0 dirty' \
      >/dev/null
fi
