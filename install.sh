#!/usr/bin/env sh
set -eu

REPOSITORY="devame/llm-context-tools"
VERSION=${LLM_CONTEXT_VERSION:-latest}
NEXT_PLAID_VERSION="1.6.4"
MODEL_ID="lightonai/LateOn-Code"
MODEL_REVISION="734b659a57935ef50562d79581c3ff1f8d825c93"
ROUTER_MODEL_ID="mixedbread-ai/mxbai-edge-colbert-v0-32m"
ROUTER_MODEL_REVISION="963e23afa1478d8bcc12e5d7115adcfdbd22c3af"
DEFAULT_INSTALL_DIR="${HOME}/.local/bin"
INSTALL_DIR=${LLM_CONTEXT_INSTALL_DIR:-"$DEFAULT_INSTALL_DIR"}
MODEL_CACHE_ROOT=${LLM_CONTEXT_MODEL_CACHE:-"${HOME}/.cache/llm-context/models"}
MODEL_DIR="${MODEL_CACHE_ROOT}/lightonai--LateOn-Code/${MODEL_REVISION}"
ROUTER_MODEL_DIR="${MODEL_CACHE_ROOT}/mixedbread-ai--mxbai-edge-colbert-v0-32m/${ROUTER_MODEL_REVISION}"

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
  DOWNLOAD_CONNECT_TIMEOUT=${LLM_CONTEXT_DOWNLOAD_CONNECT_TIMEOUT:-20}
  DOWNLOAD_LOW_SPEED_LIMIT=${LLM_CONTEXT_DOWNLOAD_LOW_SPEED_LIMIT:-1024}
  DOWNLOAD_LOW_SPEED_TIME=${LLM_CONTEXT_DOWNLOAD_LOW_SPEED_TIME:-60}
  DOWNLOAD_RETRIES=${LLM_CONTEXT_DOWNLOAD_RETRIES:-3}
  download() {
    url=$1
    output=$2
    printf 'Downloading %s\n' "${url##*/}" >&2
    curl --fail --show-error --location --progress-bar \
      --connect-timeout "$DOWNLOAD_CONNECT_TIMEOUT" \
      --speed-limit "$DOWNLOAD_LOW_SPEED_LIMIT" \
      --speed-time "$DOWNLOAD_LOW_SPEED_TIME" \
      --retry "$DOWNLOAD_RETRIES" \
      --retry-delay 2 \
      --retry-connrefused \
      --output "$output" "$url"
  }
elif command -v wget >/dev/null 2>&1; then
  DOWNLOAD_TIMEOUT=${LLM_CONTEXT_DOWNLOAD_TIMEOUT:-20}
  DOWNLOAD_RETRIES=${LLM_CONTEXT_DOWNLOAD_RETRIES:-3}
  download() {
    url=$1
    output=$2
    printf 'Downloading %s\n' "${url##*/}" >&2
    wget --timeout="$DOWNLOAD_TIMEOUT" \
      --tries="$DOWNLOAD_RETRIES" \
      --waitretry=2 \
      --show-progress \
      --output-document "$output" "$url"
  }
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

CUDA_MINIMUM_DRIVER="525.60.13"

version_at_least() {
  awk -F. -v actual="$1" -v minimum="$2" '
    BEGIN {
      split(actual, a, ".")
      split(minimum, b, ".")
      for (i = 1; i <= 3; i++) {
        if ((a[i] + 0) > (b[i] + 0)) exit 0
        if ((a[i] + 0) < (b[i] + 0)) exit 1
      }
      exit 0
    }'
}

nvidia_smi_path() {
  if command -v nvidia-smi >/dev/null 2>&1; then
    command -v nvidia-smi
  elif [ -x /usr/lib/wsl/lib/nvidia-smi ]; then
    printf '%s\n' /usr/lib/wsl/lib/nvidia-smi
  fi
}

