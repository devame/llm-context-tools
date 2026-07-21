(ns llm-context.parser.provider)

(defprotocol ParserProvider
  (supported-languages [provider])
  (parse-source [provider language source]
    "Parse source into a provider-neutral concrete syntax tree."))

(def extension-languages
  {".js" :language/javascript
   ".mjs" :language/javascript
   ".cjs" :language/javascript
   ".jsx" :language/javascript
   ".ts" :language/typescript
   ".tsx" :language/tsx
   ".py" :language/python
   ".java" :language/java
   ".go" :language/go
   ".rs" :language/rust
   ".c" :language/c
   ".h" :language/c
   ".cc" :language/cpp
   ".cpp" :language/cpp
   ".cxx" :language/cpp
   ".hpp" :language/cpp
   ".rb" :language/ruby
   ".php" :language/php
   ".sh" :language/bash
   ".bash" :language/bash
   ".clj" :language/clojure
   ".cljs" :language/clojure
   ".cljc" :language/clojure
   ".janet" :language/janet})

(defn language-for-path [path]
  (let [name (str path)
        dot (.lastIndexOf name ".")]
    (when (not= -1 dot)
      (get extension-languages (subs name dot)))))
