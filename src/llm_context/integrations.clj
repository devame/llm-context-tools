(ns llm-context.integrations
  (:import [java.nio.file FileAlreadyExistsException Files OpenOption Path StandardOpenOption]))

(def guidance
  "# Using llm-context\n\nUse the persistent semantic graph before broad source exploration.\n\n1. Run `llm-context analyze` after supported Clojure, ClojureScript, CLJC, Janet, or selected EDN changes.\n2. Find symbols with `llm-context query find-symbol <name>` or `query search <intent> --explain`.\n3. Inspect exact callers/callees and typed event/state topics with `llm-context query`.\n4. Inspect external, dynamic, ambiguous, and unresolved observations explicitly with `query unresolved`.\n5. Prefer `llm-context context <symbol>` for a bounded packet with selected path evidence.\n6. Read referenced source locations when implementation detail is needed.\n\nDatalevin under `.llm-context/db/` is authoritative. Traversable edges are exact in-project facts; diagnostic references never enter traversal. Unsupported languages are intentionally ignored. JSONL, JSON, EDN, and Markdown are exports only.\n")

(def targets
  {:claude ".claude/skills/llm-context/SKILL.md"
   :codex ".agents/skills/llm-context/SKILL.md"
   :generic ".llm-context/AGENT.md"})

(defn install! [project target force?]
  (let [relative (get targets target)]
    (when-not relative
      (throw (ex-info (str "Unknown integration target: " (name target))
                      {:exit-code 2 :target target :supported (set (keys targets))})))
    (let [path (.resolve ^Path (:root project) ^String relative)]
      (when-let [parent (.getParent path)]
        (Files/createDirectories parent
                                 (make-array java.nio.file.attribute.FileAttribute 0)))
      (try
        (Files/writeString path guidance
                           (into-array OpenOption
                                       (if force?
                                         [StandardOpenOption/CREATE
                                          StandardOpenOption/TRUNCATE_EXISTING
                                          StandardOpenOption/WRITE]
                                         [StandardOpenOption/CREATE_NEW
                                          StandardOpenOption/WRITE])))
        path
        (catch FileAlreadyExistsException _
          (throw (ex-info (str "Integration already exists: " path
                               "; pass --force to replace it")
                          {:exit-code 2 :path (str path)})))))))
