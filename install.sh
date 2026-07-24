#!/usr/bin/env sh
set -eu

REPOSITORY="devame/llm-context-tools"
VERSION=${LLM_CONTEXT_VERSION:-latest}
NEXT_PLAID_VERSION="1.6.4"
MODEL_ID="lightonai/LateOn-Code"
MODEL_REVISION="734b659a57935ef50562d79581c3ff1f8d825c93"
DEFAULT_INSTALL_DIR="${HOME}/.local/bin"
INSTALL_DIR=${LLM_CONTEXT_INSTALL_DIR:-"$DEFAULT_INSTALL_DIR"}
MODEL_CACHE_ROOT=${LLM_CONTEXT_MODEL_CACHE:-"${HOME}/.cache/llm-context/models"}
MODEL_DIR="${MODEL_CACHE_ROOT}/lightonai--LateOn-Code/${MODEL_REVISION}"

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

file_hash() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | sed 's/[[:space:]].*//'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | sed 's/[[:space:]].*//'
  else
    fail "sha256sum or shasum is required to verify downloads"
  fi
}

verify_hash() {
  [ -f "$1" ] && [ "$(file_hash "$1")" = "$2" ]
}

TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/llm-context-install.XXXXXX")
cleanup() { rm -rf -- "$TEMP_DIR"; }
trap cleanup EXIT HUP INT TERM

printf 'Downloading llm-context %s...\n' "$VERSION"
download "$RELEASE_URL/llm-context.jar" "$TEMP_DIR/llm-context.jar"
download "$RELEASE_URL/llm-context.jar.sha256" "$TEMP_DIR/llm-context.jar.sha256"

EXPECTED_HASH=$(sed -n '1{s/[[:space:]].*//;p;}' "$TEMP_DIR/llm-context.jar.sha256")
ACTUAL_HASH=$(file_hash "$TEMP_DIR/llm-context.jar")

[ -n "$EXPECTED_HASH" ] && [ "$EXPECTED_HASH" = "$ACTUAL_HASH" ] ||
  fail "release checksum verification failed"

INSTALL_SEMANTIC=1
case "${LLM_CONTEXT_SKIP_SEMANTIC:-0}" in
  1|true|TRUE|yes|YES) INSTALL_SEMANTIC=0 ;;
esac

