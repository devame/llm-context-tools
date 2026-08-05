# Public cross-repository semantic evaluation

This suite measures retrieval generalization across pinned public Clojure and
ClojureScript repositories without copying their source into this repository.
The checked-in manifest records the repository URL, exact commit, checkout
name, corpus split paths, and expected query counts.

Prepare a clean external checkout for each manifest entry. The checkout must be
detached at the recorded commit and must ignore `.llm-context/`; adding that
directory to `.git/info/exclude` is suitable for a local evaluation. The runner
does not clone, update, or repair a checkout.

Run from the `llm-context-tools` root:

```bash
clojure -M:public-semantic-evaluation /path/to/checkouts
```

The runner verifies the commit and cleanliness, runs `doctor`, performs a full
analysis, starts the loopback-only project service, waits for complete semantic
coverage, validates both corpus splits against the analyzer, and runs FTS-only,
LateOn-only, and hybrid retrieval three times. It fails before benchmarking if
any semantic document is pending, leased, dirty, or failed, if the LateOn
endpoint is not loopback-only, or if a selector is missing, ambiguous, or
overlaps a hard negative.

Full per-query results and command logs are written below each external
checkout's ignored `.llm-context/public-semantic-evaluation/` directory. The
runner's standard output contains aggregate metrics, repository/split counts,
and deterministic bootstrap intervals only. Never copy those raw files or a
source checkout into this repository.

The development and held-out judgments are frozen before aggregate results are
inspected. Correct factual selector fixes are allowed; changing wording or
grades to improve a result is not. A changed upstream commit requires
requalification of selectors and a new descriptive baseline.

Interpret failures in layers: low recall means the candidate set, analyzer,
document construction, wording, or model vocabulary is insufficient; high
recall with low MRR or nDCG means ranking is the problem; and strong search
scores with weak context recall point to seed selection or context-packet
construction. A failure isolated to one repository, language, or query type is
evidence of a domain-specific generalization issue rather than a suite-wide
conclusion.

The initial public suite covers Clojure and ClojureScript only. Janet is a
future extension: add a separately authored public corpus once a suitable
Janet project has stable analyzer support and enough production-oriented
judgments. Do not mix Janet queries into this baseline until its parser and
canonical-symbol contract are validated.

Maintenance requires a new pinned checkout, selector requalification, corpus
hash, and descriptive baseline whenever an upstream commit changes. Public
artifacts must contain only the manifest, authored selectors, aggregate
metadata, and aggregate metrics; source checkouts, generated graphs, vector
indexes, logs, raw results, and private evaluation details remain external.
