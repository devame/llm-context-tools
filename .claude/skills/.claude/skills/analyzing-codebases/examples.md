# LLM Context Tools - Examples

## Example 1: First-Time Analysis

**Scenario**: User opens a new project

```bash
$ cd ~/projects/my-app
$ ls
src/ test/ package.json

$ node analyze.js
```

**Output**:
```
🔍 No previous analysis found - running initial full analysis...
[1/5] Setting up analysis directory...
[2/5] Running SCIP indexer...
[3/5] Parsing SCIP data...
[4/5] Running full analysis...
    Functions found: 156
    Call relationships: 423
    Side effects detected: 89
[5/5] Generating manifest...
[6/6] Generating summaries...
✅ Initial analysis complete!
```

**Generated Files**:
```
.llm-context/
├── graph.jsonl (45 KB)
├── manifest.json (12 KB)
└── summaries/
    ├── L0-system.md (1.2 KB)
    ├── L1-domains.json (2.3 KB)
    └── L2-modules.json (3.1 KB)
```

## Example 2: Incremental Update

**Scenario**: Developer edits 2 files

```bash
# Edit files
vim src/auth.js src/users.js

# Run analysis
$ node analyze.js
```

**Output**:
```
🔍 Existing analysis found - checking for changes...
📝 Detected 2 changed files - running incremental analysis...

Files re-analyzed: 2
Files skipped: 45
Total functions in graph: 158
✓ Efficiency: 95.7% of files skipped!
```

**Time**: 127ms (vs 4.2s for full analysis = 97% faster)

## Example 3: Query Usage

### Find Entry Points

```bash
$ node query.js entry-points
```

**Output**:
```
Found 12 results:

  1. main (src/index.js:8)
  2. initializeApp (src/app.js:15)
  3. handleRequest (src/server.js:23)
  4. processQueue (src/workers.js:45)
  ...
```

### Find Side Effects

```bash
$ node query.js side-effects
```

**Output**:
```
Found 89 results:

  1. saveUser (src/users.js:34)
     Calls: db.query, logger.info
     Effects: database, logging

  2. fetchProfile (src/api.js:67)
     Calls: axios.get, cache.set
     Effects: network, database

  3. uploadFile (src/storage.js:12)
     Calls: fs.writeFile, s3.upload
     Effects: file_io, network
  ...
```

### Trace Function Calls

```bash
$ node query.js trace authenticateUser
```

**Output**:
```json
{
  "function": "authenticateUser",
  "file": "src/auth.js",
  "line": 45,
  "calls": [
    {
      "function": "validateCredentials",
      "file": "src/auth.js",
      "line": 78,
      "calls": [
        {
          "function": "hashPassword",
          "file": "src/crypto.js",
          "line": 23
        },
        {
          "function": "db.findUser",
          "file": "external",
          "line": 0
        }
      ]
    },
    {
      "function": "createSession",
      "file": "src/sessions.js",
      "line": 12
    }
  ]
}
```

## Example 4: LLM Interaction

**User**: "Help me understand the authentication flow"

**LLM**:
```bash
# First, check if analysis exists
$ ls .llm-context/manifest.json
✓ Found

# Read system overview
$ cat .llm-context/summaries/L0-system.md
```

**L0 Content** (parsed by LLM):
```markdown
# My App - System Overview

**Type**: Web application with REST API
**Purpose**: User management and authentication platform

## Key Components
- **src/auth**: Authentication, authorization (12 functions)
- **src/users**: User CRUD operations (23 functions)
- **src/api**: REST endpoints (34 functions)
...

## Entry Points
- `main` (src/index.js:8)
- `handleRequest` (src/server.js:23)
```

**LLM**: Now query for auth-specific functions:
```bash
$ node query.js find-function auth
```

**Output**:
```
Found 8 results:

  1. authenticateUser (src/auth.js:45)
  2. authorizeRequest (src/auth.js:89)
  3. validateToken (src/auth.js:112)
  4. refreshToken (src/auth.js:145)
  ...
```

**LLM**: Trace the main auth flow:
```bash
$ node query.js trace authenticateUser
```