if [ "$INSTALL_SEMANTIC" -eq 1 ]; then
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64|Linux:amd64)
      NEXT_PLAID_TARGET="x86_64-unknown-linux-gnu" ;;
    Darwin:arm64|Darwin:aarch64)
      NEXT_PLAID_TARGET="aarch64-apple-darwin" ;;
    Darwin:x86_64|Darwin:amd64)
      NEXT_PLAID_TARGET="x86_64-apple-darwin" ;;
    *)
      fail "LateOn runtime is not packaged for $(uname -s) $(uname -m); rerun with LLM_CONTEXT_SKIP_SEMANTIC=1"
      ;;
  esac

  NEXT_PLAID_ARCHIVE="next-plaid-api-${NEXT_PLAID_VERSION}-${NEXT_PLAID_TARGET}.tar.gz"
  printf 'Downloading NextPlaid API %s for %s...\n' \
    "$NEXT_PLAID_VERSION" "$NEXT_PLAID_TARGET"
  download "$RELEASE_URL/$NEXT_PLAID_ARCHIVE" \
    "$TEMP_DIR/$NEXT_PLAID_ARCHIVE"
  download "$RELEASE_URL/$NEXT_PLAID_ARCHIVE.sha256" \
    "$TEMP_DIR/$NEXT_PLAID_ARCHIVE.sha256"
  NEXT_PLAID_EXPECTED=$(sed -n '1{s/[[:space:]].*//;p;}' \
    "$TEMP_DIR/$NEXT_PLAID_ARCHIVE.sha256")
  [ -n "$NEXT_PLAID_EXPECTED" ] &&
    [ "$NEXT_PLAID_EXPECTED" = "$(file_hash "$TEMP_DIR/$NEXT_PLAID_ARCHIVE")" ] ||
    fail "NextPlaid runtime checksum verification failed"
  mkdir -p "$TEMP_DIR/next-plaid"
  tar -xzf "$TEMP_DIR/$NEXT_PLAID_ARCHIVE" -C "$TEMP_DIR/next-plaid"
  [ -f "$TEMP_DIR/next-plaid/next-plaid-api" ] ||
    fail "NextPlaid runtime archive did not contain next-plaid-api"
  [ -f "$TEMP_DIR/next-plaid/libonnxruntime.so" ] ||
    [ -f "$TEMP_DIR/next-plaid/libonnxruntime.dylib" ] ||
    fail "NextPlaid runtime archive did not contain ONNX Runtime"

  if verify_hash "$MODEL_DIR/model_int8.onnx" \
       "a62a88b4e3ebb76e8bc5f0263d17b773c667d27bc73c5120e3131048dd1554ef" &&
     verify_hash "$MODEL_DIR/tokenizer.json" \
       "a388b94942e98e5c661c6c23f919842285738bfd123a0d148dea0c56287505d0" &&
     verify_hash "$MODEL_DIR/config_sentence_transformers.json" \
       "34942289dec20e285b07132aa1d09980ed776a0bc34e531dd7b49c4701876871" &&
     verify_hash "$MODEL_DIR/config.json" \
       "424fa6fedd42b6a78257145a6068c17cc7e67ac5d7cc3c011ed9d8141c9159d4" &&
     verify_hash "$MODEL_DIR/onnx_config.json" \
       "eedf90bb3b71b7500a973e140b72a736c4c5ca4b6746c1f69fcc64b29924a8d5"; then
    MODEL_READY=1
    printf 'Using verified LateOn-Code model snapshot at %s\n' "$MODEL_DIR"
  else
    MODEL_READY=0
    MODEL_URL_BASE=${LLM_CONTEXT_MODEL_URL:-"https://huggingface.co/${MODEL_ID}/resolve/${MODEL_REVISION}"}
    mkdir -p "$TEMP_DIR/model"
    printf 'Downloading pinned LateOn-Code INT8 model (about 154 MB)...\n'
    download "$MODEL_URL_BASE/model_int8.onnx?download=true" \
      "$TEMP_DIR/model/model_int8.onnx"
    download "$MODEL_URL_BASE/tokenizer.json?download=true" \
      "$TEMP_DIR/model/tokenizer.json"
    download "$MODEL_URL_BASE/config_sentence_transformers.json?download=true" \
      "$TEMP_DIR/model/config_sentence_transformers.json"
    download "$MODEL_URL_BASE/config.json?download=true" \
      "$TEMP_DIR/model/config.json"
    download "$MODEL_URL_BASE/onnx_config.json?download=true" \
      "$TEMP_DIR/model/onnx_config.json"
    verify_hash "$TEMP_DIR/model/model_int8.onnx" \
      "a62a88b4e3ebb76e8bc5f0263d17b773c667d27bc73c5120e3131048dd1554ef" &&
    verify_hash "$TEMP_DIR/model/tokenizer.json" \
      "a388b94942e98e5c661c6c23f919842285738bfd123a0d148dea0c56287505d0" &&
    verify_hash "$TEMP_DIR/model/config_sentence_transformers.json" \
      "34942289dec20e285b07132aa1d09980ed776a0bc34e531dd7b49c4701876871" &&
    verify_hash "$TEMP_DIR/model/config.json" \
      "424fa6fedd42b6a78257145a6068c17cc7e67ac5d7cc3c011ed9d8141c9159d4" &&
    verify_hash "$TEMP_DIR/model/onnx_config.json" \
      "eedf90bb3b71b7500a973e140b72a736c4c5ca4b6746c1f69fcc64b29924a8d5" ||
      fail "LateOn-Code model checksum verification failed"
  fi
fi

