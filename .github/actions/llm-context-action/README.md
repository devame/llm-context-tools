# llm-context composite action

```yaml
- uses: actions/checkout@v4
- id: context
  uses: ./.github/actions/llm-context-action
- run: echo "${{ steps.context.outputs.total-files }} files"
```

The action installs JDK 25, the thin npm launcher, runs a full analysis, and
uploads the Datalevin database plus JSON and Markdown projections. The database
is an artifact rather than an auto-committed repository file.
