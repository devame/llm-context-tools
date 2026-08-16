# ARD: Verifiable accelerator selection for NextPlaid inference

Date: 2026-08-16  
Status: accepted and implemented

## Context

The canonical graph rebuild and semantic synchronization are different
pipelines. Language analysis, graph transactions, lease state, document
preparation, and index persistence are CPU/storage work. The ONNX encoding step
inside NextPlaid is the only current stage that can use CUDA.

The host exposes a GTX 1660 through WSL (`/dev/dxg`), but the installed
NextPlaid package contains only the CPU ONNX Runtime. Treating GPU visibility as
CUDA readiness would therefore cause startup failures or misleading status.
GPU mode also requires `model.onnx`; CPU INT8 uses `model_int8.onnx`.

## Decision

Both NextPlaid-backed roles expose independent `:accelerator` and
`:quantization` controls:

- `:accelerator` is `:auto`, `:cpu`, or `:cuda`.
- `:quantization` is `:auto`, `:int8`, or `:fp32`.
- `:auto/:auto` chooses CUDA/FP32 only when the device, CUDA provider,
  shared provider, and verified FP32 model are all present.
- Otherwise `:auto` chooses CPU/INT8 and publishes every fallback reason.
- Explicit CUDA fails closed if a prerequisite is missing.
- CUDA plus INT8 is rejected during configuration validation.
- The semantic retriever defaults to auto selection.
- The 2,048-token retriever uses one CUDA session and batch size one so CPU
  tuning cannot exhaust a 6 GB GPU. The short-document router has a separate
  batch default.
- CUDA also defaults to one concurrent update request; CPU keeps four. Request
  concurrency can otherwise multiply GPU workspace use despite one ONNX
  session.
- The small query router/reranker defaults to CPU/INT8 but remains
  independently configurable.

The model package manifest pins and verifies both FP32 and INT8 ONNX files.
Unverified artifacts remain rejected. Runtime status includes the requested
and effective device, precision, and auto-fallback reasons.

## Why not GPU-enable the entire indexing pipeline?

The remaining stages do not consist of large tensor operations. Moving graph
facts, Datalevin transactions, or index metadata through a GPU would add data
transfer and coordination without replacing their CPU/storage algorithms.
Those stages should instead be improved through bounded transactions, batching,
concurrency qualification, fewer duplicate documents, and incremental work.

## Runtime qualification

CUDA is considered locally eligible only when:

1. `/dev/dxg` or `/dev/nvidia0` is visible.
2. `libonnxruntime_providers_cuda.so` is beside `next-plaid-api`.
3. `libonnxruntime_providers_shared.so` is beside it.
4. cuDNN 9 is found in a configured or standard runtime directory.
5. The selected verified model directory contains `model.onnx`.

These checks are necessary but cannot prove every dynamic dependency or kernel
compatibility. NextPlaid startup remains the final health check, and its log is
the diagnostic authority. A production package must ship a CUDA-enabled
NextPlaid binary, compatible ONNX Runtime GPU providers, and their pinned CUDA
and cuDNN dependencies.

## Operational consequences

- Existing CPU installations continue safely under `:auto`; they report why
  CUDA was not selected.
- Installing only a GPU driver does not silently change execution.
- A CUDA deployment is observable and cannot fall back when explicitly
  requested.
- GPU benefit must be measured on encoding throughput and end-to-end semantic
  synchronization separately. Overall acceleration is bounded by the
  non-encoding portion of the pipeline.

## Rejected alternatives

- Detect only `nvidia-smi`: it proves device/driver visibility, not a usable
  ONNX CUDA provider.
- Always pass `--cuda`: breaks CPU installations and missing FP32 packages.
- Silently fall back for explicit CUDA: hides deployment errors.
- GPU-enable the 32M router by default: likely increases latency for tiny,
  one-query batches; users can override after benchmarking.
- Replace NextPlaid with an offline GPU batch indexer: it would weaken the
  current incremental, lease, visibility, and recovery contract.
