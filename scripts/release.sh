#!/usr/bin/env bash
set -euo pipefail

# The tag-triggered GitHub workflow performs the platform builds and publishes
# the assets. This script performs the local gates, pushes the committed main
# branch, creates the annotated tag, and optionally waits for that workflow.

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPOSITORY=${REPOSITORY:-devame/llm-context-tools}
REMOTE=${REMOTE:-origin}
BRANCH=${BRANCH:-main}
WAIT_FOR_RELEASE=1

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/release.sh check
  scripts/release.sh publish [--no-wait]

The publish command reads the version from src/llm_context/version.clj,
requires a clean main branch, runs scripts/build-release.sh, pushes main,
creates the matching annotated v<version> tag, and waits for the GitHub release
workflow unless --no-wait is supplied.

Environment overrides: REPOSITORY, REMOTE, BRANCH.
EOF
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "required command not found: $1" >&2
    exit 1
  }
}

version() {
  sed -n 's/^(def value "\(.*\)")$/\1/p' \
    "$ROOT_DIR/src/llm_context/version.clj"
}

wait_for_release_run() {
  local tag=$1
  local head_sha=$2
  local run_id=''
  local attempt

  for attempt in $(seq 1 30); do
    run_id=$(gh run list --repo "$REPOSITORY" --workflow release.yml \
      --limit 20 --json databaseId,headBranch,headSha \
      --jq "map(select(.headBranch == \"$tag\" and .headSha == \"$head_sha\")) | .[0].databaseId // empty")
    if [[ -n "$run_id" ]]; then
      break
    fi
    sleep 2
  done

  [[ -n "$run_id" ]] || {
    echo "could not find the release workflow for $tag" >&2
    echo "inspect: https://github.com/$REPOSITORY/actions" >&2
    exit 1
  }

  gh run watch "$run_id" --repo "$REPOSITORY" --exit-status
  gh release view "$tag" --repo "$REPOSITORY" \
    --json isDraft,isPrerelease,assets,url \
    --jq 'if .isDraft or .isPrerelease then error("release is not public") else "Published " + .url + " (" + ((.assets | length) | tostring) + " assets)" end'
}

if [[ $# -lt 1 ]]; then
  usage
  exit 2
fi

COMMAND=$1
shift

case "$COMMAND" in
  check)
    [[ $# -eq 0 ]] || { usage; exit 2; }
    exec "$ROOT_DIR/scripts/build-release.sh"
    ;;
  publish)
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --no-wait) WAIT_FOR_RELEASE=0 ;;
        *) usage; exit 2 ;;
      esac
      shift
    done
    ;;
  *)
    usage
    exit 2
    ;;
esac

for command in git gh; do
  require_command "$command"
done

cd "$ROOT_DIR"
VERSION=$(version)
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "invalid repository version: $VERSION" >&2
  exit 1
}
TAG="v$VERSION"

[[ "$(git branch --show-current)" == "$BRANCH" ]] || {
  echo "release must run from $BRANCH" >&2
  exit 1
}
[[ -z "$(git status --porcelain)" ]] || {
  echo "working tree must be clean before publishing" >&2
  exit 1
}

echo "==> Running local release gates"
scripts/build-release.sh

[[ -z "$(git status --porcelain)" ]] || {
  echo "release build changed tracked files; refusing to publish" >&2
  git status --short >&2
  exit 1
}

echo "==> Checking remote branch state"
git fetch --quiet "$REMOTE" "$BRANCH"
LOCAL_HEAD=$(git rev-parse HEAD)
REMOTE_HEAD=$(git rev-parse "$REMOTE/$BRANCH")
git merge-base --is-ancestor "$REMOTE_HEAD" HEAD || {
  echo "$REMOTE/$BRANCH contains commits not present locally; refusing to publish" >&2
  exit 1
}

if git rev-parse "$TAG^{commit}" >/dev/null 2>&1 || \
  git ls-remote --exit-code --tags "$REMOTE" "refs/tags/$TAG" >/dev/null 2>&1; then
  echo "tag already exists: $TAG" >&2
  exit 1
fi

echo "==> Pushing $BRANCH"
git push "$REMOTE" "$BRANCH"

echo "==> Creating and pushing $TAG"
git tag -a "$TAG" -m "Release $TAG"
git push "$REMOTE" "$TAG"

if [[ "$WAIT_FOR_RELEASE" == 1 ]]; then
  require_command gh
  wait_for_release_run "$TAG" "$LOCAL_HEAD"
else
  echo "Pushed $TAG; release workflow: https://github.com/$REPOSITORY/actions"
fi
