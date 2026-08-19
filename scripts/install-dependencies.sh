#!/usr/bin/env bash
set -Eeuo pipefail

# Reproducible project dependency bootstrap. The checked-in manifest remains
# authoritative; the online checker refuses stale pins before installation.

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TOOLCHAIN_DIR=$(printenv LLM_CONTEXT_TOOLCHAIN_DIR || true)
LOG_DIR=$(printenv LLM_CONTEXT_DEPENDENCY_LOG_DIR || true)
LOG_FILE=$(printenv LLM_CONTEXT_DEPENDENCY_LOG || true)
[[ -n "$TOOLCHAIN_DIR" ]] || TOOLCHAIN_DIR="$ROOT_DIR/.llm-context/toolchain"
[[ -n "$LOG_DIR" ]] || LOG_DIR="$ROOT_DIR/.llm-context"
[[ -n "$LOG_FILE" ]] || LOG_FILE="$LOG_DIR/dependency-install.log"
MANIFEST="$ROOT_DIR/resources/llm_context/dependencies.edn"

mkdir -p "$LOG_DIR"
exec > >(tee -a "$LOG_FILE") 2>&1

CURRENT_STEP=startup
STEP_NUMBER=0
TOTAL_STEPS=5
BOOTSTRAP_STARTED_AT=$(date +%s)

