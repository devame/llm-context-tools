# ARD: Verified, pluggable model packages

- Date: 2026-08-16
- Status: Accepted for 0.12.0

## Context

llm-context uses three independently replaceable model roles:

1. the semantic retriever that encodes repository documents and queries;
2. the compact query router and candidate reranker;
3. the answer reader used by interactive demonstrations after retrieval.

The runtime configuration already exposed paths for the first two roles, but
the installers embedded their identities, revisions, file lists, and hashes.
The answer reader was configured only by the demo. Changing a download URL was
therefore not a complete model swap: the installed files and runtime identity
could disagree, and no shared packaging contract covered all three roles.

## Decision

Define one EDN model-package contract with a versioned set of role records.
Each record contains a model identity, immutable 40-character repository
revision, runtime format, entrypoint, snapshot base URL, and a SHA-256 for every
required file. The built-in manifest is part of the signed/checksummed release
jar.

Custom manifests are accepted only when the caller supplies the manifest's
SHA-256. There is deliberately no unverified escape hatch. A package is usable
only after every downloaded or locally supplied file matches the manifest.

`llm-context models install` handles both Hugging Face-compatible snapshot URLs
and local source directories, writes packages into the shared model cache, and
emits an installation registry. Launchers point `LLM_CONTEXT_MODEL_REGISTRY`
at that registry. Configuration is merged in this order:

1. release defaults;
2. verified installed-model registry;
3. repository-local `llm-context.edn`.

This preserves explicit project overrides while making installer selections
effective at runtime. The registry maps semantic and routing roles onto their
existing runtime settings and exposes the answer-reader's GGUF path, filename,
alias, and format to demo or other answer-generation consumers.

The default installer selects semantic retrieval and routing/reranking. The
answer reader remains opt-in because it is not required by the core CLI and its
default artifact is approximately 731 MB. Demo packaging selects all three via
`LLM_CONTEXT_MODEL_ROLES`.

## Safety and failure behavior

- Revisions must be immutable commit hashes, never mutable branch names.
- File names must be safe relative paths; absolute paths and parent traversal
  are rejected.
- Custom manifests without a valid SHA-256 are rejected before model I/O.
- A checksum mismatch aborts installation and cannot update the registry.
- Existing verified cache entries are reused.
- Runtime registry data is subordinate to explicit project configuration.
- Model compatibility remains role-specific: NextPlaid roles require a
  compatible ONNX snapshot; the answer reader requires a GGUF-capable server.

## Consequences

Users can exchange any of the three models without editing installer source or
hard-coding paths in an application. Model provenance is inspectable through
`llm-context models status`. A custom model publisher must provide a complete,
pinned manifest and hashes; this is intentional operational friction that
prevents silent execution of unverified weights.

The contract does not claim that arbitrary weights are behaviorally suitable.
Retrieval and routing replacements still need their respective evaluation
suites, and an answer-reader replacement must be tested for grounded synthesis.
Cryptographic verification establishes artifact identity, not model quality.

## Rejected alternatives

- Environment variables for URLs alone: they cannot safely change hashes,
  identity, runtime paths, and answer-reader metadata as one atomic choice.
- Unverified opt-in mode: it weakens the release's supply-chain guarantee and
  was explicitly rejected.
- Always installing the answer reader: it imposes a large download on users who
  only need retrieval.
- A Metabase-specific model profile: model roles and verification are general
  packaging concerns and must not depend on one indexed repository.