cuda_library_present() {
  library=$1
  for candidate in \
    "/usr/lib/wsl/lib/$library" \
    "/usr/local/cuda/lib64/$library" \
    "/usr/lib/x86_64-linux-gnu/$library" \
    "/usr/lib64/$library"; do
    if [ -f "$candidate" ]; then
      return 0
    fi
  done
  if command -v ldconfig >/dev/null 2>&1 &&
     ldconfig -p 2>/dev/null | grep -F "$library" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

cuda_host_preflight() {
  CUDA_NVIDIA_SMI=$(nvidia_smi_path || true)
  CUDA_GPU_NAME=""
  CUDA_DRIVER_VERSION=""
  CUDA_DRIVER_PRESENT=0
  CUDA_DRIVER_COMPATIBLE=0
  CUDA_DEVICE_VISIBLE=0
  CUDA_LIBCUDA_PRESENT=0
  CUDA_RUNTIME_PRESENT=0
  CUDA_CUDNN_PRESENT=0

  if [ -n "$CUDA_NVIDIA_SMI" ]; then
    CUDA_GPU_INFO=$(
      "$CUDA_NVIDIA_SMI" --query-gpu=name,driver_version \
        --format=csv,noheader,nounits 2>/dev/null | sed -n '1p' || true
    )
    CUDA_GPU_NAME=$(printf '%s\n' "$CUDA_GPU_INFO" | sed 's/,[[:space:]].*//')
    CUDA_DRIVER_VERSION=$(printf '%s\n' "$CUDA_GPU_INFO" |
      sed -n 's/^[^,]*,[[:space:]]*//p')
    if [ -n "$CUDA_GPU_NAME" ] && [ -n "$CUDA_DRIVER_VERSION" ]; then
      CUDA_DRIVER_PRESENT=1
      CUDA_DEVICE_VISIBLE=1
      case "$CUDA_DRIVER_VERSION" in
        *[!0-9.]*|'') ;;
        *)
          if version_at_least "$CUDA_DRIVER_VERSION" "$CUDA_MINIMUM_DRIVER"; then
            CUDA_DRIVER_COMPATIBLE=1
          fi
          ;;
      esac
    fi
  fi

  if [ -e /dev/dxg ] || [ -e /dev/nvidia0 ]; then
    CUDA_DEVICE_VISIBLE=1
  fi
  if cuda_library_present libcuda.so.1; then
    CUDA_LIBCUDA_PRESENT=1
  fi
  if cuda_library_present libcudart.so.12; then
    CUDA_RUNTIME_PRESENT=1
  fi
  if cuda_library_present libcudnn.so.9; then
    CUDA_CUDNN_PRESENT=1
  fi

  if [ "$CUDA_DEVICE_VISIBLE" -eq 1 ] &&
     [ "$CUDA_DRIVER_PRESENT" -eq 1 ] &&
     [ "$CUDA_DRIVER_COMPATIBLE" -eq 1 ] &&
     [ "$CUDA_LIBCUDA_PRESENT" -eq 1 ] &&
     [ "$CUDA_RUNTIME_PRESENT" -eq 1 ] &&
     [ "$CUDA_CUDNN_PRESENT" -eq 1 ]; then
    CUDA_HOST_READY=1
  else
    CUDA_HOST_READY=0
  fi
}

print_cuda_host_preflight() {
  printf 'GPU preflight: GPU=%s; device=%s; driver=%s (minimum %s); libcuda=%s; CUDA 12 runtime=%s; cuDNN 9=%s\n' \
    "${CUDA_GPU_NAME:-not detected}" \
    "$(if [ "$CUDA_DEVICE_VISIBLE" -eq 1 ]; then printf visible; else printf missing; fi)" \
    "${CUDA_DRIVER_VERSION:-not detected}" \
    "$CUDA_MINIMUM_DRIVER" \
    "$(if [ "$CUDA_LIBCUDA_PRESENT" -eq 1 ]; then printf present; else printf missing; fi)" \
    "$(if [ "$CUDA_RUNTIME_PRESENT" -eq 1 ]; then printf present; else printf missing; fi)" \
    "$(if [ "$CUDA_CUDNN_PRESENT" -eq 1 ]; then printf present; else printf missing; fi)"
}

print_cuda_host_actions() {
  if [ "${CUDA_DRIVER_PRESENT}" -eq 0 ]; then
    printf 'Action: install an NVIDIA driver; for WSL install the Windows CUDA-enabled driver, not a Linux driver inside WSL.\n'
  elif [ "${CUDA_DRIVER_COMPATIBLE}" -eq 0 ]; then
    printf 'Action: install/update the NVIDIA driver to %s or newer.\n' \
      "$CUDA_MINIMUM_DRIVER"
  fi
  if [ "$CUDA_LIBCUDA_PRESENT" -eq 0 ]; then
    printf 'Action: expose libcuda.so.1 from the NVIDIA driver to this process.\n'
  fi
  if [ "$CUDA_RUNTIME_PRESENT" -eq 0 ]; then
    printf 'Action: install or expose the CUDA 12 runtime (libcudart.so.12).\n'
  fi
  if [ "$CUDA_CUDNN_PRESENT" -eq 0 ]; then
    printf 'Action: install the CPU package first, then run llm-context setup --install-cudnn, or install cuDNN 9 for CUDA 12 using your distribution package manager.\n'
  fi
}

TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/llm-context-install.XXXXXX")
cleanup() { rm -rf -- "$TEMP_DIR"; }
trap cleanup EXIT HUP INT TERM

printf 'Downloading llm-context %s...\n' "$VERSION"
download "$RELEASE_URL/llm-context.jar" "$TEMP_DIR/llm-context.jar"
download "$RELEASE_URL/llm-context.jar.sha256" "$TEMP_DIR/llm-context.jar.sha256"
download "$RELEASE_URL/USER-GUIDE.md" "$TEMP_DIR/USER-GUIDE.md"
download "$RELEASE_URL/USER-GUIDE.md.sha256" "$TEMP_DIR/USER-GUIDE.md.sha256"

EXPECTED_HASH=$(sed -n '1{s/[[:space:]].*//;p;}' "$TEMP_DIR/llm-context.jar.sha256")
ACTUAL_HASH=$(file_hash "$TEMP_DIR/llm-context.jar")

[ -n "$EXPECTED_HASH" ] && [ "$EXPECTED_HASH" = "$ACTUAL_HASH" ] ||
  fail "release checksum verification failed"
GUIDE_EXPECTED_HASH=$(sed -n '1{s/[[:space:]].*//;p;}' \
  "$TEMP_DIR/USER-GUIDE.md.sha256")
[ -n "$GUIDE_EXPECTED_HASH" ] &&
  [ "$GUIDE_EXPECTED_HASH" = "$(file_hash "$TEMP_DIR/USER-GUIDE.md")" ] ||
  fail "user guide checksum verification failed"

INSTALL_SEMANTIC=1
case "${LLM_CONTEXT_SKIP_SEMANTIC:-0}" in
  1|true|TRUE|yes|YES) INSTALL_SEMANTIC=0 ;;
esac

RUNTIME_FLAVOR_REQUESTED=${LLM_CONTEXT_ACCELERATOR_PACKAGE:-auto}
case "$RUNTIME_FLAVOR_REQUESTED" in
  cpu|cuda|auto) ;;
  *) fail "LLM_CONTEXT_ACCELERATOR_PACKAGE must be auto, cpu, or cuda" ;;
esac

RUNTIME_FLAVOR=cpu
if [ "$INSTALL_SEMANTIC" -eq 1 ]; then
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64|Linux:amd64)
      if [ "$RUNTIME_FLAVOR_REQUESTED" = "cuda" ]; then
        cuda_host_preflight
        print_cuda_host_preflight
        [ "$CUDA_HOST_READY" -eq 1 ] || {
          print_cuda_host_actions
          fail "CUDA was requested but the host preflight is incomplete; fix the reported prerequisites or set LLM_CONTEXT_ACCELERATOR_PACKAGE=cpu"
        }
      elif [ "$RUNTIME_FLAVOR_REQUESTED" = "auto" ]; then
        cuda_host_preflight
        print_cuda_host_preflight
        if [ "$CUDA_HOST_READY" -eq 1 ]; then
          RUNTIME_FLAVOR=cuda
          printf 'GPU preflight passed; installing the CUDA-enabled semantic runtime.\n'
        else
          printf 'GPU preflight did not pass; installing the CPU semantic runtime.\n'
          print_cuda_host_actions
        fi
      fi
      ;;
    *)
      if [ "$RUNTIME_FLAVOR_REQUESTED" = "cuda" ]; then
        fail "the packaged CUDA runtime is currently available only for Linux x86_64"
      fi
      ;;
  esac
fi

if [ "$RUNTIME_FLAVOR" = "cuda" ]; then
  RUNTIME_SUFFIX="-cuda"
else
  RUNTIME_SUFFIX=""
fi