on_error() {
  local status=$? line=unknown command=unknown
  (($# >= 1)) && line=$1
  (($# >= 2)) && command=$2
  trap - ERR
  printf '\nERROR: dependency setup failed in step "%s" at line %s (exit %s)\n' \
    "$CURRENT_STEP" "$line" "$status" >&2
  printf '       Command: %s\n' "$command" >&2
  printf '       Recovery: inspect %s, fix the reported prerequisite, and rerun the same command; completed steps are cached.\n' \
    "$LOG_FILE" >&2
  exit "$status"
}
trap 'on_error "$LINENO" "$BASH_COMMAND"' ERR

on_signal() {
  local signal=$1
  printf '\nERROR: dependency setup interrupted by %s during step "%s"\n' \
    "$signal" "$CURRENT_STEP" >&2
  printf '       No later steps were started; rerun the same command to continue.\n' >&2
  exit 130
}
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM

ACTION=install
if (($#)); then
  ACTION=$1
  shift
fi
WITH_MODELS=0
WITH_NATIVE=0
YES=0
OFFLINE=0

while (($#)); do
  case "$1" in
    --with-models) WITH_MODELS=1 ;;
    --with-native) WITH_NATIVE=1 ;;
    --yes) YES=1 ;;
    --offline) OFFLINE=1 ;;
    -h|--help)
      cat <<'USAGE'
Usage: scripts/install-dependencies.sh <check|install|verify> [options]

Options:
  --with-models  Download and verify all model packages, including the GGUF reader.
  --with-native  Build only the local Linux Janet/Tree-sitter library; other
                 platform libraries are built by GitHub Actions for releases.
  --yes          Approve local host-tool installation without prompting.
  --offline      Skip upstream latest-release checks (static hashes still run).

The default install resolves JVM dependencies and verifies the repository
contract. Downloads use retries, progress bars, staging directories, and
SHA-256 verification. Logs are retained at .llm-context/dependency-install.log.
Set LLM_CONTEXT_NODE_BIN and LLM_CONTEXT_NPM_BIN to explicitly reuse a shared
installation outside the standard PATH/version-manager locations. Set
LLM_CONTEXT_ZIG_BIN to reuse a shared Zig binary.
USAGE
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      exit 2
      ;;
  esac
  shift
done

case "$ACTION" in
  check|install|verify) ;;
  *)
    printf 'Unknown action: %s (expected check, install, or verify)\n' "$ACTION" >&2
    exit 2
    ;;
esac

notify() {
  printf '[dependency] %s\n' "$*"
}

progress_bar() {
  local percent=$1 label=$2 width=32
  local filled=$((percent * width / 100))
  local empty=$((width - filled))
  printf '\r[%3d%%] %-46s [' "$percent" "$label"
  printf '%*s' "$filled" '' | tr ' ' '#'
  printf '%*s' "$empty" '' | tr ' ' '-'
  printf ']'
  if ((percent == 100)); then
    printf '\n'
  fi
}

run_step() {
  local label=$1
  shift
  STEP_NUMBER=$((STEP_NUMBER + 1))
  CURRENT_STEP=$label
  local started_at elapsed
  started_at=$(date +%s)
  printf '\n[%d/%d] %s\n' "$STEP_NUMBER" "$TOTAL_STEPS" "$label"
  progress_bar 0 "$label"
  "$@"
  elapsed=$(( $(date +%s) - started_at ))
  progress_bar 100 "$label complete"
  printf '      completed in %ss\n' "$elapsed"
}

confirm() {
  local question=$1 answer
  if ((YES)); then
    return 0
  fi
  if [[ ! -t 0 ]]; then
    printf 'ERROR: %s requires --yes in a non-interactive terminal.\n' "$question" >&2
    return 1
  fi
  read -r -p "$question [y/N] " answer
  [[ "$answer" =~ ^[Yy]([Ee][Ss])?$ ]]
}

retry() {
  local label=$1 attempts=$2 delay=$3
  shift 3
  local attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if "$@"; then
      return 0
    fi
    if ((attempt < attempts)); then
      notify "$label failed (attempt $attempt/$attempts); retrying in ${delay}s"
      sleep "$delay"
      delay=$((delay * 2))
    fi
  done
  printf 'ERROR: %s failed after %s attempts.\n' "$label" "$attempts" >&2
  return 1
}

download() {
  local url=$1 destination=$2 temporary
  temporary="${destination}.part"
  mkdir -p "$(dirname -- "$destination")"
  rm -f -- "$temporary"
  retry "Download $(basename -- "$destination")" 3 2 \
    curl --fail --location --retry 2 --retry-all-errors --connect-timeout 20 \
      --progress-bar "$url" --output "$temporary"
  mv -f -- "$temporary" "$destination"
}

manifest_value() {
  local expression=$1
  awk -v expression="$expression" '
    $0 ~ expression { print $NF; exit }
  ' "$MANIFEST" | tr -d '"}'
}

latest_java() {
  local arch api metadata url checksum staging archive url_name actual
  command -v python3 >/dev/null 2>&1 || {
    printf 'ERROR: python3 is required to select the latest Adoptium JDK asset.\n' >&2
    return 1
  }
  case "$(uname -m)" in
    x86_64|amd64) arch=x64 ;;
    aarch64|arm64) arch=aarch64 ;;
    *) printf 'ERROR: unsupported Linux CPU architecture: %s\n' "$(uname -m)" >&2; return 1 ;;
  esac
  api="https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=$arch&image_type=jdk&os=linux&vendor=eclipse"
  metadata="$LOG_DIR/temurin-25.json"
  download "$api" "$metadata"
  url=$(python3 - "$metadata" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    assets = json.load(handle)
print(assets[0]["binary"]["package"]["link"])
PY
  )
  checksum=$(python3 - "$metadata" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    assets = json.load(handle)
print(assets[0]["binary"]["package"]["checksum"])
PY
  )
  url_name=$(printf '%s' "$url" | sed 's/[?].*//')
  archive="$LOG_DIR/$(basename -- "$url_name")"
  download "$url" "$archive"
  actual=$(sha256sum "$archive" | cut -d ' ' -f 1)
  [[ "$actual" == "$checksum" ]] || {
    printf 'ERROR: Temurin archive checksum mismatch.\n' >&2
    return 1
  }
  staging=$(mktemp -d "$TOOLCHAIN_DIR/temurin-25.new.XXXXXX")
  tar -xzf "$archive" -C "$staging" --strip-components=1
  rm -rf -- "$TOOLCHAIN_DIR/temurin-25"
  mv -- "$staging" "$TOOLCHAIN_DIR/temurin-25"
  export JAVA_HOME="$TOOLCHAIN_DIR/temurin-25"
  export PATH="$JAVA_HOME/bin:$PATH"
  notify "Using Temurin $(java -version 2>&1 | sed -n '1p') from $JAVA_HOME"
}

