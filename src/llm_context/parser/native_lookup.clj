(ns llm-context.parser.native-lookup
  (:import [io.github.treesitter.jtreesitter NativeLibraryLookup]
           [java.lang.foreign Arena SymbolLookup]
           [java.nio.file Paths]))

(deftype CoreLookup []
  NativeLibraryLookup
  (^SymbolLookup get [_ ^Arena arena]
    (let [path (System/getProperty "llm-context.tree-sitter.library")]
      (when-not path
        (throw (IllegalStateException.
                "llm-context.tree-sitter.library must be set before JTreeSitter initializes")))
      (SymbolLookup/libraryLookup
       (Paths/get path (make-array String 0))
       arena))))

(defn provider-class-loader []
  (.getClassLoader CoreLookup))
