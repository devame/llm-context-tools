# Janet grammar provenance

The packaged `tree-sitter-janet` libraries are compiled from
[`sogaiu/tree-sitter-janet-simple`](https://github.com/sogaiu/tree-sitter-janet-simple)
revision `3c1bdcfff374138da03a1db25c75efce623910fe` (Tree-sitter ABI 14).

The grammar is dedicated to the public domain under CC0 1.0. Its complete
license text and source are available in the upstream repository. Run
`script/build-janet-grammar.sh` with Zig 0.15 or newer to reproduce all shipped
Linux and macOS libraries for x86-64 and ARM64, plus Windows x86-64. These are
the platforms provided by the packaged Tree-sitter core runtime.
