# LLM Context Tools development guidance

This repository implements `llm-context` in Clojure. Datalevin under
`.llm-context/db/` is the persistent semantic graph; exported JSONL is never
the source of truth.

Useful commands:

```bash
clojure -M:test
clojure -M -m llm-context.main analyze
clojure -M -m llm-context.main query stats
clojure -M -m llm-context.main context <symbol>
clojure -T:build dist
```

Preserve explicit resolution states and evidence confidence. Incremental changes
must handle modified, added, and deleted files as well as relationships from
unchanged files into changed symbols.