mkdir -p "$INSTALL_DIR"
cp "$TEMP_DIR/llm-context.jar" "$INSTALL_DIR/.llm-context.jar.new"
mv -f "$INSTALL_DIR/.llm-context.jar.new" "$INSTALL_DIR/llm-context.jar"

cat >"$INSTALL_DIR/.llm-context.new" <<'LAUNCHER'
#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LLM_CONTEXT_INSTALL_DIR="$SCRIPT_DIR"
export LLM_CONTEXT_INSTALL_DIR
exec java --enable-native-access=ALL-UNNAMED -jar "$SCRIPT_DIR/llm-context.jar" "$@"
LAUNCHER
chmod 755 "$INSTALL_DIR/.llm-context.new"
mv -f "$INSTALL_DIR/.llm-context.new" "$INSTALL_DIR/llm-context"

if [ "$INSTALL_SEMANTIC" -eq 1 ]; then
  cp "$TEMP_DIR/next-plaid/next-plaid-api" \
    "$INSTALL_DIR/.next-plaid-api.new"
  chmod 755 "$INSTALL_DIR/.next-plaid-api.new"
  mv -f "$INSTALL_DIR/.next-plaid-api.new" \
    "$INSTALL_DIR/next-plaid-api"
  if [ -f "$TEMP_DIR/next-plaid/libonnxruntime.so" ]; then
    cp "$TEMP_DIR/next-plaid/libonnxruntime.so" \
      "$INSTALL_DIR/libonnxruntime.so"
  fi
  if [ -f "$TEMP_DIR/next-plaid/libonnxruntime.dylib" ]; then
    cp "$TEMP_DIR/next-plaid/libonnxruntime.dylib" \
      "$INSTALL_DIR/libonnxruntime.dylib"
  fi
  if [ -f "$TEMP_DIR/next-plaid/next-plaid-LICENSE" ]; then
    cp "$TEMP_DIR/next-plaid/next-plaid-LICENSE" \
      "$INSTALL_DIR/next-plaid-LICENSE"
  fi
  if [ -f "$TEMP_DIR/next-plaid/onnxruntime-LICENSE" ]; then
    cp "$TEMP_DIR/next-plaid/onnxruntime-LICENSE" \
      "$INSTALL_DIR/onnxruntime-LICENSE"
  fi
  if [ -f "$TEMP_DIR/next-plaid/onnxruntime-ThirdPartyNotices.txt" ]; then
    cp "$TEMP_DIR/next-plaid/onnxruntime-ThirdPartyNotices.txt" \
      "$INSTALL_DIR/onnxruntime-ThirdPartyNotices.txt"
  fi

  if [ "$MODEL_READY" -eq 0 ]; then
    MODEL_PARENT=$(dirname "$MODEL_DIR")
    MODEL_STAGED="${MODEL_DIR}.new.$$"
    MODEL_BACKUP="${MODEL_DIR}.previous.$$"
    mkdir -p "$MODEL_PARENT"
    mkdir "$MODEL_STAGED"
    cp "$TEMP_DIR/model/"* "$MODEL_STAGED/"
    if [ -d "$MODEL_DIR" ]; then
      mv "$MODEL_DIR" "$MODEL_BACKUP"
    fi
    if mv "$MODEL_STAGED" "$MODEL_DIR"; then
      if [ -d "$MODEL_BACKUP" ]; then
        rm -rf -- "$MODEL_BACKUP"
      fi
    else
      if [ -d "$MODEL_BACKUP" ]; then
        mv "$MODEL_BACKUP" "$MODEL_DIR"
      fi
      fail "could not install the LateOn-Code model snapshot"
    fi
  fi
fi

INSTALLED_VERSION=$("$INSTALL_DIR/llm-context" version)
printf 'Installed llm-context %s at %s\n' "$INSTALLED_VERSION" "$INSTALL_DIR/llm-context"
if [ "$INSTALL_SEMANTIC" -eq 1 ]; then
  printf 'Installed NextPlaid API %s and LateOn-Code at %s\n' \
    "$NEXT_PLAID_VERSION" "$MODEL_DIR"
fi

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
