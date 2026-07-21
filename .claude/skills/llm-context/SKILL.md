---
name: llm-context
description: Use the Datalevin semantic graph to build focused code context.
---

# Using llm-context

Run `llm-context analyze` after source changes. Use `llm-context context <symbol>`
for a bounded task packet, and use `llm-context query` for callers, callees,
effects, entry points, and unresolved relationships. Treat resolution and
effect confidence as evidence quality, then inspect referenced source locations
for implementation detail.
