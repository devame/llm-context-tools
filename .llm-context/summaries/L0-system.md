# IBM CL Visualizer - System Overview

**Type**: Interactive visualizer for IBM Control Language (CL) programs
**Purpose**: Parse, evaluate, and visualize execution flow of CL programs
**Architecture**: JavaScript modules with event-driven UI

## Statistics
- **Files**: 2 modules
- **Functions**: 3 total
- **Call relationships**: 25
- **Side effects**: database, file_io, logging

## Key Components
- **root**: query, scip-parser

## Entry Points


## Architecture Pattern
- **Parser**: Converts CL source to AST (grammar.js, cmdParser.js)
- **Evaluator**: Executes AST and tracks state (evaluator.js, statementEvaluators.js)
- **State**: Manages variables and execution flow (state.js, stateStorage.js)
- **UI**: Visualizes execution and state (ui.js, formatting.js)
