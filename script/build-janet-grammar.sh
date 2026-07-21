#!/usr/bin/env sh
set -eu

# Rebuild the five native libraries shipped for Janet parsing. End users do not
# run this script; the generated libraries are embedded in the standalone JAR.
GRAMMAR_REVISION=3c1bdcfff374138da03a1db25c75efce623910fe
GRAMMAR_ARCHIVE_SHA256=afdac751df067aff225a93fbecdf460eb53814ec10bc702512a3fe4a6ae5fa0f
TREE_SITTER_VERSION=0.25.3
TREE_SITTER_ARCHIVE_SHA256=862fac52653bc7bc9d2cd0630483e6bdf3d02bcd23da956ca32663c4798a93e3
ZIG=${ZIG:-zig}
export SOURCE_DATE_EPOCH=0

command -v "$ZIG" >/dev/null 2>&1 || {
  printf 'build-janet-grammar: Zig 0.15+ is required\n' >&2
  exit 1
}

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/llm-context-janet.XXXXXX")
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT HUP INT TERM

archive="$work_dir/grammar.tar.gz"
url="https://github.com/sogaiu/tree-sitter-janet-simple/archive/${GRAMMAR_REVISION}.tar.gz"
curl --fail --silent --show-error --location "$url" --output "$archive"

actual=$(sha256sum "$archive" | sed 's/[[:space:]].*//')
[ "$actual" = "$GRAMMAR_ARCHIVE_SHA256" ] || {
  printf 'build-janet-grammar: grammar archive checksum mismatch\n' >&2
  exit 1
}

tar -xzf "$archive" -C "$work_dir"
src="$work_dir/tree-sitter-janet-simple-${GRAMMAR_REVISION}/src"
output="$repo_root/resources/lib"
mkdir -p "$output"

core_archive="$work_dir/tree-sitter.tar.gz"
core_url="https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v${TREE_SITTER_VERSION}.tar.gz"
curl --fail --silent --show-error --location "$core_url" --output "$core_archive"
core_actual=$(sha256sum "$core_archive" | sed 's/[[:space:]].*//')
[ "$core_actual" = "$TREE_SITTER_ARCHIVE_SHA256" ] || {
  printf 'build-janet-grammar: Tree-sitter archive checksum mismatch\n' >&2
  exit 1
}
tar -xzf "$core_archive" -C "$work_dir"
core="$work_dir/tree-sitter-${TREE_SITTER_VERSION}/lib"

compile_unix() {
  target=$1
  destination=$2
  "$ZIG" cc -target "$target" -O2 -g0 -shared -fPIC -I "$src" \
    "$src/parser.c" "$src/scanner.c" -o "$output/$destination"
}

compile_macos() {
  target=$1
  destination=$2
  "$ZIG" cc -target "$target" -O2 -g0 -dynamiclib -fPIC -I "$src" \
    "$src/parser.c" "$src/scanner.c" -o "$output/$destination"
}

compile_windows() {
  target=$1
  destination=$2
  build_output="$work_dir/$destination"
  "$ZIG" cc -target "$target" -O2 -g0 -shared -I "$src" \
    "$src/parser.c" "$src/scanner.c" -o "$build_output"
  cp "$build_output" "$output/$destination"
}

compile_unix x86_64-linux-gnu x86_64-linux-gnu-tree-sitter-janet.so
compile_unix aarch64-linux-gnu aarch64-linux-gnu-tree-sitter-janet.so
compile_macos x86_64-macos x86_64-macos-tree-sitter-janet.dylib
compile_macos aarch64-macos aarch64-macos-tree-sitter-janet.dylib
compile_windows x86_64-windows-gnu x86_64-windows-tree-sitter-janet.dll

# The upstream Maven core DLL omits public C API exports on Windows. JTreeSitter
# therefore cannot resolve ts_set_allocator before any grammar is loaded. Ship
# the same core version with the public API exported, as api.h intends.
"$ZIG" cc -target x86_64-windows-gnu -O2 -g0 -shared \
  -I "$core/include" -I "$core/src" "$core/src/lib.c" \
  -o "$work_dir/x86_64-windows-tree-sitter.dll"
cp "$work_dir/x86_64-windows-tree-sitter.dll" \
  "$output/x86_64-windows-tree-sitter.dll"

chmod 0644 "$output"/*tree-sitter-janet* \
  "$output/x86_64-windows-tree-sitter.dll"

printf 'Built Janet grammar revision %s\n' "$GRAMMAR_REVISION"
