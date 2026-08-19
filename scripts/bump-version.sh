#!/usr/bin/env bash
set -euo pipefail

# Update the version locations that are part of the published CLI and its
# documentation. Changelog content is supplied explicitly so a release never
# gets a silently empty or placeholder notes section.

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

usage() {
  cat >&2 <<'EOF'
Usage: scripts/bump-version.sh VERSION NOTES_FILE

VERSION must be a numeric major.minor.patch version without a leading v.
NOTES_FILE contains the Markdown bullets for the new CHANGELOG.md section.
EOF
}

if [[ $# -ne 2 ]]; then
  usage
  exit 2
fi

NEW_VERSION=$1
NOTES_FILE=$2

if [[ "$NOTES_FILE" != /* ]]; then
  NOTES_FILE="$PWD/$NOTES_FILE"
fi
NOTES_FILE=$(cd "$(dirname "$NOTES_FILE")" && pwd)/$(basename "$NOTES_FILE")

[[ "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "invalid version: $NEW_VERSION" >&2
  exit 2
}
[[ -s "$NOTES_FILE" ]] || {
  echo "release notes file is missing or empty: $NOTES_FILE" >&2
  exit 1
}

cd "$ROOT_DIR"
[[ -z "$(git status --porcelain)" ]] || {
  echo "working tree must be clean before bumping the version" >&2
  exit 1
}

SOURCE_VERSION=$(sed -n 's/^(def value "\(.*\)")$/\1/p' \
  src/llm_context/version.clj)
[[ "$SOURCE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "could not read the current version from src/llm_context/version.clj" >&2
  exit 1
}
[[ "$SOURCE_VERSION" != "$NEW_VERSION" ]] || {
  echo "version is already $NEW_VERSION" >&2
  exit 1
}

for file in build.clj package.json package-lock.json \
  resources/llm_context/dependencies.edn src/llm_context/version.clj; do
  OLD_VERSION="$SOURCE_VERSION" NEW_VERSION="$NEW_VERSION" \
    perl -0pi -e '
      s/\Q$ENV{OLD_VERSION}\E/$ENV{NEW_VERSION}/g
    ' "$file"
done

# README contains only current-release installation examples and a pin used by
# the release gate. Keep those examples aligned without touching historical
# changelog entries.
OLD_VERSION="$SOURCE_VERSION" NEW_VERSION="$NEW_VERSION" \
  perl -0pi -e '
    s/(LLM_CONTEXT_VERSION=)\Q$ENV{OLD_VERSION}\E/$1$ENV{NEW_VERSION}/g;
    s/(llm-context-)\Q$ENV{OLD_VERSION}\E(\.tgz)/$1$ENV{NEW_VERSION}$2/g
  ' README.md

TEMP_CHANGELOG=$(mktemp)
trap 'rm -f "$TEMP_CHANGELOG"' EXIT
{
  printf '## %s\n\n' "$NEW_VERSION"
  cat "$NOTES_FILE"
  printf '\n\n'
  cat CHANGELOG.md
} >"$TEMP_CHANGELOG"
mv "$TEMP_CHANGELOG" CHANGELOG.md
trap - EXIT

echo "Bumped version from $SOURCE_VERSION to $NEW_VERSION"
echo "Review the changes, then run scripts/build-release.sh"
