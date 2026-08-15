# Source-role-aware intent retrieval

- Date: 2026-08-14
- Status: Accepted for implementation
- Scope: hybrid search ordering, natural-language context seed selection, CLI/service provenance

## Problem

Large repositories frequently define test symbols with names and examples that
match implementation questions more literally than production symbols. In the
complete 55,638-document Metabase index, `how to reset password` ranked
`metabase.session.api-test/reset-password-validation-test` first at
`0.031099324975891997`; the production
`metabase.cmd.core/reset-password` was already present second at
`0.031009615384615385`.

The semantic document already includes the canonical file path. The missing
piece is not encoding coverage but retrieval policy: reciprocal-rank fusion
does not know whether the caller wants production implementation, tests, or an
unmodified corpus search. Intent context then traverses only the first result,
so a small ordering error becomes the sole graph seed.

Other baseline queries demonstrate that source role is not the whole retrieval
problem. The HTTP-surface query selected an unrelated production symbol, while
email and dashboard queries returned mixtures of relevant and broad production
symbols. Source-role preference therefore has a deliberately bounded claim: it
corrects role selection when the desired candidate is already present; it does
not solve broad multi-seed retrieval or semantic recall.

## Decision

Derive a repository-neutral source role from the current canonical graph file
path after freshness validation and graph hydration. Built-in roles are
`:production`, `:test`, `:generated`, `:vendor`, and `:unknown`; ordered project
glob overrides handle repository-specific layouts.

Support source preferences `:auto`, `:production`, `:test`, and `:none`.
`context --intent` defaults to `:auto`; general implementation questions
resolve to production preference and explicit test/spec/fixture questions to
test preference. General `query search` defaults to `:none` so its existing
corpus-wide ordering remains available.

Preference is a stable partition of non-exact fused results. Exact identifier
matches remain first. Tests and other roles are not filtered, and reciprocal-
rank scores are not modified. Results and context provenance report source
role, fused rank, final rank, preference reason, and whether ordering changed.

## Consequences

- Existing semantic indexes remain valid; no re-encoding is required.
- FTS fallback and LateOn-only ablations use the same caller-selected policy.
- Tests remain available for explicit test questions and as alternatives.
- Project overrides can correct unconventional path layouts without changing
  canonical identities or semantic documents.
- Public evaluation must measure production and test intent separately.
- Broad questions that need multiple distributed symbols remain a separate
  retrieval/orchestration problem.

## Qualification gates

1. Unit tests cover path roles, overrides, auto intent, stable ordering, and
   exact-match priority.
2. CLI, direct, and resident-service paths produce the same ordering and
   provenance.
3. Metabase production questions select production seeds when production
   candidates are present; explicit test questions can still select tests.
4. Existing public retrieval metrics do not regress when search uses its
   default `:none` preference.
5. Warm-query policy overhead remains in-memory and does not issue another
   LateOn request or graph write.
