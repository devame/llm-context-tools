# Accelerator host preflight and setup

## Decision

The packaged installer and the CLI expose the same CUDA host preflight. On
Linux x86-64, the installer defaults to `auto`: it selects the CUDA package
only when it can see an NVIDIA GPU, read a compatible driver version, and
locate `libcuda.so.1`, CUDA 12's `libcudart.so.12`, and cuDNN 9. A failed
preflight selects the CPU package and prints corrective actions. Explicit
`LLM_CONTEXT_ACCELERATOR_PACKAGE=cuda` fails before downloading the semantic
runtime when those checks fail.

`llm-context setup` is the interactive version of the same host report. It may
offer the supported Debian/Ubuntu cuDNN 9 package, but only after interactive
confirmation or an explicit `--yes`. It never installs a GPU driver. Native
Linux driver installation is distribution and hardware specific; under WSL,
the driver belongs on Windows.

## Rationale and boundary

Static host checks make packaging failures visible before a large model and
runtime download. They are not proof that ONNX Runtime can initialize CUDA:
WSL device forwarding, provider loading, and process-level library resolution
can still fail. The semantic service log remains authoritative, and `doctor`
and `semantic status` surface that runtime diagnostic separately.

The Windows installer remains CPU-only until a verified Windows CUDA asset is
published. It still detects and reports an NVIDIA GPU so users are directed to
the supported Linux/WSL path instead of assuming the CPU package can use it.

## Alternatives rejected

- Automatically installing NVIDIA drivers: unsafe across native Linux and WSL
  environments, and can conflict with the host's package or Windows driver.
- Treating `nvidia-smi` alone as CUDA readiness: it proves driver visibility,
  not CUDA runtime, cuDNN, or ONNX provider compatibility.
- Silently falling back from explicit CUDA to CPU: hides a deployment error;
  automatic fallback is limited to the installer's `auto` mode.
