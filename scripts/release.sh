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
  scripts/release.sh CURRENT_VERSION [--version-bump minor|major] [--no-wait]
  scripts/release.sh check
  scripts/release.sh publish [--no-wait]

The version form verifies CURRENT_VERSION against the repository, increments
the patch version by default, writes the current HEAD commit message to a
temporary notes.md, creates the release version/changelog commit, and then
publishes it. Use --version-bump minor or --version-bump major for a non-patch
release.

The publish command reads the version from src/llm_context/version.clj,
requires a clean main branch, runs scripts/build-release.sh, pushes main,
creates the matching annotated v<version> tag, and waits for the GitHub release
workflow unless --no-wait is supplied.

The tagged checkout must already contain the local x86_64 Linux native parser
library. Build it with scripts/install-dependencies.sh install --with-native
and commit that Linux artifact; the release workflow builds and overlays the
ARM Linux, macOS, and Windows variants from GitHub Actions.

Environment overrides: REPOSITORY, REMOTE, BRANCH.
EOF
}

prepare_and_publish() {
  local base_version=$1
  shift
  local bump_kind=patch
  local wait_args=()
  local current_version
  local new_version
  local major
  local minor
  local patch
  local notes_directory
  local notes_file

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --version-bump)
        [[ $# -ge 2 ]] || { usage; exit 2; }
        bump_kind=$2
        shift 2
        ;;
      --no-wait)
        wait_args+=(--no-wait)
        shift
        ;;
      *)
        usage
        exit 2
        ;;
    esac
  done

  [[ "$base_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    echo "invalid current version: $base_version" >&2
    exit 2
  }
  [[ "$bump_kind" == minor || "$bump_kind" == major || "$bump_kind" == patch ]] || {
    echo "--version-bump must be minor or major" >&2
    exit 2
  }

  cd "$ROOT_DIR"
  current_version=$(version)
  [[ "$current_version" == "$base_version" ]] || {
    echo "requested current version $base_version does not match repository version $current_version" >&2
    exit 1
  }
  [[ "$(git branch --show-current)" == "$BRANCH" ]] || {
    echo "release must run from $BRANCH" >&2
    exit 1
  }
  [[ -z "$(git status --porcelain)" ]] || {
    echo "working tree must be clean before preparing a release" >&2
    exit 1
  }

  IFS=. read -r major minor patch <<<"$base_version"
  major=$((10#$major))
  minor=$((10#$minor))
  patch=$((10#$patch))
  case "$bump_kind" in
    patch) patch=$((patch + 1)) ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    major) major=$((major + 1)); minor=0; patch=0 ;;
  esac
  new_version="$major.$minor.$patch"

  echo "==> Checking remote branch state"
  git fetch --quiet "$REMOTE" "$BRANCH"
  local remote_head
  remote_head=$(git rev-parse "$REMOTE/$BRANCH")
  git merge-base --is-ancestor "$remote_head" HEAD || {
    echo "$REMOTE/$BRANCH contains commits not present locally; refusing to prepare a release" >&2
    exit 1
  }
  if git rev-parse "v$new_version^{commit}" >/dev/null 2>&1 || \
    git ls-remote --exit-code --tags "$REMOTE" "refs/tags/v$new_version" >/dev/null 2>&1; then
    echo "tag already exists: v$new_version" >&2
    exit 1
  fi

  notes_directory=$(mktemp -d)
  notes_file="$notes_directory/notes.md"
  trap 'rm -rf "$notes_directory"' EXIT
  git log -1 --pretty=format:'- %s%n%n%b%n' >"$notes_file"
  [[ -s "$notes_file" ]] || {
    echo "could not create release notes from the current commit" >&2
    exit 1
  }

  echo "==> Preparing release $new_version from $base_version"
  "$ROOT_DIR/scripts/bump-version.sh" "$new_version" "$notes_file"
  git add CHANGELOG.md README.md build.clj package.json package-lock.json \
    resources/llm_context/dependencies.edn src/llm_context/version.clj
  git commit -m "chore: prepare release $new_version"
  trap - EXIT
  rm -rf "$notes_directory"
  exec "$0" publish "${wait_args[@]}"
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
  [0-9]*.[0-9]*.[0-9]*)
    prepare_and_publish "$COMMAND" "$@"
    ;;
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

[[ -s "$ROOT_DIR/resources/lib/x86_64-linux-gnu-tree-sitter-janet.so" ]] || {
  echo "local Linux native parser artifact is missing; run install-dependencies.sh install --with-native and commit it" >&2
  exit 1
}

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