if [ "$INSTALL_SEMANTIC" -eq 1 ]; then
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64|Linux:amd64)
      NEXT_PLAID_TARGET="x86_64-unknown-linux-gnu" ;;
    Darwin:arm64|Darwin:aarch64)
      NEXT_PLAID_TARGET="aarch64-apple-darwin" ;;
    *)
      fail "LateOn runtime is not packaged for $(uname -s) $(uname -m); rerun with LLM_CONTEXT_SKIP_SEMANTIC=1"
      ;;
  esac
  NEXT_PLAID_ARCHIVE="next-plaid-api-${NEXT_PLAID_VERSION}-${NEXT_PLAID_TARGET}${RUNTIME_SUFFIX}.tar.gz"
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

  if [ -z "${LLM_CONTEXT_MODEL_MANIFEST:-}" ]; then
  if verify_hash "$MODEL_DIR/model.onnx" \
       "75f8f308994224ac88d580d5a37b68e94bd78be4887b7beb8578ed8b30bad242" &&
     verify_hash "$MODEL_DIR/model_int8.onnx" \
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
    printf 'Downloading pinned LateOn-Code FP32 and INT8 models (about 747 MB)...\n'
    download "$MODEL_URL_BASE/model.onnx?download=true" \
      "$TEMP_DIR/model/model.onnx"
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
    verify_hash "$TEMP_DIR/model/model.onnx" \
      "75f8f308994224ac88d580d5a37b68e94bd78be4887b7beb8578ed8b30bad242" &&
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

  if verify_hash "$ROUTER_MODEL_DIR/model.onnx" \
       "886e3a1638af8222613a8b3baf73520d5ab8c8275fc5ea16e3166982d01df24e" &&
     verify_hash "$ROUTER_MODEL_DIR/model_int8.onnx" \
       "264ba680e960af9fffb4f78c3af1e4ff92520678b8e136c79434d88fb2549e1b" &&
     verify_hash "$ROUTER_MODEL_DIR/tokenizer.json" \
       "594291000b476c98ed600cbb1914ff128c79642a9433aac86213c7a5562d7c1a" &&
     verify_hash "$ROUTER_MODEL_DIR/config_sentence_transformers.json" \
       "0c4eb4090ff55ddee69380ad5ea88a3a89500651996a56953af72bafdb7965b6" &&
     verify_hash "$ROUTER_MODEL_DIR/config.json" \
       "a60a035a715a686dca530cf41da553a571e26ea45288d04d750b9da1a27c268d" &&
     verify_hash "$ROUTER_MODEL_DIR/onnx_config.json" \
       "e10f017e4a8355f6b15f5be5f67295c90d5b25e487568bf0b0d9ee3259dc0eb7"; then
    ROUTER_MODEL_READY=1
    printf 'Using verified Mixedbread query router at %s\n' "$ROUTER_MODEL_DIR"
  else
    ROUTER_MODEL_READY=0
    ROUTER_MODEL_URL_BASE=${LLM_CONTEXT_QUERY_ROUTER_MODEL_URL:-"https://huggingface.co/${ROUTER_MODEL_ID}/resolve/${ROUTER_MODEL_REVISION}"}
    mkdir -p "$TEMP_DIR/router-model"
    printf 'Downloading pinned Mixedbread FP32 and INT8 query router (about 165 MB)...\n'
    download "$ROUTER_MODEL_URL_BASE/model.onnx?download=true" \
      "$TEMP_DIR/router-model/model.onnx"
    download "$ROUTER_MODEL_URL_BASE/model_int8.onnx?download=true" \
      "$TEMP_DIR/router-model/model_int8.onnx"
    download "$ROUTER_MODEL_URL_BASE/tokenizer.json?download=true" \
      "$TEMP_DIR/router-model/tokenizer.json"
    download "$ROUTER_MODEL_URL_BASE/config_sentence_transformers.json?download=true" \
      "$TEMP_DIR/router-model/config_sentence_transformers.json"
    download "$ROUTER_MODEL_URL_BASE/config.json?download=true" \
      "$TEMP_DIR/router-model/config.json"
    download "$ROUTER_MODEL_URL_BASE/onnx_config.json?download=true" \
      "$TEMP_DIR/router-model/onnx_config.json"
    verify_hash "$TEMP_DIR/router-model/model.onnx" \
      "886e3a1638af8222613a8b3baf73520d5ab8c8275fc5ea16e3166982d01df24e" &&
    verify_hash "$TEMP_DIR/router-model/model_int8.onnx" \
      "264ba680e960af9fffb4f78c3af1e4ff92520678b8e136c79434d88fb2549e1b" &&
    verify_hash "$TEMP_DIR/router-model/tokenizer.json" \
      "594291000b476c98ed600cbb1914ff128c79642a9433aac86213c7a5562d7c1a" &&
    verify_hash "$TEMP_DIR/router-model/config_sentence_transformers.json" \
      "0c4eb4090ff55ddee69380ad5ea88a3a89500651996a56953af72bafdb7965b6" &&
    verify_hash "$TEMP_DIR/router-model/config.json" \
      "a60a035a715a686dca530cf41da553a571e26ea45288d04d750b9da1a27c268d" &&
    verify_hash "$TEMP_DIR/router-model/onnx_config.json" \
      "e10f017e4a8355f6b15f5be5f67295c90d5b25e487568bf0b0d9ee3259dc0eb7" ||
      fail "Mixedbread query-router model checksum verification failed"
  fi
  else
    MODEL_READY=1
    ROUTER_MODEL_READY=1
  fi
