# /// script
# requires-python = ">=3.11"
# dependencies = [
#   "numpy>=2.0",
#   "torch>=2.7",
#   "transformers>=4.57",
#   "pylate>=1.3.4",
#   "llama-cpp-python>=0.3.34",
# ]
# ///
"""Evaluate query-shape routing without using repository-specific vocabulary."""

from __future__ import annotations

import argparse
import json
import re
import time
from collections import Counter, defaultdict
from pathlib import Path


LABELS = ("lookup", "set", "flow")
ROUTES = {
    "lookup": "Find one principal code definition, implementation, owner, or source-of-truth location.",
    "set": "Find a bounded collection of distinct code elements, modules, implementations, or locations.",
    "flow": "Explain an ordered process by following execution, calls, data movement, or other code relationships.",
}
ROUTE_PROFILES = {
    "descriptive": ROUTES,
    "concise": {
        "lookup": "One code location.",
        "set": "Multiple distinct code locations.",
        "flow": "A sequence of connected code operations.",
    },
    "answer": {
        "lookup": "Answer by pointing to the single best code symbol.",
        "set": "Answer with a list of several distinct code symbols.",
        "flow": "Answer with a step-by-step path through connected code symbols.",
    },
    "questions": {
        "lookup": "Where is the main implementation?",
        "set": "Which code elements participate?",
        "flow": "How does execution proceed from start to finish?",
    },
    "cardinality": {
        "lookup": "One: one main definition, owner, implementation, or source of truth.",
        "set": "Many: several modules, handlers, implementations, participants, or places.",
        "flow": "Sequence: events over time, end-to-end behavior, calls, or data movement.",
    },
}
DEMONSTRATIONS = [
    {"role": "user", "content": "Which function owns the default page size?"},
    {"role": "assistant", "content": "lookup"},
    {"role": "user", "content": "Show every serializer implementation."},
    {"role": "assistant", "content": "set"},
    {"role": "user", "content": "Walk through file upload from request to storage."},
    {"role": "assistant", "content": "flow"},
]

SET_RE = re.compile(
    r"\b(?:all|list|which|what)\b.*\b(?:apis|classes|components|endpoints|files|functions|handlers|implementations|modules|namespaces|routes|symbols|tests)\b"
    r"|\b(?:modules|namespaces|components)\s+(?:expose|implement|provide)\b",
    re.I,
)
FLOW_RE = re.compile(
    r"\b(?:before|after|flow|pipeline|responsible for|end[- ]to[- ]end|how.+(?:handled|processed|validated|sent|delivered|enforced))\b",
    re.I,
)


def load_cases(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def predict_rules(cases):
    predictions = []
    for case in cases:
        query = case["query"]
        label = "set" if SET_RE.search(query) else "flow" if FLOW_RE.search(query) else "lookup"
        predictions.append({"label": label, "score": None})
    return predictions


def predict_liquid_router(cases, model_id, routes):
    from transformers import AutoModel, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)
    model = AutoModel.from_pretrained(model_id, trust_remote_code=True).eval()
    route_text = list(routes.values())
    reverse = {description: label for label, description in routes.items()}
    predictions = []
    latencies = []
    for case in cases:
        started = time.perf_counter()
        ranked = model.route(case["query"], route_text, tokenizer=tokenizer)
        latencies.append((time.perf_counter() - started) * 1000)
        predictions.append({"label": reverse[ranked[0]["route"]], "score": ranked[0]["score"]})
    return predictions, latencies


def predict_colbert(cases, model_id, query_format):
    import torch
    from pylate import models, rank

    model = models.ColBERT(model_name_or_path=model_id)
    route_text = list(ROUTES.values())
    route_ids = list(ROUTES.keys())
    document_embeddings = model.encode(route_text, is_query=False, show_progress_bar=False)
    predictions = []
    latencies = []
    for case in cases:
        started = time.perf_counter()
        query = case["query"]
        if query_format == "agentir":
            query = (
                "Instruct: Given a user's reasoning followed by a web search query, retrieve relevant passages "
                "that answer the query while incorporating the user's reasoning\\nQuery:"
                f"Reasoning: Empty\n\nQuery: {query}"
            )
        query_embeddings = model.encode([query], is_query=True, show_progress_bar=False)
        ranked = rank.rerank(
            documents_ids=[route_ids],
            queries_embeddings=query_embeddings,
            documents_embeddings=[document_embeddings],
        )[0]
        latencies.append((time.perf_counter() - started) * 1000)
        first = ranked[0]
        predictions.append({"label": first["id"], "score": float(first["score"])})
    del model
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    return predictions, latencies


