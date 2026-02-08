/**
 * Language-specific Tree-sitter queries
 *
 * Defines queries for extracting:
 * - Function declarations
 * - Function calls
 * - Import/require statements
 * - Export statements
 *
 * Each language has specific patterns based on its syntax.
 */

/**
 * Tree-sitter query patterns for each language
 * Queries use S-expression syntax to match AST nodes
 */
export const LANGUAGE_QUERIES = {
  javascript: {
    // Function declarations
    functions: `
      (function_declaration
        name: (identifier) @name
        parameters: (formal_parameters) @params
        body: (statement_block) @body) @function

      (lexical_declaration
        (variable_declarator
          name: (identifier) @name
          value: (arrow_function
            parameters: (_) @params
            body: (_) @body))) @function

      (variable_declaration
        (variable_declarator
          name: (identifier) @name
          value: (arrow_function
            parameters: (_) @params
            body: (_) @body))) @function

      (method_definition
        name: (_) @name
        parameters: (formal_parameters) @params
        body: (statement_block) @body) @function
    `,

    // Function calls
    calls: `
      (call_expression
        function: (identifier) @call)

      (call_expression
        function: (member_expression
          property: (property_identifier) @call))
    `,

    // Imports
    imports: `
      (import_statement
        source: (string) @source)

      (call_expression
        function: (identifier) @func
        arguments: (arguments (string) @source)
        (#eq? @func "require"))
    `
  },

  typescript: {
    // TypeScript has similar patterns to JavaScript
    functions: `
      (function_declaration
        name: (identifier) @name
        parameters: (formal_parameters) @params
        body: (statement_block) @body) @function

      (method_definition
        name: (_) @name
        parameters: (formal_parameters) @params
        body: (statement_block) @body) @function

      (variable_declarator
        name: (identifier) @name
        value: (arrow_function
          parameters: (_) @params
          body: (_) @body)) @function

      (export_statement
        declaration: (function_declaration
          name: (identifier) @name
          parameters: (formal_parameters) @params
          body: (statement_block) @body)) @function
    `,

    calls: `
      (call_expression
        function: (identifier) @call)

      (call_expression
        function: (member_expression
          property: (property_identifier) @call))
    `,

    imports: `
      (import_statement
        source: (string) @source)
    `
  },

  tsx: {
    // TSX uses same queries as TypeScript
    functions: `
      (function_declaration
        name: (identifier) @name
        parameters: (formal_parameters) @params
        body: (statement_block) @body) @function

      (method_definition
        name: (_) @name
        parameters: (formal_parameters) @params
        body: (statement_block) @body) @function

      (variable_declarator
        name: (identifier) @name
        value: (arrow_function
          parameters: (_) @params
          body: (_) @body)) @function
    `,

    calls: `
      (call_expression
        function: (identifier) @call)

      (call_expression
        function: (member_expression
          property: (property_identifier) @call))
    `,

    imports: `
      (import_statement
        source: (string) @source)
    `
  },

  python: {
    functions: `
      (function_definition
        name: (identifier) @name
        parameters: (parameters) @params
        body: (block) @body) @function
    `,

    calls: `
      (call
        function: (identifier) @call)

      (call
        function: (attribute
          attribute: (identifier) @call))
    `,

    imports: `
      (import_statement
        name: (dotted_name) @source)

      (import_from_statement
        module_name: (dotted_name) @source)
    `
  },

  go: {
    functions: `
      (function_declaration
        name: (identifier) @name
        parameters: (parameter_list) @params
        body: (block) @body) @function

      (method_declaration
        name: (field_identifier) @name
        parameters: (parameter_list) @params
        body: (block) @body) @function
    `,

    calls: `
      (call_expression
        function: (identifier) @call)

      (call_expression
        function: (selector_expression
          field: (field_identifier) @call))
    `,

    imports: `
      (import_spec
        path: (interpreted_string_literal) @source)
    `
  },

  rust: {
    functions: `
      (function_item
        name: (identifier) @name
        parameters: (parameters) @params
        body: (block) @body) @function
    `,

    calls: `
      (call_expression
        function: (identifier) @call)

      (call_expression
        function: (field_expression
          field: (field_identifier) @call))
    `,

    imports: `
      (use_declaration
        argument: (_) @source)
    `
  },

  java: {
    functions: `
      (method_declaration
        name: (identifier) @name
        parameters: (formal_parameters) @params
        body: (block) @body) @function
    `,

    calls: `
      (method_invocation
        name: (identifier) @call)
    `,

    imports: `
      (import_declaration
        (scoped_identifier) @source)
    `
  },

  c: {
    functions: `
      (function_definition
        declarator: (function_declarator
          declarator: (identifier) @name
          parameters: (parameter_list) @params)
        body: (compound_statement) @body) @function
    `,

    calls: `
      (call_expression
        function: (identifier) @call)
    `,

    imports: `
      (preproc_include
        path: (_) @source)
    `
  },

  cpp: {
    functions: `
      (function_definition
        declarator: (function_declarator
          declarator: (identifier) @name
          parameters: (parameter_list) @params)
        body: (compound_statement) @body) @function
    `,

    calls: `
      (call_expression
        function: (identifier) @call)
    `,

    imports: `
      (preproc_include
        path: (_) @source)
    `
  },

  ruby: {
    functions: `
      (method
        name: (_) @name
        parameters: (method_parameters)? @params
        (_)* @body) @function
    `,

    calls: `
      (call
        method: (identifier) @call)
    `,

    imports: `
      (call
        method: (identifier) @method
        arguments: (argument_list
          (string) @source)
        (#eq? @method "require"))
    `
  },

  php: {
    functions: `
      (function_definition
        name: (name) @name
        parameters: (formal_parameters) @params
        body: (compound_statement) @body) @function

      (method_declaration
        name: (name) @name
        parameters: (formal_parameters) @params
        body: (compound_statement) @body) @function
    `,

    calls: `
      (function_call_expression
        function: (name) @call)
    `,

    imports: `
      (namespace_use_clause
        (qualified_name) @source)
    `
  },

  bash: {
    functions: `
      (function_definition
        name: (word) @name
        body: (_) @body) @function
    `,

    calls: `
      (command
        name: (command_name) @call)
    `,

    imports: `
      (command
        name: (command_name) @cmd
        argument: (word) @source
        (#eq? @cmd "source"))
    `
  },

  json: {
    // JSON doesn't have functions, but we include it for completeness
    functions: ``,
    calls: ``,
    imports: ``
  },

  clojure: {
    functions: `
      (list_lit
        (sym_lit) @func_type
        (sym_lit) @name
        (_)* @params
        (_)* @body
        (#match? @func_type "^(defn|defn-)$")) @function

      (list_lit
        (sym_lit) @func_type
        (_)* @params
        (_)* @body
        (#match? @func_type "^(fn|fn*)$")) @function

      (list_lit
        (sym_lit) @func_type
        (sym_lit) @name
        (_)* @body
        (#eq? @func_type "defmulti")) @function
    `,

    calls: `
      (list_lit
        (sym_lit) @call)
    `,

    imports: `
      (list_lit
        (sym_lit) @import_type
        (_)* @source
        (#match? @import_type "^(require|use|import)$"))
    `
  },

  clojurescript: {
    functions: `
      (list_lit
        (sym_lit) @func_type
        (sym_lit) @name
        (_)* @params
        (_)* @body
        (#match? @func_type "^(defn|defn-)$")) @function

      (list_lit
        (sym_lit) @func_type
        (_)* @params
        (_)* @body
        (#match? @func_type "^(fn|fn*)$")) @function

      (list_lit
        (sym_lit) @func_type
        (sym_lit) @name
        (_)* @body
        (#eq? @func_type "defmulti")) @function
    `,

    calls: `
      (list_lit
        (sym_lit) @call)
    `,

    imports: `
      (list_lit
        (sym_lit) @import_type
        (_)* @source
        (#match? @import_type "^(require|use|import)$"))
    `
  },

  janet: {
    functions: `
      (par_tup_lit
        (sym_lit) @func_type
        (sym_lit) @name
        (_)* @params
        (_)* @body
        (#match? @func_type "^(defn|defn/?)$")) @function

      (par_tup_lit
        (sym_lit) @func_type
        (_)* @params
        (_)* @body
        (#match? @func_type "^(fn|fn/)$")) @function

      (par_tup_lit
        (sym_lit) @func_type
        (sym_lit) @name
        (_)* @body
        (#eq? @func_type "defmacro")) @function
    `,

    calls: `
      (par_tup_lit
        (sym_lit) @call)
    `,

    imports: `
      (par_tup_lit
        (sym_lit) @import_type
        (_)* @source
        (#match? @import_type "^(import|use)$"))
    `
  }

};

/**
 * Get queries for a specific language
 * @param {string} language - Language name
 * @returns {object} Queries object {functions, calls, imports}
 */
export function getQueries(language) {
  if (!LANGUAGE_QUERIES[language]) {
    throw new Error(`No queries defined for language: ${language}`);
  }
  return LANGUAGE_QUERIES[language];
}

/**
 * Get function query for a language
 * @param {string} language - Language name
 * @returns {string} Tree-sitter query string
 */
export function getFunctionQuery(language) {
  const queries = getQueries(language);
  return queries.functions;
}

/**
 * Get call query for a language
 * @param {string} language - Language name
 * @returns {string} Tree-sitter query string
 */
export function getCallQuery(language) {
  const queries = getQueries(language);
  return queries.calls;
}

/**
 * Get import query for a language
 * @param {string} language - Language name
 * @returns {string} Tree-sitter query string
 */
export function getImportQuery(language) {
  const queries = getQueries(language);
  return queries.imports;
}

export default LANGUAGE_QUERIES;