**LLM Response to User**:
```
Based on the codebase analysis, here's the authentication flow:

1. Entry point: `authenticateUser(credentials)` at src/auth.js:45

2. Call chain:
   authenticateUser
   ├─→ validateCredentials (src/auth.js:78)
   │   ├─→ hashPassword (src/crypto.js:23)
   │   └─→ db.findUser (database query)
   └─→ createSession (src/sessions.js:12)
       └─→ db.saveSession (database write)

3. Side effects:
   - Database reads (user lookup)
   - Database writes (session creation)
   - Logging (authentication attempts)

4. Dependencies:
   - Crypto module for password hashing
   - Database for user/session storage
   - Logger for audit trail

The flow is standard: validate credentials, create session, return token.
Would you like me to examine any specific step in detail?
```

## Example 5: Debugging

**User**: "There's a memory leak when processing large files"

**LLM**:
```bash
# Find file-related functions
$ node query.js find-function file

# Check for file I/O side effects
$ node query.js side-effects | grep file_io
```

**Output**:
```
  1. uploadFile (src/storage.js:12)
     Effects: file_io, network

  2. processFile (src/processor.js:45)
     Effects: file_io, logging

  3. readLargeFile (src/utils.js:89)
     Effects: file_io
```

**LLM**: Trace the suspect function:
```bash
$ node query.js trace processFile
```

**LLM Response**:
```
Potential memory leak found in file processing flow:

processFile (src/processor.js:45)
├─→ readLargeFile (src/utils.js:89) ⚠️
│   └─→ fs.readFileSync  ← Loads entire file into memory
├─→ transformData (src/processor.js:67)
└─→ uploadFile (src/storage.js:12)

Issue: readLargeFile uses fs.readFileSync which loads the entire
file into memory. For large files, this causes memory exhaustion.

Recommendation: Use streaming approach
[Shows specific code change with fs.createReadStream]
```

## Example 6: Refactoring Impact Analysis

**User**: "I want to rename `getUser` to `fetchUser`"

**LLM**:
```bash
# Find all callers
$ node query.js calls-to getUser
```

**Output**:
```
Found 15 callers:

  1. loadProfile (src/profile.js:23)
  2. authenticateUser (src/auth.js:56)
  3. updateUser (src/users.js:89)
  4. deleteUser (src/users.js:123)
  ...
  15. adminPanel (src/admin.js:234)
```

**LLM Response**:
```
Impact analysis for renaming getUser → fetchUser:

Affected files: 8
Affected functions: 15

Files that need updates:
1. src/profile.js (2 call sites)
2. src/auth.js (3 call sites)
3. src/users.js (4 call sites)
4. src/api.js (3 call sites)
5. src/admin.js (3 call sites)

After renaming, I'll help you:
1. Update all 15 call sites
2. Update tests
3. Re-run analysis to verify
```

## Example 7: Architecture Review

**User**: "Review the code architecture"

**LLM**:
```bash
$ node query.js stats
```

**Output**:
```json
{
  "totalFunctions": 234,
  "filesAnalyzed": 47,
  "totalCalls": 892,
  "withSideEffects": 67,
  "effectTypes": ["database", "network", "file_io", "logging"]
}
```

**LLM**: Read domain summaries:
```bash
$ cat .llm-context/summaries/L1-domains.json
```

**LLM Response**:
```
Architecture Review:

Structure:
- 234 functions across 47 files
- 892 call relationships (avg 3.8 calls/function)
- 67 functions with side effects (29%)

Domain Organization:
1. src/auth (12 functions) - ✅ Well-contained
2. src/api (34 functions) - ⚠️ Large, consider splitting
3. src/users (23 functions) - ✅ Appropriate size
4. src/data (45 functions) - ⚠️ High coupling

Side Effect Distribution:
- Database: 34 functions
- Network: 23 functions
- File I/O: 10 functions

Recommendations:
1. Split src/api into smaller modules (currently 34 functions)
2. Review src/data coupling (45 functions, many interdependencies)
3. Consider extracting network operations into dedicated service layer
4. Good separation of concerns in auth module
```

## Example 8: Pre-Commit Analysis

**Scenario**: Developer wants to check changes before committing

```bash
# Stage changes
$ git add .

# Check what changed
$ node change-detector.js
```