def predict_instruct(cases, model_id, batch_size):
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(model_id)
    tokenizer.padding_side = "left"
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token
    model = AutoModelForCausalLM.from_pretrained(model_id, torch_dtype="auto").eval()
    predictions = []
    latencies = []
    system = (
        "Classify a code question by the evidence it asks for. "
        "lookup means one principal definition or implementation; set means a bounded collection of distinct code elements; "
        "flow means an ordered process requiring code relationships. Reply with exactly lookup, set, or flow."
    )
    prompts = [
        tokenizer.apply_chat_template(
            [{"role": "system", "content": system}, *DEMONSTRATIONS, {"role": "user", "content": case["query"]}],
            add_generation_prompt=True,
            tokenize=False,
        )
        for case in cases
    ]
    for offset in range(0, len(prompts), batch_size):
        batch = prompts[offset:offset + batch_size]
        inputs = tokenizer(batch, return_tensors="pt", padding=True)
        started = time.perf_counter()
        with torch.inference_mode():
            output = model.generate(**inputs, max_new_tokens=5, do_sample=False, pad_token_id=tokenizer.eos_token_id)
        elapsed_per_query = (time.perf_counter() - started) * 1000 / len(batch)
        latencies.extend([elapsed_per_query] * len(batch))
        answers = tokenizer.batch_decode(output[:, inputs["input_ids"].shape[-1]:], skip_special_tokens=True)
        for answer in answers:
            answer = answer.strip().lower()
            match = re.search(r"\b(lookup|set|flow)\b", answer)
            predictions.append({"label": match.group(1) if match else "invalid", "score": None, "raw": answer})
    return predictions, latencies


def predict_llama(cases, repo_id, filename):
    from huggingface_hub import hf_hub_download
    from llama_cpp import Llama

    model_path = hf_hub_download(repo_id=repo_id, filename=filename)
    model = Llama(model_path=model_path, n_ctx=1024, n_threads=6, verbose=False)
    system = (
        "Classify a code question by the evidence it asks for. "
        "lookup means one principal definition or implementation; set means a bounded collection of distinct code elements; "
        "flow means an ordered process requiring code relationships. Reply with exactly lookup, set, or flow."
    )
    predictions = []
    latencies = []
    for case in cases:
        started = time.perf_counter()
        response = model.create_chat_completion(
            messages=[{"role": "system", "content": system}, *DEMONSTRATIONS, {"role": "user", "content": case["query"]}],
            max_tokens=4,
            temperature=0,
        )
        latencies.append((time.perf_counter() - started) * 1000)
        answer = response["choices"][0]["message"]["content"].strip().lower()
        match = re.search(r"\b(lookup|set|flow)\b", answer)
        predictions.append({"label": match.group(1) if match else "invalid", "score": None, "raw": answer})
    return predictions, latencies


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, round((len(ordered) - 1) * fraction))]


def summarize(cases, predictions, latencies, model_name, wall_ms):
    confusion = {label: Counter() for label in LABELS}
    groups = defaultdict(lambda: [0, 0])
    failures = []
    for case, prediction in zip(cases, predictions):
        actual = case["label"]
        predicted = prediction["label"]
        confusion[actual][predicted] += 1
        groups[case["group"]][1] += 1
        if actual == predicted:
            groups[case["group"]][0] += 1
        else:
            failures.append({**case, "predicted": predicted, "score": prediction.get("score"), "raw": prediction.get("raw")})
    correct = len(cases) - len(failures)
    return {
        "model": model_name,
        "cases": len(cases),
        "accuracy": correct / len(cases),
        "group_accuracy": {group: hits / total for group, (hits, total) in sorted(groups.items())},
        "confusion": {label: dict(counts) for label, counts in confusion.items()},
        "latency_ms": {
            "wall_total": wall_ms,
            "warm_query_p50": percentile(latencies[1:], 0.5),
            "warm_query_p95": percentile(latencies[1:], 0.95),
            "first_query": latencies[0] if latencies else None,
        },
        "failures": failures,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", choices=("rules", "liquid-router", "colbert", "instruct", "llama"), required=True)
    parser.add_argument("--model")
    parser.add_argument("--filename", default="LFM2.5-1.2B-Instruct-Q4_K_M.gguf")
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--route-profile", choices=tuple(ROUTE_PROFILES), default="descriptive")
    parser.add_argument("--query-format", choices=("raw", "agentir"), default="raw")
    parser.add_argument("--groups", nargs="*")
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.json"))
    args = parser.parse_args()
    cases = load_cases(args.cases)
    if args.groups:
        cases = [case for case in cases if case["group"] in set(args.groups)]
    started = time.perf_counter()
    if args.backend == "rules":
        predictions, latencies = predict_rules(cases), []
        model_name = "legacy-rules"
    elif args.backend == "liquid-router":
        model_id = args.model or "LiquidAI/LFM2.5-Encoder-350M-Prompt-Router"
        model_name = f"{model_id}:{args.route_profile}"
        predictions, latencies = predict_liquid_router(cases, model_id, ROUTE_PROFILES[args.route_profile])
    elif args.backend == "colbert":
        if not args.model:
            parser.error("--model is required for colbert")
        model_name = f"{args.model}:{args.query_format}"
        predictions, latencies = predict_colbert(cases, args.model, args.query_format)
    elif args.backend == "instruct":
        model_name = args.model or "LiquidAI/LFM2.5-1.2B-Instruct"
        predictions, latencies = predict_instruct(cases, model_name, args.batch_size)
    else:
        model_name = args.model or "LiquidAI/LFM2.5-1.2B-Instruct-GGUF"
        predictions, latencies = predict_llama(cases, model_name, args.filename)
    wall_ms = (time.perf_counter() - started) * 1000
    print(json.dumps(summarize(cases, predictions, latencies, model_name, wall_ms), indent=2))


if __name__ == "__main__":
    main()