fi

mkdir -p "$INSTALL_DIR"
cp "$TEMP_DIR/llm-context.jar" "$INSTALL_DIR/.llm-context.jar.new"
mv -f "$INSTALL_DIR/.llm-context.jar.new" "$INSTALL_DIR/llm-context.jar"
cp "$TEMP_DIR/USER-GUIDE.md" "$INSTALL_DIR/.USER-GUIDE.md.new"
mv -f "$INSTALL_DIR/.USER-GUIDE.md.new" "$INSTALL_DIR/USER-GUIDE.md"

cat >"$INSTALL_DIR/.llm-context.new" <<'LAUNCHER'
#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LLM_CONTEXT_INSTALL_DIR="$SCRIPT_DIR"
LLM_CONTEXT_MODEL_REGISTRY="$SCRIPT_DIR/models.edn"
export LLM_CONTEXT_INSTALL_DIR
export LLM_CONTEXT_MODEL_REGISTRY
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
  for provider in "$TEMP_DIR/next-plaid"/libonnxruntime_providers_*.so; do
    [ -f "$provider" ] || continue
    cp "$provider" "$INSTALL_DIR/$(basename "$provider")"
  done
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
  if [ "$ROUTER_MODEL_READY" -eq 0 ]; then
    ROUTER_MODEL_PARENT=$(dirname "$ROUTER_MODEL_DIR")
    ROUTER_MODEL_STAGED="${ROUTER_MODEL_DIR}.new.$$"
    ROUTER_MODEL_BACKUP="${ROUTER_MODEL_DIR}.previous.$$"
    mkdir -p "$ROUTER_MODEL_PARENT"
    mkdir "$ROUTER_MODEL_STAGED"
    cp "$TEMP_DIR/router-model/"* "$ROUTER_MODEL_STAGED/"
    if [ -d "$ROUTER_MODEL_DIR" ]; then
      mv "$ROUTER_MODEL_DIR" "$ROUTER_MODEL_BACKUP"
    fi
    if mv "$ROUTER_MODEL_STAGED" "$ROUTER_MODEL_DIR"; then
      if [ -d "$ROUTER_MODEL_BACKUP" ]; then
        rm -rf -- "$ROUTER_MODEL_BACKUP"
      fi
    else
      if [ -d "$ROUTER_MODEL_BACKUP" ]; then
        mv "$ROUTER_MODEL_BACKUP" "$ROUTER_MODEL_DIR"
      fi
      fail "could not install the Mixedbread query-router model snapshot"
    fi
  fi
fi

MODEL_ROLES=${LLM_CONTEXT_MODEL_ROLES:-}
if [ -z "$MODEL_ROLES" ] && [ "$INSTALL_SEMANTIC" -eq 1 ]; then
  MODEL_ROLES="semantic-retriever,query-router-reranker"
fi
if [ -n "$MODEL_ROLES" ]; then
  set -- models install --cache "$MODEL_CACHE_ROOT" \
    --registry "$INSTALL_DIR/models.edn" --roles "$MODEL_ROLES"
  if [ -n "${LLM_CONTEXT_MODEL_MANIFEST:-}" ]; then
    [ -n "${LLM_CONTEXT_MODEL_MANIFEST_SHA256:-}" ] ||
      fail "LLM_CONTEXT_MODEL_MANIFEST_SHA256 is required for a custom model manifest"
    set -- "$@" --manifest "$LLM_CONTEXT_MODEL_MANIFEST" \
      --manifest-sha256 "$LLM_CONTEXT_MODEL_MANIFEST_SHA256"
  fi
  java --enable-native-access=ALL-UNNAMED -jar "$INSTALL_DIR/llm-context.jar" "$@"
fi

INSTALLED_VERSION=$("$INSTALL_DIR/llm-context" version)
printf 'Installed llm-context %s at %s\n' "$INSTALLED_VERSION" "$INSTALL_DIR/llm-context"
printf 'Installed user guide at %s\n' "$INSTALL_DIR/USER-GUIDE.md"
if [ "$INSTALL_SEMANTIC" -eq 1 ]; then
  printf 'Installed NextPlaid API %s, LateOn-Code at %s, and query router at %s\n' \
    "$NEXT_PLAID_VERSION" "$MODEL_DIR" "$ROUTER_MODEL_DIR"
fi
printf 'Run %s setup to inspect GPU/CUDA prerequisites, or %s doctor to check the complete installation.\n' \
  "$INSTALL_DIR/llm-context" "$INSTALL_DIR/llm-context"

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
