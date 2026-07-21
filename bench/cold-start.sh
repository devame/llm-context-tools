#!/usr/bin/env sh
set -eu

ITERATIONS=${1:-5}
JAR_PATH=${2:-dist/llm-context.jar}

if [ ! -f "$JAR_PATH" ]; then
  echo "Build $JAR_PATH with: clojure -T:build dist" >&2
  exit 2
fi

i=1
while [ "$i" -le "$ITERATIONS" ]; do
  /usr/bin/time -f '%e' java --enable-native-access=ALL-UNNAMED \
    -jar "$JAR_PATH" version >/dev/null
  i=$((i + 1))
done