**Output**:
```
=== Change Detector ===

[3] Computing changes...
    M src/auth.js (MODIFIED)
    M src/users.js (MODIFIED)
    + src/validators.js (NEW)

=== Change Summary ===
Total files: 48
Changes detected: 3
  Added: 1
  Modified: 2
  Deleted: 0
  Unchanged: 45
```

**Run incremental analysis**:
```bash
$ node analyze.js
```

**Output**:
```
Files re-analyzed: 3
Files skipped: 45
Total functions in graph: 239 (+5)

New functions added:
- validateEmail (src/validators.js:12)
- validatePassword (src/validators.js:34)
- validateUsername (src/validators.js:56)
- isStrongPassword (src/auth.js:178)
- sanitizeInput (src/users.js:234)
```

**Developer can now confidently commit knowing:**
- 5 new functions added
- No breaking changes to existing functions
- Validation logic properly extracted

## Example 9: Python Project Analysis

**Scenario**: Analyzing a Python data processing project

```bash
$ cd ~/projects/data-pipeline
$ ls
src/ tests/ requirements.txt

$ node analyze.js
```

**Output**:
```
🔍 No previous analysis found - running initial full analysis...
[1/4] Running custom analysis (multi-language parsing)...
    Functions found: 47
    Call relationships: 134
    Side effects detected: 23
[2/4] Generating manifest...
✅ Initial analysis complete!
```

**Query Python Functions**:
```bash
$ node query.js side-effects
```

**Output**:
```
Found 23 results:

  1. load_data (src/data_loader.py:15)
     Calls: open, pd.read_csv
     Effects: file_io

  2. fetch_api_data (src/api_client.py:34)
     Calls: requests.get, response.json
     Effects: network, logging

  3. save_to_database (src/db_handler.py:56)
     Calls: sqlite3.connect, cursor.execute
     Effects: database

  4. process_large_file (src/processor.py:78)
     Calls: open, json.load, print
     Effects: file_io, logging
  ...
```

**Trace Python Function**:
```bash
$ node query.js trace process_pipeline
```

**Output**:
```json
{
  "function": "process_pipeline",
  "file": "src/pipeline.py",
  "line": 23,
  "async": true,
  "calls": [
    {
      "function": "load_data",
      "file": "src/data_loader.py",
      "line": 15,
      "effects": ["file_io"]
    },
    {
      "function": "transform_data",
      "file": "src/transformer.py",
      "line": 45
    },
    {
      "function": "save_to_database",
      "file": "src/db_handler.py",
      "line": 56,
      "effects": ["database"]
    }
  ]
}
```

**LLM Analysis**:
```
Python pipeline analysis:

Flow: process_pipeline (async) → load_data → transform_data → save_to_database

Side effects:
- File I/O: load_data reads CSV files
- Database: save_to_database writes to SQLite
- Logging: Multiple print() statements for progress

The async pipeline efficiently processes data through three stages:
1. Data loading from CSV
2. Transformation (pure function, no side effects)
3. Database persistence

Recommendations:
- Consider adding error handling for file operations
- Database connection should be reused, not created per call
```

## Example 10: Mixed Language Codebase

**Scenario**: Full-stack project with JavaScript backend and Python ML service

```bash
$ cd ~/projects/ml-app
$ ls
backend/ (Node.js)
ml-service/ (Python)

$ node analyze.js
```

**Output**:
```
🔍 Running analysis...
[2] Discovering source files...
    Found 67 source files (42 JavaScript, 25 Python)

[3] Running custom analysis (multi-language parsing)...
    Functions found: 156 (98 JavaScript, 58 Python)
    Call relationships: 387
    Side effects detected: 67
```

**Query by Language**:
```bash
# Find all Python functions
$ node query.js find-function . | grep "\.py:"

# Find network calls in both languages
$ node query.js side-effects | grep network
```

**Output**:
```
JavaScript (backend/):
  1. handleRequest (backend/api.js:23)
     Effects: network, database

  2. fetchUserData (backend/services.js:45)
     Effects: network

Python (ml-service/):
  3. predict (ml-service/model.py:67)
     Effects: network, logging

  4. train_model (ml-service/trainer.py:34)
     Effects: file_io, logging
```

**Cross-language analysis works seamlessly:**
- Single unified graph for all functions
- Language-specific side effect detection
- Same query interface for all languages
- Incremental updates work across languages
