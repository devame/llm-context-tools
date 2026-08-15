# Query-shape model evaluation — 2026-08-15

## Decision

No evaluated model is accurate enough to make a hard `lookup` / `set` / `flow`
decision that filters candidates. Remove the phrase router from the production
path, but do not replace it with another hard gate yet.

The safe near-term design is:

1. honor an explicit caller override;
2. retrieve a bounded, diverse candidate pool without shape filtering;
3. infer evidence shape from both the question and retrieved graph structure;
4. allow an experimental learned router to contribute only an advisory prior;
5. never discard candidates solely because of that prior.

## Implemented follow-up

The 32M Mixedbread checkpoint is now integrated in exactly that advisory role.
It runs as a pinned 33 MB INT8 ONNX model in a second resident NextPlaid
process. Automatic retrieval remains shape-neutral and broad; the model runs
concurrently, and its suggestion is accepted only when retrieved structure
supports the requested evidence shape. Explicit caller overrides win, and any
model failure, timeout, or unsupported suggestion leaves an adaptive plan.
The exact ONNX runtime was then calibrated on the frozen 72-case corpus. A
0.02 top-two score margin accepts 35 cases at 97.1% accuracy; lower-margin
results remain visible in provenance but cannot resolve the plan.

## Scope

The frozen corpus contains 72 repository-neutral English code questions, evenly
split across `lookup`, `set`, and `flow`. Each class contains six direct, six
paraphrased, six terse, and six contrastive questions. This evaluates query
wording only. It does not measure retrieval recall, graph traversal, or final
answer quality.

The machine was a six-core Intel i5-9400F under WSL2 with 23 GiB RAM and no GPU.
Warm latency excludes the first query but includes query encoding or generation.
Model-loading time is included only in wall time, not warm-query percentiles.

## Results

| Candidate | Intended use in this test | Accuracy | Warm p50 | Warm p95 | Result |
| --- | --- | ---: | ---: | ---: | --- |
| Legacy phrase rules | Existing hard router | 41.7% | negligible | negligible | Reject |
| Liquid LFM2.5 350M Prompt Router, descriptive lanes | Zero-shot router | 76.4% | 318 ms | 661 ms | Not qualified |
| Liquid LFM2.5 350M Prompt Router, selected question lanes | Zero-shot router; 18-case development selection | 75.9% held-out | 134 ms | 393 ms | Not qualified |
| Mixedbread mxbai-edge-colbert-v0-32m | Semantic route matching | 76.4% | 22.8 ms | 37.0 ms | Fast, but not qualified |
| Reason-mxbai-colbert-v0.1-32m | Reasoning-oriented semantic route matching | 68.1% | 24.4 ms | 43.4 ms | Wrong objective for routing |
| LightOn LateOn-Code-edge | Code-oriented semantic route matching | 66.7% | 14.4 ms | 28.4 ms | Wrong objective for routing |
| LightOn Agent-ModernColBERT | Agentic semantic route matching | **79.2%** | 32.3 ms | 52.0 ms | Best accuracy, still not qualified |
| LightOn Agent-ModernColBERT, required AgentIR query format | Agentic semantic route matching | 47.2% | 39.1 ms | 64.2 ms | Contract is mismatched to routing |
| Liquid LFM2.5 1.2B Instruct Q4_K_M, zero-shot | Generative router | 34.7% | 863 ms | 1,323 ms | Reject |
| Liquid LFM2.5 1.2B Instruct Q4_K_M, three-shot | Generative router | 54.2% | 1,081 ms | 1,474 ms | Reject |

The selected Prompt Router lane wording tied the descriptive profile at 83.3%
on the 18 direct development cases. It then reached only 75.9% on the untouched
54 paraphrase, terse, and contrastive cases. Lane wording therefore did not
solve the generalization problem.

The full-precision 1.2B Instruct PyTorch run was stopped after its first batch
failed to complete in a practical interval. The reported Instruct measurements
use Liquid's official Q4_K_M GGUF with llama.cpp, which is representative of a
CPU deployment.

## Failure patterns

- The phrase router defaults unfamiliar set and flow wording to `lookup`.
- The Liquid Prompt Router over-predicts `set` for single-location questions,
  including some explicit uses of “single” and “canonical.” Several wrong
  predictions carried high scores, so its raw score is not a safe confidence.
- The retrieval encoders understand process language well but confuse evidence
  cardinality. Their training objective is relevance, not query-plan shape.
- The 1.2B Instruct model is highly prompt-sensitive. Balanced demonstrations
  changed its dominant error from `lookup` to `flow` without producing adequate
  accuracy.
- Terse wording is the hardest group for most candidates.

## Model-specific conclusions

### Liquid AI

`LFM2.5-Encoder-350M-Prompt-Router` is the correct model *type* for this task and
is the best candidate for a later routing-specific fine-tune. Its current
zero-shot quality is insufficient, its published checkpoint is F32, and loading
it requires `trust_remote_code=True`. Adoption would require a pinned revision,
reviewed model code, quantized CPU/ARM artifacts, calibration, and a materially
larger held-out corpus.

`LFM2.5-1.2B-Instruct` is slower and substantially less accurate here. The
Thinking checkpoint is not warranted for a three-class decision when the
Instruct checkpoint already fails the simpler contract.

### Mixedbread

The open 32M edge ColBERT is an excellent latency/size option for retrieval or
bounded candidate reranking. It is not reliable as a query-shape classifier.
The newer `mxbai-rerank-v3.1-listwise` is a managed listwise reranker, not a
local classifier; it was not called because no Mixedbread API credential was
available and testing it as the hard router would conflate two different jobs.

### LightOn

`Agent-ModernColBERT` produced the best raw-query score, but 79.2% still means
roughly one wrong plan in five. Applying the exact AgentIR reasoning-plus-query
format required by its model card reduced routing accuracy to 47.2% because the
retrieval instruction itself makes all three route labels look process-oriented.
That confirms a task-contract mismatch rather than qualifying the raw-query
workaround. The model remains useful for reasoning-aware retrieval experiments,
not as the hard query router. `LateOn-Code-edge` remains attractive for very
cheap code retrieval, but its 66.7% routing result confirms that code relevance
does not imply evidence-shape understanding.

## Reproduction

Run the dependency-free baseline:

```sh
python3 evaluation/query-routing/evaluate.py --backend rules
```

Run all model-backed evaluations through the script's PEP 723 environment:

```sh
uv run evaluation/query-routing/evaluate.py --backend liquid-router
uv run evaluation/query-routing/evaluate.py --backend colbert --model mixedbread-ai/mxbai-edge-colbert-v0-32m
uv run evaluation/query-routing/evaluate.py --backend colbert --model DataScience-UIBK/Reason-mxbai-colbert-v0.1-32m
uv run evaluation/query-routing/evaluate.py --backend colbert --model lightonai/LateOn-Code-edge
uv run evaluation/query-routing/evaluate.py --backend colbert --model lightonai/Agent-ModernColBERT
uv run evaluation/query-routing/evaluate.py --backend colbert --model lightonai/Agent-ModernColBERT --query-format agentir
uv run evaluation/query-routing/evaluate.py --backend llama
```

Model downloads are cached outside the repository. The evaluation does not
modify the Metabase index.
