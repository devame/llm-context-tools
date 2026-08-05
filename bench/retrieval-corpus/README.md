# Semantic retrieval corpus

This directory is the maintained, versioned retrieval-quality fixture for
llm-context. It is deliberately independent of the llm-context implementation
repository so benchmark results do not depend on unrelated source changes.

The project contains parallel Clojure and Janet domains with realistic naming
collisions: authentication versus authorization, loading versus validation,
queueing versus processing, route dispatch versus aliases, and production work
versus diagnostic inspection. The query set currently contains 24 judgments
covering identifiers, behavior, state flow, graph flow, framework conventions,
hard negatives, and cross-language concepts.

## Judgment format

`queries.edn` uses corpus format 1:

```clojure
{:corpus/version 1
 :queries
 [{:id :clojure/authenticate-browser-session
   :language :clojure
   :query-type :behavior
   :query "where is an incoming browser request authenticated?"
   :relevance {"corpus.auth.session/authenticate-request" 3
               "corpus.auth.session/decode-session-cookie" 1}
   :hard-negatives ["corpus.auth.session/authorize-request"]}]}
```

Relevance grades are positive integers. Grade 3 is the primary answer and
grade 1 is useful supporting context. Keys are canonical qualified names, not
generated symbol IDs, so judgments remain stable across graph rebuilds.
Hard negatives are plausible but wrong answers that should not outrank a
relevant result.

Repository-specific corpora may use format 2 selectors when qualified names
alone are not unique:

```clojure
{:corpus/version 2
 :queries
 [{:id :synthetic/load-session
   :language :clojurescript
   :query-type :behavior
   :domain :sessions
   :query "where is persisted session state loaded?"
   :relevance
   [{:qualified-name "example.session/load-state"
     :platform :cljs
     :grade 3}]
   :hard-negatives
   [{:qualified-name "example.session/validate-state"
     :platform :cljs}]}]}
```

A structured selector must provide `:id` or `:qualified-name` and may add
`:platform`, `:file`, or `:kind`. The validator matches all supplied fields and
requires exactly one canonical analyzer symbol per format-2 selector. It also
rejects relevant and hard-negative selectors that resolve to the same symbol.
Repeated result candidates cannot earn gain for the same judgment twice.

Validate the schema and every judged identity against real clj-kondo and Janet
analyzer output:

```bash
clojure -M:validate-semantic-corpus
```

Analyzer warnings are counted in the validation summary; analyzer errors still
fail validation. A warning does not invalidate selectors that resolve exactly.

## Running the benchmark

Analyze the fixture and start its synchronized resident service:

```bash
llm-context -C bench/retrieval-corpus/project analyze --full
llm-context -C bench/retrieval-corpus/project service start
clojure -M:semantic-bench \
  bench/retrieval-corpus/project \
  bench/retrieval-corpus/queries.edn
```

The fixture inherits the repository's default LateOn-Code configuration. Keep
the graph, model revision, candidate count, and hardware fixed when comparing
runs. A disabled or unavailable semantic provider still produces a useful FTS
baseline, which will be visible in the LateOn participation rates.

## Maintenance rules

When adding a query:

1. Write it as a realistic task description, not a paraphrase of the symbol
   name.
2. Assign one stable keyword ID, language slice, and query-type slice.
3. Judge every answer needed to satisfy the task; use grades to distinguish
   the primary answer from supporting context.
4. Add at least one confusing, existing symbol as a hard negative.
5. Keep the fixture source credible enough that the answer is evident from its
   documentation, implementation, or exact relationships.
6. Run the corpus validator and focused evaluation tests.

Corpus changes should be reviewed like source changes. Do not rewrite queries
after looking at one retrieval run merely to improve a score; record genuinely
ambiguous judgments or improve the fixture instead.