latest_node() {
  local arch metadata url version staging archive checksums expected actual
  local node_root install_dir
  command -v python3 >/dev/null 2>&1 || {
    printf 'ERROR: python3 is required to select the latest Node.js LTS asset.\n' >&2
    return 1
  }
  case "$(uname -m)" in
    x86_64|amd64) arch=x64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) printf 'ERROR: unsupported Linux CPU architecture: %s\n' "$(uname -m)" >&2; return 1 ;;
  esac
  metadata="$LOG_DIR/node-index.json"
  download "https://nodejs.org/dist/index.json" "$metadata"
  version=$(python3 - "$metadata" "$arch" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    versions = json.load(handle)
for item in versions:
    if item.get("lts") and any(f"linux-{sys.argv[2]}" in name for name in item.get("files", [])):
        print(item["version"])
        break
PY
  )
  [[ -n "$version" ]] || { printf 'ERROR: no Node.js LTS asset was found.\n' >&2; return 1; }
  node_root="$TOOLCHAIN_DIR/node"
  install_dir="$node_root/$version"

  # Keep each verified release and move a small current symlink. This makes
  # retries safe and lets later workspaces reuse an already downloaded release.
  if [[ -x "$install_dir/bin/node" && -f "$install_dir/bin/npm" ]] && \
     [[ "$("$install_dir/bin/node" --version 2>/dev/null | tr -d '\r')" == "$version" ]] && \
     "$install_dir/bin/npm" --version >/dev/null 2>&1; then
    mkdir -p "$node_root"
    ln -sfn "$install_dir" "$node_root/current"
    node_command="$node_root/current/bin/node"
    npm_command="$node_root/current/bin/npm"
    node_source="$node_root/current/bin (cached $version)"
    export PATH="$node_root/current/bin:$PATH"
    notify "Reusing cached Node.js $version from $install_dir"
    return 0
  fi
  url="https://nodejs.org/dist/$version/node-$version-linux-$arch.tar.xz"
  archive="$LOG_DIR/node-$version-linux-$arch.tar.xz"
  download "$url" "$archive"
  checksums="$LOG_DIR/node-$version-SHASUMS256.txt"
  download "https://nodejs.org/dist/$version/SHASUMS256.txt" "$checksums"
  expected=$(awk -v file="$(basename -- "$archive")" '$2 == file {print $1}' "$checksums")
  actual=$(sha256sum "$archive" | cut -d ' ' -f 1)
  [[ -n "$expected" && "$actual" == "$expected" ]] || {
    printf 'ERROR: Node.js archive checksum mismatch.\n' >&2
    return 1
  }
  mkdir -p "$TOOLCHAIN_DIR" "$node_root"
  staging=$(mktemp -d "$TOOLCHAIN_DIR/.node-$version.new.XXXXXX")
  tar -xJf "$archive" -C "$staging" --strip-components=1
  rm -rf -- "$install_dir"
  mv -- "$staging" "$install_dir"
  ln -sfn "$install_dir" "$node_root/current"
  export PATH="$node_root/current/bin:$PATH"
  node_command="$node_root/current/bin/node"
  npm_command="$node_root/current/bin/npm"
  node_source="$node_root/current/bin (installed $version)"
  [[ "$(node_version)" == "$version" ]] || {
    printf 'ERROR: Node.js verification failed; expected %s, found %s.\n' \
      "$version" "$(node_version)" >&2
    return 1
  }
  [[ -n "$(npm_version)" ]] || {
    printf 'ERROR: npm verification failed for Node.js %s.\n' "$version" >&2
    return 1
  }
  notify "Using Node.js $version from $install_dir"
}

node_command=node
npm_command=npm
node_source=

node_version() {
  "$node_command" --version 2>/dev/null | tr -d '\r' | sed -n '1p'
}

npm_version() {
  "$npm_command" --version 2>/dev/null | tr -d '\r' | sed -n '1p'
}

node_tools_respond() {
  [[ -n "$(node_version)" && -n "$(npm_version)" ]]
}

