# Public cross-repository semantic evaluation

Date: 2026-08-05

## Status

Accepted for implementation. The first release is a local, descriptive
evaluation suite and is not a release-quality gate.

## Context

The semantic evaluator now has a maintained public corpus, a separate private
repository-specific corpus, analyzer-backed selector validation, graded
relevance judgments, hard negatives, MRR, nDCG, context recall, latency
metrics, per-language and per-query-type slices, and privacy-safe result
output. Those evaluations establish that the current system can be measured
reliably and that a correct candidate can be found in a real project.

They do not yet establish that the retrieval behavior generalizes across
unrelated Clojure and ClojureScript architectures. A single repository can
overrepresent its naming conventions, document structure, framework idioms,
and source vocabulary. A public cross-repository suite is therefore needed to
separate repository-specific tuning from general retrieval behavior.

## Decision

Create a versioned public evaluation suite in `llm-context-tools` for three
pinned public repositories:

- [clojure-lsp/clojure-lsp](https://github.com/clojure-lsp/clojure-lsp), for
  predominantly Clojure analysis, navigation, refactoring, and cache flows;
- [day8/re-frame](https://github.com/day8/re-frame), for ClojureScript
  registration, event, subscription, effect, interceptor, and state flows;
- [metabase/metabase](https://github.com/metabase/metabase), for a large mixed
  Clojure/ClojureScript application with modular production and test code.

The suite will contain approximately 40, 30, and 50 questions respectively.
Each repository will have a development split and a held-out split. Repository
sources will be prepared in external local checkouts at exact manifest-pinned
commits; source files will not be copied into the evaluator repository.

The first baseline will be descriptive. It will inform engineering decisions,
but it will not fail a release. A result is incomparable, rather than a
regression, when the repository commit, corpus hash, scorer commit, retrieval
runtime, model revision, document version, candidate count, or relevant
runtime configuration differs.

## Corpus contract

Each query will use corpus format 2 and include:

- a stable query ID;
- language, query-type, and domain slices;
- natural-language wording that does not paraphrase the target symbol name;
- one primary grade-3 judgment;
- optional grade-2 alternate and grade-1 supporting judgments;
- at least one explicit hard negative; and
- platform, file, or kind selectors wherever a qualified name is ambiguous.

Questions will cover behavior, identifiers, state and data flow, framework
conventions, cross-file relationships, macro or registration paths, production
versus test code, and similarly named implementations. Wording and judgments
will be frozen before inspecting aggregate retrieval results. A judgment may be
corrected when it is factually wrong, but difficult queries will not be
rewritten to improve their scores.

Every relevance and hard-negative selector must resolve to exactly one
canonical analyzer symbol. Missing selectors, ambiguous selectors, and
relevant/hard-negative overlap are validation errors. The public corpus will
contain authored questions and public symbol selectors only; it will not
contain source snippets or generated graph data.

## Runner and source isolation

The public runner will accept a root containing externally prepared checkouts.
It will not clone repositories implicitly. Before evaluation it will:

1. verify that every checkout is clean and at the manifest-pinned commit;
2. run the project doctor and analysis steps;
3. wait for semantic synchronization to reach complete coverage;
4. require zero dirty, pending, leased, or failed semantic documents;
5. require the LateOn endpoint to be loopback-only;
6. validate the selected corpus against analyzer output; and
7. run the benchmark only after all preflight checks succeed.

The runner will execute FTS-only, LateOn-only, and hybrid retrieval modes with
the same corpus, candidate count, model, document version, context depth, and
token budget. Full per-query results and logs will remain below ignored
`.llm-context/` state in the external project checkouts. Console output will
contain only aggregate metrics, slice metrics, latency summaries, and
diagnostic counts.

The checked-in manifest will record each public repository URL, exact commit,
checkout name, corpus files, and expected query counts. Run metadata will record
the repository commit, corpus hash, scorer commit, retrieval runtime version,
model revision, document version, candidate count, context configuration, and
timestamp.

## Retrieval ablations and metrics

The evaluator will expose three search modes:

- FTS-only, using Datalevin lexical candidates;
- LateOn-only, using freshness-validated semantic candidates; and
- hybrid, using the existing exact-identifier priority and reciprocal-rank
  fusion.

Search comparisons will report recall@10, recall@20, recall@50, MRR, nDCG,
hard-negative-before-relevant rate, LateOn participation, and search latency.
The normal hybrid path will additionally report context-seed recall, final
context-packet recall, context errors, and context latency. Results will be
available per repository, language, query type, domain, retrieval mode, and
suite-wide macro and query-weighted aggregates.

The aggregate reporter will calculate deterministic bootstrap confidence
intervals with a fixed seed. Three repeated runs will be used to verify ranking
determinism and establish descriptive latency medians and percentiles. Runtime
differences will remain descriptive rather than hard wall-clock gates.

## Implementation units

The work will be delivered as independently reviewable commits:

1. Add explicit FTS-only, LateOn-only, and hybrid retrieval modes while
   preserving hybrid as the default, with service, CLI, and evaluator tests.
2. Add the pinned public-suite manifest and external-checkout runner, including
   completeness, loopback, cleanliness, and ignored-output checks.
3. Add the clojure-lsp development and held-out corpora and their validation
   coverage.
4. Add the re-frame development and held-out corpora and their validation
   coverage.
5. Add the Metabase development and held-out corpora and their validation
   coverage.
6. Add multi-repository aggregation, ablation reports, deterministic confidence
   intervals, and aggregate-only baseline metadata.
7. Document corpus maintenance, pinned-checkout preparation, privacy rules,
   interpretation of candidate-versus-ranking-versus-context failures, and
   future Janet coverage.

Each commit must pass its focused tests, `git diff --check`, and the relevant
corpus or runner validation before the next unit is started. Generated
databases, vector indexes, raw results, logs, source checkouts, and private
repository details are excluded from every public commit.

## Validation and acceptance

Before accepting the first baseline:

- all approximately 120 public queries and their hard negatives resolve
  exactly once;
- no selector is missing, ambiguous, or shared between relevant and negative
  judgments;
- every repository reaches complete semantic coverage before benchmarking;
- all three retrieval modes produce comparable query counts and metadata;
- three repeated runs have deterministic ranking metrics;
- the held-out split is not used to author or tune the development corpus;
- no generated state is tracked;
- the staged public diff contains no private names, paths, domains, symbols,
  query text, or source snippets; and
- the full Clojure test suite and focused evaluator tests pass.

The suite supports the following interpretation:

- low recall means the candidate set, analyzer, document construction, query
  wording, or model vocabulary needs work;
- high recall with low MRR or nDCG means candidates are found but ordered
  poorly;
- high search scores with low context recall points to seed selection or
  context construction; and
- a failure isolated to one repository or language slice indicates a domain or
  construct-specific generalization problem.

## Privacy and consequences

All selected repositories are public, but the evaluation workflow still keeps
source-derived state outside `llm-context-tools`. No private repository
checkout, private corpus, private query, private symbol, generated database,
index, raw result, log, or source snippet may enter the public repository or
its public artifacts. The existing private evaluation remains a separate
workflow and is not referenced by the public manifest or runner.

This decision adds maintenance work whenever a pinned upstream commit changes:
selectors may need requalification and the baseline must be regenerated. That
cost is accepted because pinned commits make failures reproducible and prevent
moving upstream code from silently changing the benchmark. The initial suite
validates Clojure and ClojureScript generalization only; Janet requires a
separate public corpus when suitable projects and judgments are available.
