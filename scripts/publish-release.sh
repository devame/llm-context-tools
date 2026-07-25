#!/usr/bin/env bash
set -euo pipefail

# Publish a built release idempotently. The release may already exist (for
# example when a tag was created manually); assets are uploaded separately so
# rerunning this script repairs or replaces incomplete assets safely.

REPOSITORY=${REPOSITORY:-devame/llm-context-tools}
TAG=${1:-${GITHUB_REF_NAME:-}}
DIST_DIR=${DIST_DIR:-dist}

if [[ -z "$TAG" ]]; then
  echo "usage: $0 v<version> [dist-dir]" >&2
  exit 2
fi
if [[ $# -ge 2 ]]; then
  DIST_DIR=$2
fi

command -v gh >/dev/null || { echo "gh is required" >&2; exit 1; }
[[ -f "$DIST_DIR/llm-context.jar" ]] || { echo "missing $DIST_DIR/llm-context.jar" >&2; exit 1; }
[[ -f "$DIST_DIR/USER-GUIDE.md" ]] || cp docs/user-guide.md "$DIST_DIR/USER-GUIDE.md"
if [[ ! -f "$DIST_DIR/USER-GUIDE.md.sha256" ]]; then
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$DIST_DIR" && sha256sum USER-GUIDE.md > USER-GUIDE.md.sha256)
  else
    (cd "$DIST_DIR" && shasum -a 256 USER-GUIDE.md > USER-GUIDE.md.sha256)
  fi
fi

assets=(
  "$DIST_DIR/llm-context.jar"
  "$DIST_DIR/llm-context.jar.sha256"
  "$DIST_DIR/USER-GUIDE.md"
  "$DIST_DIR/USER-GUIDE.md.sha256"
  "$DIST_DIR"/next-plaid-api-*
  install.sh
  install.ps1
)

for asset in "${assets[@]}"; do
  [[ -f "$asset" ]] || { echo "missing release asset: $asset" >&2; exit 1; }
done

if ! gh release view "$TAG" --repo "$REPOSITORY" >/dev/null 2>&1; then
  gh release create "$TAG" --repo "$REPOSITORY" --verify-tag --generate-notes
fi

gh release upload "$TAG" --repo "$REPOSITORY" --clobber "${assets[@]}"
gh release edit "$TAG" --repo "$REPOSITORY" --draft=false
echo "Published $TAG with ${#assets[@]} assets"
