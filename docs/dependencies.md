# Dependency registry

The authoritative dependency and artifact inventory is
[`resources/llm_context/dependencies.edn`](../resources/llm_context/dependencies.edn).
It is packaged with the application because the model contract is needed at
runtime. The top-level `:roles` entries contain the verified model revisions,
entrypoints, and SHA-256 hashes.

Run the consistency check from the repository root with:

```sh
clojure -M script/verify-dependencies.clj
```

The release build runs this check automatically. It compares the manifest with
`deps.edn`, build/version files, the Unix and Windows installers, release CI,
the native grammar builder, and the checked-in Janet catalog. Runtime code
loads the same manifest instead of maintaining a second copy of model and
host-runtime pins.

## Current dependency groups

| Group | Pinned contract |
| --- | --- |
| JVM runtime | Clojure 1.12.5, clj-kondo 2026.08.04, data.json 2.5.2, tools.reader 1.6.0, Datalevin Embedded 1.0.2, JTreeSitter 0.26.1, Tree-sitter Java/native 0.26.6 |
| JVM test/build | Cognitect test-runner tag `v0.5.1` at `dfb30dd`; tools.build 0.10.14 |
| Java/Node | Java 23 minimum and compile target; CI uses Java 25 and Node 24 |
| Janet/native parser | Janet catalog 1.41.2; Tree-sitter ABI 14; Janet grammar source revision and archive hash; Zig 0.15.1 minimum; local WSL/Linux build plus CI-built ARM Linux/macOS/Windows artifacts |
| Semantic runtime | NextPlaid 1.7.0 at its release-source revision; ONNX Runtime 1.29.0 with per-platform archive hashes |
| CUDA host | CUDA 12 runtime package `cuda-cudart-12-9`, cuDNN 9 package `cudnn9-cuda-12`, NVIDIA driver 525.60.13 minimum |
| Models | LateOn-Code, mxbai edge ColBERT router, and LFM2.5 GGUF at immutable Hugging Face revisions with per-file hashes |
| npm | No runtime or development npm dependencies; npm is used only to package the launcher and release artifacts |
| Host tools | Clojure CLI, Java, npm, Zig, curl/wget, tar, checksum tools, and platform installer utilities as listed in the manifest |

The GitHub Actions and Rust release-toolchain pins are also recorded in the
manifest. The dependency installer checks these pins against their current
upstream releases before installing. The Janet grammar source is intentionally
held at the verified ABI-14 revision: the newest upstream grammar tag currently
advertises ABI 13 and cannot safely replace it without rebuilding and testing
the packaged parser contract.

Zig binaries are selected from Zig's official download index rather than from
GitHub release assets, because GitHub may publish only the bootstrap source
archive. The installer selects the newest stable Linux binary, verifies its
published SHA-256, and caches it by version.

Use the repository command for a checked, recoverable setup:

```sh
scripts/install-dependencies.sh install
```

Add `--with-native` to build the verified native parser library for the current
Linux host. GitHub Actions builds the non-local parser variants and the release
workflow overlays those CI artifacts onto the local Linux artifact. Add
`--with-models` to download and hash-verify the semantic model packages.
The command reuses Node/npm already on `PATH`, standard nvm/fnm/asdf/Volta/mise
locations, and Windows Node installations exposed through WSL. It also keeps
downloaded Linux Node releases under the versioned
`$LLM_CONTEXT_TOOLCHAIN_DIR/node/<version>` directory and points `current` at
the verified release, so an interrupted rerun does not discard a good tool.
For a shared installation in another location, set `LLM_CONTEXT_NODE_BIN` and
`LLM_CONTEXT_NPM_BIN`; set `LLM_CONTEXT_ZIG_BIN` for a shared Zig binary. They
are added to the command's `PATH` before any bootstrap decision is made. On
WSL, the runnable `npm` wrapper is preferred over `npm.cmd` when both are
present.
