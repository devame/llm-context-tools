#!/usr/bin/env sh
set -eu

REPOSITORY="devame/llm-context-tools"
VERSION=${LLM_CONTEXT_VERSION:-latest}
DEFAULT_INSTALL_DIR="${HOME}/.local/bin"
INSTALL_DIR=${LLM_CONTEXT_INSTALL_DIR:-"$DEFAULT_INSTALL_DIR"}

if [ -n "${LLM_CONTEXT_RELEASE_URL:-}" ]; then
  RELEASE_URL=${LLM_CONTEXT_RELEASE_URL%/}
elif [ "$VERSION" = "latest" ]; then
  RELEASE_URL="https://github.com/${REPOSITORY}/releases/latest/download"
else
  RELEASE_URL="https://github.com/${REPOSITORY}/releases/download/v${VERSION}"
fi

fail() {
  printf 'llm-context installer: %s\n' "$*" >&2
  exit 1
}

command -v java >/dev/null 2>&1 ||
  fail "Java 23 or newer is required but java was not found on PATH"

JAVA_LINE=$(java -version 2>&1 | sed -n '1p')
JAVA_MAJOR=$(printf '%s\n' "$JAVA_LINE" |
  sed -n 's/.*version "\(1\.\)\{0,1\}\([0-9][0-9]*\).*/\2/p')
case "$JAVA_MAJOR" in
  ''|*[!0-9]*) fail "could not determine the Java version from: $JAVA_LINE" ;;
esac
[ "$JAVA_MAJOR" -ge 23 ] ||
  fail "Java 23 or newer is required; found Java $JAVA_MAJOR"

if command -v curl >/dev/null 2>&1; then
  download() { curl --fail --silent --show-error --location "$1" --output "$2"; }
elif command -v wget >/dev/null 2>&1; then
  download() { wget --quiet "$1" --output-document "$2"; }
else
  fail "curl or wget is required to download the release"
fi

TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/llm-context-install.XXXXXX")
cleanup() { rm -rf -- "$TEMP_DIR"; }
trap cleanup EXIT HUP INT TERM

printf 'Downloading llm-context %s...\n' "$VERSION"
download "$RELEASE_URL/llm-context.jar" "$TEMP_DIR/llm-context.jar"
download "$RELEASE_URL/llm-context.jar.sha256" "$TEMP_DIR/llm-context.jar.sha256"

EXPECTED_HASH=$(sed -n '1{s/[[:space:]].*//;p;}' "$TEMP_DIR/llm-context.jar.sha256")
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_HASH=$(sha256sum "$TEMP_DIR/llm-context.jar" | sed 's/[[:space:]].*//')
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL_HASH=$(shasum -a 256 "$TEMP_DIR/llm-context.jar" | sed 's/[[:space:]].*//')
else
  fail "sha256sum or shasum is required to verify the release"
fi

[ -n "$EXPECTED_HASH" ] && [ "$EXPECTED_HASH" = "$ACTUAL_HASH" ] ||
  fail "release checksum verification failed"

mkdir -p "$INSTALL_DIR"
cp "$TEMP_DIR/llm-context.jar" "$INSTALL_DIR/.llm-context.jar.new"
mv -f "$INSTALL_DIR/.llm-context.jar.new" "$INSTALL_DIR/llm-context.jar"

cat >"$INSTALL_DIR/.llm-context.new" <<'LAUNCHER'
#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java --enable-native-access=ALL-UNNAMED -jar "$SCRIPT_DIR/llm-context.jar" "$@"
LAUNCHER
chmod 755 "$INSTALL_DIR/.llm-context.new"
mv -f "$INSTALL_DIR/.llm-context.new" "$INSTALL_DIR/llm-context"

INSTALLED_VERSION=$("$INSTALL_DIR/llm-context" version)
printf 'Installed llm-context %s at %s\n' "$INSTALLED_VERSION" "$INSTALL_DIR/llm-context"

case "${LLM_CONTEXT_INSTALL_SCIP:-0}" in
  1|true|TRUE|yes|YES)
    command -v npm >/dev/null 2>&1 ||
      fail "npm is required when LLM_CONTEXT_INSTALL_SCIP=1"
    printf 'Installing optional SCIP TypeScript provider...\n'
    npm install --global '@sourcegraph/scip-typescript@^0.4.0'
    ;;
esac

case ":${PATH}:" in
  *":${INSTALL_DIR}:"*) ;;
  *)
    if [ "$INSTALL_DIR" = "$DEFAULT_INSTALL_DIR" ] &&
       [ "${LLM_CONTEXT_SKIP_PATH_UPDATE:-0}" != "1" ]; then
      case "${SHELL:-}" in
        */zsh) PROFILE_FILE="${HOME}/.zprofile" ;;
        */bash)
          if [ "$(uname -s)" = "Darwin" ]; then
            PROFILE_FILE="${HOME}/.bash_profile"
          else
            PROFILE_FILE="${HOME}/.profile"
          fi
          ;;
        *) PROFILE_FILE="${HOME}/.profile" ;;
      esac
      if ! grep -F 'export PATH="$HOME/.local/bin:$PATH"' "$PROFILE_FILE" \
           >/dev/null 2>&1; then
        printf '\n# Added by llm-context installer\nexport PATH="$HOME/.local/bin:$PATH"\n' \
          >>"$PROFILE_FILE"
      fi
      printf 'Added %s to PATH in %s; open a new terminal to use it.\n' \
        "$INSTALL_DIR" "$PROFILE_FILE"
    else
      printf 'Add %s to PATH to run llm-context from any directory.\n' "$INSTALL_DIR"
    fi
    ;;
esac