discover_node_tools() {
  local explicit_node explicit_npm dir candidate_node candidate_npm source
  explicit_node=$(printenv LLM_CONTEXT_NODE_BIN || true)
  explicit_npm=$(printenv LLM_CONTEXT_NPM_BIN || true)

  if [[ -n "$explicit_node" && ( -x "$explicit_node" || -f "$explicit_node" ) ]]; then
    node_command="$explicit_node"
    if [[ -n "$explicit_npm" && -f "$explicit_npm" ]]; then
      npm_command="$explicit_npm"
    else
      npm_command="$(dirname -- "$explicit_node")/npm"
    fi
    [[ -f "$npm_command" || -x "$npm_command" ]] || return 1
    node_source="$(dirname -- "$explicit_node") (explicit)"
    export PATH="$(dirname -- "$explicit_node"):$PATH"
    node_tools_respond && return 0
  fi

  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    node_command=$(command -v node)
    npm_command=$(command -v npm)
    node_source="$(dirname -- "$node_command") (PATH)"
    node_tools_respond && return 0
  fi

  try_node_tools() {
    candidate_node=$1
    candidate_npm=$2
    source=$3
    [[ -x "$candidate_node" || -f "$candidate_node" ]] || return 1
    [[ -x "$candidate_npm" || -f "$candidate_npm" ]] || return 1
    node_command="$candidate_node"
    npm_command="$candidate_npm"
    node_source="$source"
    export PATH="$(dirname -- "$candidate_node"):$PATH"
    node_tools_respond
  }

  # Load standard global/version-manager locations even when the shell did
  # not source nvm, fnm, asdf, Volta, or mise initialization.
  for dir in \
    "$TOOLCHAIN_DIR/node/current/bin" \
    "$TOOLCHAIN_DIR/node/bin" \
    "$HOME"/.nvm/versions/node/*/bin \
    "$HOME"/.fnm/node-versions/*/installation/bin \
    "$HOME"/.asdf/installs/nodejs/*/bin \
    "$HOME"/.volta/bin \
    "$HOME"/.local/share/mise/installs/node/*/bin \
    "$HOME"/.local/bin \
    /usr/local/bin /usr/bin; do
    [[ -d "$dir" ]] || continue
    if try_node_tools "$dir/node" "$dir/npm" "$dir (discovered)"; then
      return 0
    fi
  done

  # WSL commonly exposes a shared Windows Node installation as node.exe and
  # npm.cmd. It is accepted when explicitly present on PATH, so a second Linux
  # copy is not downloaded unnecessarily.
  while IFS= read -r -d ':' dir; do
    [[ -n "$dir" ]] || continue
    dir=$(printf '%s' "$dir" | sed 's:/*$::')
    if [[ -f "$dir/node.exe" && -f "$dir/npm" ]] && \
       try_node_tools "$dir/node.exe" "$dir/npm" "$dir (Windows/WSL PATH)"; then
      return 0
    elif [[ -f "$dir/node.exe" && -f "$dir/npm.cmd" ]] && \
         try_node_tools "$dir/node.exe" "$dir/npm.cmd" "$dir (Windows/WSL PATH)"; then
      return 0
    fi
  done < <(printf '%s:' "$PATH")

  return 1
}

node_major_version() {
  node_version | sed -n 's/^v\([0-9][0-9]*\).*/\1/p' | head -n 1
}

node_version_usable() {
  local major minimum
  major=$(node_major_version)
  minimum=$(manifest_value ':ci-node-major')
  [[ -n "$major" && "$major" -ge "$minimum" ]] && [[ -n "$(npm_version)" ]]
}

zig_command=zig
zig_source=

zig_version() {
  "$zig_command" version 2>/dev/null | tr -d '\r' | sed -n '1p'
}

discover_zig() {
  local explicit dir
  explicit=$(printenv LLM_CONTEXT_ZIG_BIN || true)
  if [[ -n "$explicit" && -x "$explicit" ]]; then
    zig_command="$explicit"
    zig_source="$explicit (explicit)"
    export PATH="$(dirname -- "$explicit"):$PATH"
    [[ -n "$(zig_version)" ]] && return 0
  fi
  if command -v zig >/dev/null 2>&1; then
    zig_command=$(command -v zig)
    zig_source="$zig_command (PATH)"
    [[ -n "$(zig_version)" ]] && return 0
  fi
  for dir in \
    "$TOOLCHAIN_DIR/zig/current" \
    "$TOOLCHAIN_DIR/zig" \
    "$HOME/.local/bin" \
    /usr/local/bin /usr/bin; do
    [[ -x "$dir/zig" ]] || continue
    zig_command="$dir/zig"
    zig_source="$dir (discovered)"
    export PATH="$dir:$PATH"
    [[ -n "$(zig_version)" ]] && return 0
  done
  return 1
}

