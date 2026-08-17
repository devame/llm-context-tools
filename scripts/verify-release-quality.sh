#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
JAR_PATH=${1:-"$ROOT_DIR/dist/llm-context.jar"}
FIXTURE_DIR="$ROOT_DIR/quality/release-corpus"
CHECKER="$ROOT_DIR/script/verify-release-graph.clj"
EXPECTED_VERSION=0.12.1
EXPECTED_GRAPH_FORMAT=4

if [[ "$JAR_PATH" != /* ]]; then
  JAR_PATH="$(cd "$(dirname "$JAR_PATH")" && pwd)/$(basename "$JAR_PATH")"
fi

[[ -f "$JAR_PATH" ]] || {
  echo "missing packaged jar: $JAR_PATH" >&2
  echo "build it with: clojure -T:build dist" >&2
  exit 1
}

run_jar() {
  java --enable-native-access=ALL-UNNAMED -jar "$JAR_PATH" "$@"
}

source_version=$(sed -n 's/^(def value "\(.*\)")$/\1/p' \
  "$ROOT_DIR/src/llm_context/version.clj")
build_version=$(sed -n 's/^(def version "\(.*\)")$/\1/p' \
  "$ROOT_DIR/build.clj")
package_version=$(sed -n 's/^[[:space:]]*"version": "\(.*\)",$/\1/p' \
  "$ROOT_DIR/package.json" | head -1)
lock_versions=$(sed -n 's/^[[:space:]]*"version": "\(.*\)",$/\1/p' \
  "$ROOT_DIR/package-lock.json" | sort -u)
jar_version=$(run_jar version)

for version in "$source_version" "$build_version" "$package_version" \
  "$lock_versions" "$jar_version"; do
  [[ "$version" == "$EXPECTED_VERSION" ]] || {
    echo "release version mismatch: expected $EXPECTED_VERSION, found $version" >&2
    exit 1
  }
done

grep -q "^## $EXPECTED_VERSION$" "$ROOT_DIR/CHANGELOG.md" || {
  echo "CHANGELOG.md has no $EXPECTED_VERSION release section" >&2
  exit 1
}
grep -q "LLM_CONTEXT_VERSION=$EXPECTED_VERSION" "$ROOT_DIR/README.md" || {
  echo "README.md does not pin the current release version" >&2
  exit 1
}

jar_graph_format=$(
  java --enable-native-access=ALL-UNNAMED -cp "$JAR_PATH" clojure.main -e \
    "(require 'llm-context.model.schema) (print llm-context.model.schema/graph-format-version)"
)
[[ "$jar_graph_format" == "$EXPECTED_GRAPH_FORMAT" ]] || {
  echo "graph format mismatch: expected $EXPECTED_GRAPH_FORMAT, found $jar_graph_format" >&2
  exit 1
}

TEMP_DIR=$(mktemp -d)
if [[ ${KEEP_RELEASE_QUALITY_TEMP:-0} == 1 ]]; then
  echo "Keeping release-quality workspace at $TEMP_DIR"
else
  trap 'rm -rf "$TEMP_DIR"' EXIT
fi
INCREMENTAL_PROJECT="$TEMP_DIR/incremental"
FULL_PROJECT="$TEMP_DIR/full"
mkdir -p "$INCREMENTAL_PROJECT" "$FULL_PROJECT"
cp -R "$FIXTURE_DIR/base/." "$INCREMENTAL_PROJECT/"
cp -R "$FIXTURE_DIR/base/." "$FULL_PROJECT/"

run_jar -C "$INCREMENTAL_PROJECT" analyze --full \
  >"$TEMP_DIR/initial-full.log"
cp "$FIXTURE_DIR/updates/src/quality/core.clj" \
  "$INCREMENTAL_PROJECT/src/quality/core.clj"
run_jar -C "$INCREMENTAL_PROJECT" analyze \
  >"$TEMP_DIR/incremental.log"
grep -Eq "Analyzed [0-9]+ files: 1 changed, 0 deleted" \
  "$TEMP_DIR/incremental.log" || {
    cat "$TEMP_DIR/incremental.log" >&2
    echo "incremental release corpus did not report exactly one changed file" >&2
    exit 1
  }
run_jar -C "$INCREMENTAL_PROJECT" export --format edn \
  --output "$TEMP_DIR/incremental.edn"

cp "$FIXTURE_DIR/updates/src/quality/core.clj" \
  "$FULL_PROJECT/src/quality/core.clj"
run_jar -C "$FULL_PROJECT" analyze --check >"$TEMP_DIR/check.log"
grep -Eq "Validated 4 files and [0-9]+ canonical entities" \
  "$TEMP_DIR/check.log" || {
    cat "$TEMP_DIR/check.log" >&2
    echo "packaged read-only analysis check did not validate the release corpus" >&2
    exit 1
  }
[[ ! -e "$FULL_PROJECT/.llm-context/db" ]] || {
  echo "analyze --check unexpectedly created a graph database" >&2
  exit 1
}
run_jar -C "$FULL_PROJECT" analyze --full >"$TEMP_DIR/final-full.log"
run_jar -C "$FULL_PROJECT" export --format edn \
  --output "$TEMP_DIR/full.edn"

java --enable-native-access=ALL-UNNAMED -cp "$JAR_PATH" clojure.main \
  "$CHECKER" \
  "$INCREMENTAL_PROJECT" "$FULL_PROJECT" \
  "$TEMP_DIR/incremental.edn" "$TEMP_DIR/full.edn"

echo "Release quality gate passed for llm-context $EXPECTED_VERSION, graph format $EXPECTED_GRAPH_FORMAT"
