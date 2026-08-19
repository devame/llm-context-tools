#!/usr/bin/env sh
set -eu

# Build the selected native library shipped for Janet parsing. End users do not
# run this script; the generated libraries are embedded in the standalone JAR.
GRAMMAR_REVISION=3c1bdcfff374138da03a1db25c75efce623910fe
GRAMMAR_ARCHIVE_SHA256=afdac751df067aff225a93fbecdf460eb53814ec10bc702512a3fe4a6ae5fa0f
TREE_SITTER_VERSION=0.26.12
TREE_SITTER_ARCHIVE_SHA256=428e2b182fe38eddc100d8bd851e47c96921a69281b66abafc25ba4b0aaeeeab
ZIG=${ZIG:-zig}
export SOURCE_DATE_EPOCH=0

platform=linux-x86_64
output_dir=

usage() {
  cat >&2 <<'USAGE'
Usage: script/build-janet-grammar.sh [options]

Options:
  --platform NAME    linux-x86_64 (default), linux-aarch64, macos-x86_64,
                     macos-aarch64, windows-x86_64, or all.
  --output-dir DIR   Write selected libraries to DIR instead of resources/lib.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --platform)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      platform=$2
      shift 2
      ;;
    --output-dir)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      output_dir=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'build-janet-grammar: unknown option: %s\n' "$1" >&2
      usage
      exit 2
      ;;
  esac
done

build_linux_x86_64=0
build_linux_aarch64=0
build_macos_x86_64=0
build_macos_aarch64=0
build_windows_x86_64=0
case "$platform" in
  linux-x86_64) build_linux_x86_64=1 ;;
  linux-aarch64) build_linux_aarch64=1 ;;
  macos-x86_64) build_macos_x86_64=1 ;;
  macos-aarch64) build_macos_aarch64=1 ;;
  windows-x86_64) build_windows_x86_64=1 ;;
  all)
    build_linux_x86_64=1
    build_linux_aarch64=1
    build_macos_x86_64=1
    build_macos_aarch64=1
    build_windows_x86_64=1
    ;;
  *)
    printf 'build-janet-grammar: unsupported platform: %s\n' "$platform" >&2
    usage
    exit 2
    ;;
esac

command -v "$ZIG" >/dev/null 2>&1 || {
  printf 'build-janet-grammar: Zig 0.15.1+ is required\n' >&2
  exit 1
}
zig_version=$($ZIG version)
case "$zig_version" in
  0.15.*|0.16.*|0.17.*|0.18.*|1.*) ;;
  *)
    printf 'build-janet-grammar: Zig %s is older than 0.15.1\n' "$zig_version" >&2
    exit 1
    ;;
esac

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | sed 's/[[:space:]].*//'
  else
    shasum -a 256 "$1" | sed 's/[[:space:]].*//'
  fi
}

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -z "$output_dir" ]; then
  output_dir="$repo_root/resources/lib"
fi
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/llm-context-janet.XXXXXX")
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT HUP INT TERM

archive="$work_dir/grammar.tar.gz"
url="https://github.com/sogaiu/tree-sitter-janet-simple/archive/${GRAMMAR_REVISION}.tar.gz"
curl --fail --location --retry 3 --retry-all-errors --connect-timeout 20 \
  --progress-bar "$url" --output "$archive"

actual=$(sha256 "$archive")
[ "$actual" = "$GRAMMAR_ARCHIVE_SHA256" ] || {
  printf 'build-janet-grammar: grammar archive checksum mismatch\n' >&2
  exit 1
}

tar -xzf "$archive" -C "$work_dir"
src="$work_dir/tree-sitter-janet-simple-${GRAMMAR_REVISION}/src"
output="$work_dir/output"
mkdir -p "$output"

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

if [ "$build_linux_x86_64" -eq 1 ]; then
  compile_unix x86_64-linux-gnu x86_64-linux-gnu-tree-sitter-janet.so
fi
if [ "$build_linux_aarch64" -eq 1 ]; then
  compile_unix aarch64-linux-gnu aarch64-linux-gnu-tree-sitter-janet.so
fi
if [ "$build_macos_x86_64" -eq 1 ]; then
  compile_macos x86_64-macos x86_64-macos-tree-sitter-janet.dylib
fi
if [ "$build_macos_aarch64" -eq 1 ]; then
  compile_macos aarch64-macos aarch64-macos-tree-sitter-janet.dylib
fi
if [ "$build_windows_x86_64" -eq 1 ]; then
  compile_windows x86_64-windows-gnu x86_64-windows-tree-sitter-janet.dll

  # The upstream Maven core DLL omits public C API exports on Windows.
  # JTreeSitter therefore cannot resolve ts_set_allocator before any grammar
  # is loaded. Ship the same core version with the public API exported.
  core_archive="$work_dir/tree-sitter.tar.gz"
  core_url="https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v${TREE_SITTER_VERSION}.tar.gz"
  curl --fail --location --retry 3 --retry-all-errors --connect-timeout 20 \
    --progress-bar "$core_url" --output "$core_archive"
  core_actual=$(sha256 "$core_archive")
  [ "$core_actual" = "$TREE_SITTER_ARCHIVE_SHA256" ] || {
    printf 'build-janet-grammar: Tree-sitter archive checksum mismatch\n' >&2
    exit 1
  }
  tar -xzf "$core_archive" -C "$work_dir"
  core="$work_dir/tree-sitter-${TREE_SITTER_VERSION}/lib"
  "$ZIG" cc -target x86_64-windows-gnu -O2 -g0 -shared \
    -I "$core/include" -I "$core/src" "$core/src/lib.c" \
    -o "$work_dir/x86_64-windows-tree-sitter.dll"
  cp "$work_dir/x86_64-windows-tree-sitter.dll" \
    "$output/x86_64-windows-tree-sitter.dll"
fi

mkdir -p "$output_dir"
for artifact in \
  x86_64-linux-gnu-tree-sitter-janet.so \
  aarch64-linux-gnu-tree-sitter-janet.so \
  x86_64-macos-tree-sitter-janet.dylib \
  aarch64-macos-tree-sitter-janet.dylib \
  x86_64-windows-tree-sitter-janet.dll \
  x86_64-windows-tree-sitter.dll; do
  if [ -f "$output/$artifact" ]; then
    case "$artifact" in
      x86_64-linux-gnu-*) [ "$build_linux_x86_64" -eq 1 ] || continue ;;
      aarch64-linux-gnu-*) [ "$build_linux_aarch64" -eq 1 ] || continue ;;
      x86_64-macos-*) [ "$build_macos_x86_64" -eq 1 ] || continue ;;
      aarch64-macos-*) [ "$build_macos_aarch64" -eq 1 ] || continue ;;
      x86_64-windows-*) [ "$build_windows_x86_64" -eq 1 ] || continue ;;
    esac
    chmod 0644 "$output/$artifact"
    cp "$output/$artifact" "$output_dir/$artifact"
  fi
done

printf 'Built Janet grammar revision %s for %s\n' "$GRAMMAR_REVISION" "$platform"