zig_version_usable() {
  local actual minimum
  actual=$(zig_version)
  minimum=$(manifest_value ':zig-minimum-version')
  [[ -n "$actual" && -n "$minimum" ]] || return 1
  [[ "$(printf '%s\n' "$minimum" "$actual" | sort -V | head -n 1)" == "$minimum" ]]
}

latest_zig() {
  local arch metadata version asset expected archive staging actual
  local zig_root install_dir
  command -v python3 >/dev/null 2>&1 || {
    printf 'ERROR: python3 is required to select the latest Zig binary.\n' >&2
    return 1
  }
  case "$(uname -m)" in
    x86_64|amd64) arch=x86_64 ;;
    aarch64|arm64) arch=aarch64 ;;
    *) printf 'ERROR: unsupported Linux CPU architecture: %s\n' "$(uname -m)" >&2; return 1 ;;
  esac
  # GitHub's Zig release entries can contain only the bootstrap source
  # archive. The official download index is the source for published
  # platform binaries and their SHA-256 values.
  metadata="$LOG_DIR/zig-download-index.json"
  download "https://ziglang.org/download/index.json" "$metadata"
  version=$(python3 - "$metadata" "$arch" <<'PY'
import json, re, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    index = json.load(handle)
candidates = []
for key, release in index.items():
    if re.fullmatch(r"\d+\.\d+\.\d+", key) and f"{sys.argv[2]}-linux" in release:
        candidates.append((tuple(int(part) for part in key.split(".")), key))
if candidates:
    print(max(candidates)[1])
PY
  )
  [[ -n "$version" ]] || { printf 'ERROR: no stable Zig Linux release was found.\n' >&2; return 1; }
  asset=$(python3 - "$metadata" "$version" "$arch" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    release = json.load(handle)[sys.argv[2]]
print(release[f"{sys.argv[3]}-linux"]["tarball"])
PY
  )
  expected=$(python3 - "$metadata" "$version" "$arch" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as handle:
    release = json.load(handle)[sys.argv[2]]
print(release[f"{sys.argv[3]}-linux"]["shasum"])
PY
  )
  [[ -n "$asset" && -n "$expected" ]] || {
    printf 'ERROR: Zig release %s has no Linux binary metadata.\n' "$version" >&2
    return 1
  }
  zig_root="$TOOLCHAIN_DIR/zig"
  install_dir="$zig_root/$version"

  if [[ -x "$install_dir/zig" ]] && \
     [[ "$("$install_dir/zig" version 2>/dev/null | tr -d '\r')" == "$version" ]]; then
    mkdir -p "$zig_root"
    ln -sfn "$install_dir" "$zig_root/current"
    zig_command="$zig_root/current/zig"
    zig_source="$zig_root/current (cached $version)"
    export PATH="$zig_root/current:$PATH"
    notify "Reusing cached Zig $version from $install_dir"
    return 0
  fi

  archive="$LOG_DIR/$(basename -- "$asset")"
  download "$asset" "$archive"
  actual=$(sha256sum "$archive" | cut -d ' ' -f 1)
  [[ -n "$expected" && "$actual" == "$expected" ]] || {
    printf 'ERROR: Zig archive checksum mismatch.\n' >&2
    return 1
  }
  mkdir -p "$TOOLCHAIN_DIR" "$zig_root"
  staging=$(mktemp -d "$TOOLCHAIN_DIR/.zig-$version.new.XXXXXX")
  tar -xJf "$archive" -C "$staging" --strip-components=1
  rm -rf -- "$install_dir"
  mv -- "$staging" "$install_dir"
  ln -sfn "$install_dir" "$zig_root/current"
  zig_command="$zig_root/current/zig"
  zig_source="$zig_root/current (installed $version)"
  export PATH="$zig_root/current:$PATH"
  [[ "$(zig_version)" == "$version" ]] || {
    printf 'ERROR: Zig verification failed; expected %s, found %s.\n' \
      "$version" "$(zig_version)" >&2
    return 1
  }
  notify "Using Zig $version from $install_dir"
}

