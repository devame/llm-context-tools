(ns llm-context.integrations
  (:import [java.nio.file FileAlreadyExistsException Files OpenOption Path StandardOpenOption]))

(def guidance
  "# Using llm-context\n\nUse the persistent semantic graph before broad source exploration.\n\n1. Run `llm-context analyze` after source changes.\n2. Find symbols with `llm-context query find-symbol <name>`.\n3. Inspect callers, callees, effects, and unresolved links with `llm-context query`.\n4. Prefer `llm-context context <symbol>` for a bounded task packet.\n5. Read the referenced source locations when implementation detail is needed.\n\nDatalevin under `.llm-context/db/` is authoritative. JSONL, JSON, EDN, and Markdown are exports only. Resolution states and effect confidence are evidence quality indicators, not guarantees.\n")

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