latest_clojure_cli() {
  local version archive installer prefix
  version=$(manifest_value ':ci-clojure-cli')
  prefix="$TOOLCHAIN_DIR/clojure"
  archive="$LOG_DIR/clojure-tools-install.sh"
  installer="https://download.clojure.org/install/linux-install.sh"
  download "$installer" "$archive"
  mkdir -p "$prefix"
  bash "$archive" --prefix "$prefix"
  export PATH="$prefix/bin:$PATH"
  actual=$(clojure -Sdescribe | sed -n 's/.*:version "\([^"]*\)".*/\1/p')
  [[ "$actual" == "$version" ]] || {
    printf 'ERROR: Clojure CLI installer produced %s; expected %s.\n' "$actual" "$version" >&2
    return 1
  }
  notify "Using Clojure CLI from $prefix"
}

host_report() {
  local failed=0 feature
  for command_name in java clojure curl tar; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
      printf 'MISSING: %s\n' "$command_name"
      failed=1
    else
      printf 'FOUND:   %s -> %s\n' "$command_name" "$(command -v "$command_name")"
    fi
  done
  if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
    printf 'MISSING: sha256sum or shasum\n'
    failed=1
  fi
  if discover_node_tools && node_version_usable; then
    printf 'FOUND:   node -> %s (%s; %s)\n' "$node_command" \
      "$(node_version)" "$node_source"
    printf 'FOUND:   npm  -> %s (%s)\n' "$npm_command" \
      "$(npm_version)"
  else
    printf 'MISSING/OUTDATED: node/npm (required Node major %s)\n' \
      "$(manifest_value ':ci-node-major')"
    failed=1
  fi
  if ((WITH_NATIVE)); then
    if discover_zig && zig_version_usable; then
      printf 'FOUND:   zig  -> %s (%s; %s)\n' "$zig_command" \
        "$(zig_version)" "$zig_source"
    else
      printf 'MISSING/OUTDATED: zig (minimum version %s)\n' \
        "$(manifest_value ':zig-minimum-version')"
      failed=1
    fi
  fi
  if command -v java >/dev/null 2>&1; then
    feature=$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)
    if [[ -n "$feature" && "$feature" -lt 23 ]]; then
      printf 'OUTDATED: Java feature %s; minimum is 23\n' "$feature"
      failed=1
    fi
  fi
  return "$failed"
}

bootstrap_host_tools() {
  [[ "$(uname -s)" == Linux ]] || {
    notify "Automatic host-tool bootstrap is implemented for Linux/WSL; use the documented platform installers elsewhere."
    return 0
  }
  if ! command -v java >/dev/null 2>&1; then
    confirm "Install the latest Temurin Java 25 into $TOOLCHAIN_DIR?" || return 1
    latest_java
  fi
  if ! command -v clojure >/dev/null 2>&1; then
    confirm "Install the latest Clojure CLI into $TOOLCHAIN_DIR?" || return 1
    latest_clojure_cli
  fi
  if ! discover_node_tools || ! node_version_usable; then
    confirm "Install the latest Node.js LTS into $TOOLCHAIN_DIR?" || return 1
    latest_node
  else
    notify "Reusing Node.js $(node_version) and npm $(npm_version) from $node_source"
  fi
  if ((WITH_NATIVE)) && ! discover_zig; then
    confirm "Install the latest Zig binary into $TOOLCHAIN_DIR?" || return 1
    latest_zig
  elif ((WITH_NATIVE)); then
    if zig_version_usable; then
      notify "Reusing Zig $(zig_version) from $zig_source"
    else
      confirm "Install the latest Zig binary into $TOOLCHAIN_DIR?" || return 1
      latest_zig
    fi
  fi
}

run_static_verification() {
  (cd "$ROOT_DIR" && clojure -M script/verify-dependencies.clj)
}

run_online_verification() {
  if ((OFFLINE)); then
    notify "Skipping upstream version checks (--offline); hashes and repository pins are still verified."
  else
    (cd "$ROOT_DIR" && clojure -M script/check-latest-dependencies.clj)
  fi
}

local_native_platform() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'linux-x86_64\n' ;;
    aarch64|arm64) printf 'linux-aarch64\n' ;;
    *)
      printf 'ERROR: unsupported local Linux CPU architecture for native build: %s\n' \
        "$(uname -m)" >&2
      return 1
      ;;
  esac
}

local_native_library() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'resources/lib/x86_64-linux-gnu-tree-sitter-janet.so\n' ;;
    aarch64|arm64) printf 'resources/lib/aarch64-linux-gnu-tree-sitter-janet.so\n' ;;
    *)
      printf 'ERROR: unsupported local Linux CPU architecture for native verification: %s\n' \
        "$(uname -m)" >&2
      return 1
      ;;
  esac
}

install_project_dependencies() {
  (cd "$ROOT_DIR" && retry "Resolve JVM dependency graph" 3 2 clojure -P)
  if ((WITH_NATIVE)); then
    (cd "$ROOT_DIR" && retry "Build local Linux native parser library" 2 3 \
      script/build-janet-grammar.sh --platform "$(local_native_platform)")
  fi
  if ((WITH_MODELS)); then
    local cache registry
    cache=$(printenv LLM_CONTEXT_MODEL_CACHE || true)
    registry=$(printenv LLM_CONTEXT_MODEL_REGISTRY || true)
    [[ -n "$cache" ]] || cache="$HOME/.cache/llm-context/models"
    [[ -n "$registry" ]] || registry="$cache/registry.edn"
    (cd "$ROOT_DIR" && retry "Download and verify model packages" 3 3 \
      clojure -M -m llm-context.main models install \
        --cache "$cache" --registry "$registry" \
        --roles semantic-retriever,query-router-reranker,answer-reader)
    (cd "$ROOT_DIR" && clojure -M script/verify-installed-models.clj "$registry")
  fi
}

verify_installed_dependencies() {
  (cd "$ROOT_DIR" && clojure -M -e \
    "(require 'clojure.data.json 'clj-kondo.core 'datalevin.core) (println \"JVM dependency classpath verified.\")")
  if ((WITH_NATIVE)); then
    local library
    library=$(local_native_library)
    [[ -s "$ROOT_DIR/$library" ]] || {
      printf 'ERROR: local native dependency is missing or empty: %s\n' "$library" >&2
      return 1
    }
    notify "Local Linux native dependency verified: $library"
  fi
  if ((WITH_MODELS)); then
    local registry
    registry=$(printenv LLM_CONTEXT_MODEL_REGISTRY || true)
    if [[ -z "$registry" ]]; then
      local cache
      cache=$(printenv LLM_CONTEXT_MODEL_CACHE || true)
      [[ -n "$cache" ]] || cache="$HOME/.cache/llm-context/models"
      registry="$cache/registry.edn"
    fi
    (cd "$ROOT_DIR" && clojure -M script/verify-installed-models.clj "$registry")
  fi
}

main() {
  notify "Starting '$ACTION'; log: $LOG_FILE"
  [[ -f "$MANIFEST" ]] || { printf 'ERROR: dependency manifest is missing: %s\n' "$MANIFEST" >&2; return 2; }

  host_step() {
    if [[ "$ACTION" == install ]]; then
      bootstrap_host_tools
    fi
    host_report
  }

  contract_step() {
    run_static_verification
  }

  upstream_step() {
    run_online_verification
  }

  project_step() {
    if [[ "$ACTION" == install ]]; then
      install_project_dependencies
    else
      notify "No files changed ($ACTION only)"
    fi
  }

  run_step "Checking host tools" host_step
  run_step "Checking pinned repository contract" contract_step
  run_step "Checking current upstream releases" upstream_step
  run_step "Installing and verifying project dependencies" project_step
  run_step "Verifying installed dependency classpath and artifacts" verify_installed_dependencies

  notify "Dependency $ACTION completed successfully."
  printf 'Dependency setup completed in %ss.\n' "$(( $(date +%s) - BOOTSTRAP_STARTED_AT ))"
}

main "$@"
