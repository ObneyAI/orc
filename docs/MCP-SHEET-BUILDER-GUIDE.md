# MCP Sheet Builder: Complete Architecture Guide

## Start Here: The Story

You have access to MCP tool servers — Linear, GitHub, a database, an nREPL, whatever speaks the [Model Context Protocol](https://modelcontextprotocol.io). Normally, to put those tools to work inside a behavior tree, you'd **hand-wire the tree yourself**: decide which tool feeds which, lift each tool's schema onto the blackboard, choose the control nodes, and connect it all up by hand.

The **MCP Sheet Builder** writes that first draft for you. Point it at an MCP server and it reads the server's tool schemas and **generates an ORC behavior tree** — a real, runnable workflow — automatically. Think of it as a bootstrapper: it gets you from *"here are some tools"* to *"here is a working tree"* in a single call. And the tree it hands back is a perfectly normal ORC tree that you then **refine by hand**.

### The simplest path

Four conceptual steps — connect, analyze, generate, execute:

```clojure
(require '[ai.obney.orc.mcp-sheet-builder.interface :as msb]
         '[ai.obney.orc.orc-service.interface :as sheet])

;; 1. CONNECT to an MCP server (a built-in preset, or any HTTP MCP endpoint)
(def conn (msb/connect {:preset :nrepl}))
;; or: (msb/connect {:type :http :url "https://your-mcp-server.example/mcp"})

;; 2. ANALYZE its tools — capabilities, relationships, candidate patterns
(def analysis (msb/analyze-tools conn))

;; 3. GENERATE + build an ORC behavior tree from those tools, in the event store.
;;    build-sheet-from-mcp! collapses steps 1–3 into one call.
(def result (msb/build-sheet-from-mcp! ctx {:preset :nrepl}))
;; => {:sheet-id #uuid "..." :workflow-name "mcp-workflow-..."
;;     :pattern :research-compilation :tools [...] :analysis {...}}

;; 4. EXECUTE the generated tree like any other ORC sheet
(sheet/execute ctx (:sheet-id result) {:query "..."})
```

That's the whole loop. `build-sheet-from-mcp!` is the one-call shortcut; `connect` + `analyze-tools` are there when you want to inspect the analysis before committing to a tree.

### This is Layer 8 — standalone

The MCP Sheet Builder is **Layer 8 in [COMPONENT-MAP.md](COMPONENT-MAP.md)**: a standalone capability. You do **not** need the ontology, ColBERT, or the self-improving loop to use it. It connects to any MCP server, analyzes tool schemas, and emits executable ORC behavior-tree workflows — and nothing more is required.

That's not a claim, it's the dependency list. Source: `components/mcp-sheet-builder/deps.edn` lists only `clj-http`, `cheshire`, and `sci` — no `orc/ontology`, no Python:

```clojure
;; components/mcp-sheet-builder/deps.edn (verbatim)
{:paths ["src" "resources"]
 :deps {clj-http/clj-http {:mvn/version "3.12.3"}
        cheshire/cheshire {:mvn/version "5.13.0"}
        org.babashka/sci {:mvn/version "0.8.43"}}
 :aliases {:test {:extra-paths ["test"]
                  :extra-deps {}}}}
```

### The output is just an ORC tree

The generated sheet is **not a black box**. It's an ordinary ORC behavior tree — the same shape you'd build by hand with the orc DSL. So once it's generated you can:

- **Edit it** — tweak instructions, swap models, add or remove leaves.
- **Compose it into a larger tree** — drop the generated sheet in as a subtree of a bigger workflow (e.g. via a `:delegate` node), so the bootstrapped tool-calling logic becomes one step in a richer pipeline.
- **Attach judges to it** — wire in LLM-as-judge evaluation around its outputs to gate quality.

The MCP Sheet Builder gives you the first draft; ORC gives you everything you'd do with any other tree afterward.

---

This document explains the MCP Sheet Builder system - a meta-system that generates behavior tree workflows from MCP tool schemas.

---

## Executive Summary

We built a **"sheet that builds sheets"** - a meta-system that:

1. **Connects to any MCP server** (HTTP/SSE, your MCP client's pre-loaded tools, or static presets)
2. **Analyzes tools** to understand capabilities and relationships
3. **Matches patterns** (Google's 8 Agent Patterns)
4. **Generates ORC behavior trees** automatically
5. **Executes with real MCP tool calls**

The REPL Researcher node adds **iterative reasoning** - LLM generates code, executes it, sees results, refines until convergence.

**Test Results:**
- 41/41 POC tests passed (100%)
- Average quality score: 1.00
- Live execution verified against an nREPL MCP server and a documentation-search MCP server

---

## Quick Start

### 1. Configure MCP Access (`.mcp.json` at project root)

```json
{
  "mcpServers": {
    "nrepl": {
      "type": "stdio",
      "command": "npx",
      "args": ["nrepl-mcp-server"]
    }
  }
}
```

Restart your MCP client after creating this file.

### 2. Build and Execute a Sheet

```clojure
(require '[ai.obney.orc.mcp-sheet-builder.interface :as msb]
         '[ai.obney.orc.orc-service.interface :as sheet])

;; No ctx with ontology needed — this is Layer 8 standalone
;; Build sheet from MCP tools
(def result (msb/build-sheet-from-mcp! ctx {:preset :nrepl}))

;; Execute it
(sheet/execute ctx (:sheet-id result) {:query "What namespaces are loaded?"})
```

### 3. Using MCP Tools in Other Projects

**Option A: Copy `.mcp.json`** to the other project, restart your MCP client

**Option B: Import component** via deps.edn:
```clojure
{:deps {obneyai/orc
        {:git/url "https://github.com/ObneyAI/orc.git"
         :git/sha "..."
         :deps/root "components/mcp-sheet-builder"}}}
```

**Option C: Export sheet as EDN** for portable workflows:
```clojure
(spit "my-workflow.edn" (pr-str (:workflow-data sheet-data)))
```

---

## Part 1: The 8 Agent Patterns

### Pattern Definitions

All patterns are defined in `mcp-sheet-builder/core/patterns.clj`:

| # | Pattern | Description | ORC Nodes | Min Tools | Required Relationships |
|---|---------|-------------|-----------|-----------|------------------------|
| 1 | **Sequential Pipeline** | Linear data flow (A -> B -> C) | `sequence` | 2 | `:sequential` |
| 2 | **Coordinator/Dispatcher** | Route to specialist tools | `fallback`, `llm-condition` | 3 | `:alternative` |
| 3 | **Parallel Fan-Out** | Independent parallel work + synthesis | `parallel`, `sequence` | 2 | `:parallel` |
| 4 | **Hierarchical Decomposition** | Break into subtasks | `sequence`, `parallel` | 3 | `:sequential`, `:parallel` |
| 5 | **Generator/Critic** | Generate then validate | `sequence`, `fallback` | 2 | `:refinement` |
| 6 | **Iterative Refinement** | Progressive improvement loop | `fallback`, `sequence` | 2 | `:refinement`, `:sequential` |
| 7 | **Research Compilation** | Multi-source gathering + synthesis | `parallel`, `map-each`, `sequence` | 2 | Tools with `:retrieval`, `:search` |
| 8 | **Adversarial Analysis** | Opposing views + judge | `parallel`, `sequence` | 3 | Tools with `:generate` |

### How Patterns Are Selected

**Confidence Calculation:**
```
confidence = base (0.5 if enough tools, 0.3 otherwise)
           + requirement_bonus (0.1 per satisfied requirement)
           + density_bonus (0-0.2 based on relationship count)
```

**Selection Process:**
1. Analyze tools -> extract capabilities (`:search`, `:retrieval`, `:generate`, etc.)
2. Detect relationships (`:sequential`, `:parallel`, `:alternative`, `:refinement`)
3. Check each pattern's requirements against detected relationships
4. Calculate confidence scores
5. Return sorted list (highest confidence first)

### Adding New Patterns

The pattern system is **extensible via code modification**:

**Step 1: Add pattern definition** in `patterns.clj`:
```clojure
:your-new-pattern
{:name "Your Pattern Name"
 :description "What it does"
 :orc-nodes [:sequence :llm]
 :min-tools 2
 :requires #{:sequential}}
```

**Step 2: Add generator function** in `generator.clj`:
```clojure
(defn generate-your-new-pattern [tools relationships]
  `(sheet/sequence "your-pattern"
     ...))
```

**Step 3: Add case clause** in `generate-workflow-tree`:
```clojure
:your-new-pattern (generate-your-new-pattern tools relationships)
```

---

## Part 2: The Behavior Tree Tick System

### How ORC Executes Workflows

The orc-service implements a **synchronous, blocking execution model**:

```
User Input -> Execute(sheet-id, inputs) -> Tree Traversal -> Outputs
```

**Key file:** `components/orc-service/src/.../core/runtime.clj`

### Node Types and Control Flow

```
CONTROL NODES
-------------
sequence    : Execute children in order
              Stop on FIRST failure (short-circuit AND)

fallback    : Execute children in order
              Stop on FIRST success (short-circuit OR)

parallel    : Execute ALL children concurrently
              Merge blackboards by version tracking
              Success/failure policies: :all, :any, :majority

map-each    : Iterate over collection
              Execute child for each item
              Collect outputs into vector


LEAF NODES
----------
llm             : Call LLM via orc's structured LLM provider
                  Reads blackboard keys -> LLM -> Writes outputs

code            : Call Clojure function
                  Function: {:inputs {...}} -> {:output value}

condition       : Check blackboard value
                  Operators: :equals :gt :lt :contains :exists

llm-condition   : LLM decides yes/no

repl-researcher : Iterative LLM + code execution
                  Generates code -> Executes -> Iterates
                  Until FINAL_ANSWER or max iterations
```

### Blackboard State Flow

The **blackboard** is the single source of truth for data flow:

```clojure
;; Blackboard entry structure
{:key-name {:key :key-name
            :schema [:string {:description "..."}]
            :value "current value"
            :version 3}}  ;; Increments on each write
```

**Data flows through nodes:**
```
Node A (writes :result)  ->  Blackboard  ->  Node B (reads :result)
         |                       |                |
   {:outputs          version increments    {:inputs
    {:result val}}                          {:result val}}
```

### Execution Example

```clojure
;; Define a simple workflow
(sheet/workflow "qa-workflow"
  (sheet/blackboard
    {:question :string
     :answer :string})
  (sheet/llm "answer-question"
    :model "google/gemini-2.5-flash"
    :instruction "Answer the question concisely"
    :reads [:question]
    :writes [:answer]))

;; Build it
(def sheet-id (sheet/build-workflow! ctx workflow))

;; Execute it
(sheet/execute ctx sheet-id {:question "What is a behavior tree?"})
;; => {:status :success
;;     :outputs {:answer "A behavior tree is a tree of control and leaf nodes..."}
;;     :duration-ms 1234}
```

---

## Part 3: The REPL Researcher Node

### What It Does

The `repl-researcher` node implements the **RLM (Recursive Language Model)** pattern:

```
                    REPL RESEARCHER LOOP
+-------------------------------------------------------------+
|                                                              |
|   +-------------+     +-------------+     +-------------+   |
|   | LLM: Generate| -> | SCI: Execute| -> | Capture      |  |
|   | Clojure code |    | in sandbox  |    | stdout +     |  |
|   | calling MCP  |    | with MCP    |    | result       |  |
|   +-------------+     | tools       |    +-------------+   |
|          ^            +-------------+            |          |
|          |                                       |          |
|          +-- feedback: history of iterations ----+          |
|                                                              |
|   Convergence: FINAL_ANSWER detected OR max iterations     |
+-------------------------------------------------------------+
```

### Key Implementation Details

**File:** `components/orc-service/src/.../core/executor.clj` (lines 733-866)

1. **SCI Sandbox Safety**
   - Only 46 safe clojure.core functions allowed
   - No `eval`, `slurp`, `require`, or IO
   - MCP tools injected as callable functions

2. **MCP Tool Injection**
   ```clojure
   ;; MCP tools become callable in SCI namespace
   (searchDocs {:query "tracing"})
   (getDocsPage {:pathOrUrl "/docs/tracing"})
   ```

3. **Convergence Detection**
   - Regex patterns for `FINAL_ANSWER: ...`
   - Repeated output detection (loop protection)
   - Max iterations fail-safe

4. **Metadata-Only Context**
   - LLM sees variable names and types, NOT values
   - Forces LLM to use tools to discover information

### DSL Usage

```clojure
(sheet/repl-researcher "research"
  :model "google/gemini-2.5-flash"
  :instruction "Research how to trace LLM calls and write a how-to"
  :reads [:question]           ;; Blackboard keys (metadata only)
  :writes [:answer]            ;; Output key for final result
  :mcp-tools ["searchDocs" "getDocsPage"]
  :max-iterations 10)
```

### How It Fits Into Tick System

The REPL Researcher is a **leaf node** that:

1. Receives execution call from runtime (line 454-510 in runtime.clj)
2. Runs its internal iteration loop (blocking)
3. Returns `{:status :success/:failure :outputs {...}}`
4. Outputs are written to blackboard like any other leaf

```
runtime.clj:execute-node-sync
    |
case :repl-researcher
    |
executor.clj:execute-repl-researcher
    |
loop [iteration 0, history []]
    |
llm-provider/predict -> generate code
    |
sci-sandbox/execute-code -> run in sandbox
    |
FINAL_ANSWER? -> return {:status :success :outputs {...}}
```

---

## Part 4: The MCP Sheet Builder Pipeline

### Complete Architecture

```
+-------------------------------------------------------------+
| 1. MCP CONNECTION                                            |
| connect({:preset :nrepl})                                   |
| connect({:type :http :url "https://mcp.example.com"})       |
+------------------------+------------------------------------+
                         |
+------------------------v------------------------------------+
| 2. TOOL DISCOVERY                                            |
| list-tools(mcp-conn) -> [{:name "searchDocs"                |
|                          :description "Semantic search..."   |
|                          :inputSchema {...}}]                |
+------------------------+------------------------------------+
                         |
+------------------------v------------------------------------+
| 3. ANALYSIS                                                  |
| analyze-tools() -> {:capabilities #{:search :retrieval}     |
|                    :category :data-access                    |
|                    :idempotent? true                         |
|                    :malli-input [:map [:query :string]]}    |
+------------------------+------------------------------------+
                         |
+------------------------v------------------------------------+
| 4. RELATIONSHIP DETECTION                                    |
| detect-relationships() -> [{:type :sequential               |
|                            :from "searchDocs"               |
|                            :to "getDocsPage"                |
|                            :confidence 0.8}]                 |
+------------------------+------------------------------------+
                         |
+------------------------v------------------------------------+
| 5. PATTERN MATCHING (Google's 8 Agent Patterns)              |
| select-patterns() -> [{:pattern :research-compilation       |
|                       :confidence 0.92}                      |
|                      {:pattern :sequential-pipeline          |
|                       :confidence 0.75}]                     |
+------------------------+------------------------------------+
                         |
+------------------------v------------------------------------+
| 6. SHEET GENERATION                                          |
| generate-sheet-data() -> {:workflow-data {...}              |
|                          :blackboard {:query :string ...}}  |
+------------------------+------------------------------------+
                         |
+------------------------v------------------------------------+
| 7. BUILD SHEET                                               |
| sheet/build-workflow!(ctx, workflow-data)                   |
| -> sheet-id (UUID, ready to execute)                        |
+-------------------------------------------------------------+
```

### Connection Types

**1. Static Presets** (for testing/POC)
```clojure
(msb/connect {:preset :nrepl})  ;; Pre-defined tools, no network
```

**2. HTTP/SSE** (for real MCP servers)
```clojure
(msb/connect {:type :http
              :url "https://mcp-server.example.com"
              :api-key "sk-..."})
```

**3. Pre-loaded client tools** (`:claude-mcp` type — tools already loaded by your MCP client)
```clojure
(msb/connect {:type :claude-mcp
              :tools [...pre-loaded tool definitions...]})
```

---

## Part 5: How to Leverage This System

### Basic Usage: Build a Sheet from MCP Server

```clojure
(require '[ai.obney.orc.mcp-sheet-builder.interface :as msb]
         '[ai.obney.orc.orc-service.interface :as sheet])

;; 1. Build sheet from MCP server (one-liner)
(def result
  (msb/build-sheet-from-mcp! ctx
    {:type :http
     :url "https://your-mcp-server.com"
     :api-key "your-api-key"}
    {:pattern :research-compilation}))  ;; Optional: auto-selects if omitted

;; Result contains:
;; {:sheet-id #uuid "..."
;;  :workflow-name "mcp-workflow-..."
;;  :pattern :research-compilation
;;  :tools ["tool1" "tool2" ...]
;;  :analysis {...}}

;; 2. Execute the sheet
(sheet/execute ctx (:sheet-id result) {:query "How do I trace LLM calls?"})
```

### Advanced: REPL Researcher for Adaptive Tool Use

```clojure
;; Build a repl-researcher sheet that iteratively calls tools
(def researcher
  (msb/build-repl-researcher-sheet! ctx
    {:type :http :url "https://your-docs-mcp-server.example/mcp"}
    "Research everything about LLM tracing and provide a comprehensive guide"
    {:max-iterations 5}))

;; Execute - the LLM will generate code, call tools, iterate
(sheet/execute ctx (:sheet-id researcher) {:question "What is tracing?"})
```

### Production Workflow

```
+-------------------------------------------------------------+
| 1. CONFIGURE MCP SERVER                                      |
|    - URL: https://your-mcp-server.com                       |
|    - API Key: sk-...                                        |
|    - Or use preset: :nrepl, etc.                            |
+------------------------+------------------------------------+
                         |
+------------------------v------------------------------------+
| 2. BUILD SHEET (one-time or on tool changes)                |
|    (msb/build-sheet-from-mcp! ctx mcp-opts)                 |
|    Returns: sheet-id                                        |
+------------------------+------------------------------------+
                         |
+------------------------v------------------------------------+
| 3. EXECUTE (many times with different inputs)               |
|    (sheet/execute ctx sheet-id {:query "..."})             |
|    Returns: {:status :success :outputs {...}}               |
+-------------------------------------------------------------+
```

### Adding New MCP Servers

To add support for a new MCP server:

1. **Option A: HTTP Connection (recommended for external servers)**
   ```clojure
   (msb/build-sheet-from-mcp! ctx
     {:type :http
      :url "https://new-server.com/mcp"
      :api-key "..."})
   ```

2. **Option B: Add Static Preset (for testing)**
   - Edit `mcp_client.clj` lines 109-154
   - Add tool definitions to `known-servers` map

3. **Option C: Pre-loaded Client Tools (`:claude-mcp` adapter)**
   - For tools already loaded by your MCP client
   - Wrap as callable functions

---

## Part 6: Why the Results Are Strong

### Architecture Advantages

1. **Explicit Schemas**
   - Malli schemas with descriptions -> LLMs know exactly what to output
   - No `:any` types -> no ambiguity

2. **Structured-Output Integration**
   - Output flattening into structured fields, then automatic reassembly of nested structures
   - Structured `[[ ## field ## ]]` markers for reliable parsing

3. **Pattern-Based Generation**
   - Not random workflow generation
   - Matches tool capabilities to proven agent patterns
   - Google's 8 patterns are battle-tested

4. **Iterative Refinement (REPL Researcher)**
   - LLM can explore and correct itself
   - Tools provide real data, not hallucinations
   - Convergence detection prevents infinite loops

### Quality Test Results

```
+----------------------------------------------------+
|          MCP SHEET QUALITY REPORT                  |
+----------------------------------------------------+
| Q-001: Trace LLM calls             Score: 0.72     |
| Q-002: Traces vs Spans             Score: 0.72     |
| Q-003: Python SDK Install          Score: 0.72     |
| Q-004: Framework Integration       Score: 1.00     |
+----------------------------------------------------+
| Average Quality Score: 0.79                        |
| Tests Passing (>0.6): 4/4                          |
+----------------------------------------------------+
```

---

## Key Files Reference

| Purpose | File | Key Functions |
|---------|------|---------------|
| **Sheet Execution** | `orc-service/core/runtime.clj` | `execute`, `execute-node-sync` |
| **Node Execution** | `orc-service/core/executor.clj` | `execute-ai`, `execute-repl-researcher` |
| **DSL Macros** | `orc-service/core/dsl.clj` | `workflow`, `llm`, `repl-researcher` |
| **MCP Client** | `mcp-sheet-builder/core/mcp_client.clj` | `connect`, `list-tools`, `call-tool` |
| **Tool Analysis** | `mcp-sheet-builder/core/analyzer.clj` | `analyze-tool`, `analyze-tools` |
| **Pattern Matching** | `mcp-sheet-builder/core/patterns.clj` | `select-patterns`, `best-pattern` |
| **Sheet Generation** | `mcp-sheet-builder/core/generator.clj` | `generate-sheet-data` |
| **SCI Sandbox** | `mcp-sheet-builder/core/sci_sandbox.clj` | `build-sci-context`, `execute-code` |
| **Public API** | `mcp-sheet-builder/interface.clj` | `build-sheet-from-mcp!` |

---

## Summary

**What we built:**
- A complete meta-system for generating behavior tree workflows from MCP tool schemas
- REPL Researcher node for iterative LLM+code execution
- Pattern matching based on Google's 8 Agent Patterns
- Safe SCI sandbox with MCP tool injection

**The 8 Agent Patterns:**
- Sequential Pipeline, Coordinator/Dispatcher, Parallel Fan-Out, Hierarchical Decomposition
- Generator/Critic, Iterative Refinement, Research Compilation, Adversarial Analysis
- Extensible by adding to `patterns.clj` and `generator.clj`

**How the REPL Researcher fits in:**
- It's a leaf node in the behavior tree tick system
- Runs an internal iteration loop (blocking)
- LLM generates Clojure code -> executes in sandbox -> feeds results back
- Converges when `FINAL_ANSWER:` detected

**Future usage:**
1. Configure MCP server (URL + API key, or preset)
2. Call `build-sheet-from-mcp!` to generate a sheet
3. Execute with `sheet/execute` and inputs
4. For adaptive tool use, use `build-repl-researcher-sheet!`

The system automatically analyzes tools, detects relationships, selects appropriate patterns, and generates valid ORC behavior trees. The strong results come from explicit schemas, proven patterns, and iterative refinement capabilities.

---

## Part 7: Portable Export

### The Problem

Generated sheets depend on the MCP Sheet Builder at runtime. What if you want to:
- Share a workflow with another team
- Deploy to a server without the full MCP component
- Version control standalone workflow files

### The Solution: Portable Export

Export MCP-generated sheets as **standalone `.clj` files** that work with only `orc-service`:

```clojure
(require '[ai.obney.orc.mcp-sheet-builder.interface :as msb])

;; Export executor definitions to standalone file
(msb/export-executors!
  [{:tool-name "searchDocs" :input-schema {"type" "object" "properties" {"query" {"type" "string"}}}}
   {:tool-name "getPage" :input-schema {"type" "object" "properties" {"path" {"type" "string"}}}}]
  "my-executors.clj"
  :namespace "my-executors")

;; Export complete portable sheet package
(msb/export-portable-sheet!
  workflow-data                    ;; The workflow data structure
  executor-defs                    ;; Executor definitions
  "exports/"                       ;; Output directory
  "my-research")                   ;; Base name

;; Creates two files:
;; - exports/my-research-executors.clj  (all executor functions)
;; - exports/my-research.clj            (workflow + build! function)
```

### Generated File Structure

**Executors file (`my-research-executors.clj`):**
```clojure
(ns my-research-executors
  "Generated MCP tool executors.
   Auto-generated by MCP Sheet Builder - do not edit directly."
  (:require [ai.obney.orc.mcp-sheet-builder.core.mcp-client :as mcp-client]
            [com.brunobonacci.mulog :as u]))

(defn call-searchDocs
  "Generated executor for MCP tool: searchDocs"
  [{:keys [inputs context]}]
  (let [mcp-session (:mcp-session context)
        tool-args {"query" (get inputs :query)}]
    (if mcp-session
      (let [result (mcp-client/call-tool mcp-session "searchDocs" tool-args)]
        {:searchDocs-result result})
      {:searchDocs-result {:mock true :tool "searchDocs" :args tool-args}})))

;; ... more executors ...

(def executors
  {"searchDocs" call-searchDocs
   "getPage" call-getPage})
```

**Workflow file (`my-research.clj`):**
```clojure
(ns my-research
  "my-research - Generated by MCP Sheet Builder."
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [my-research-executors :as exec]))

(def workflow
  "Workflow definition - pass to (sheet/build-workflow! ctx workflow)"
  {:workflow-name "my-research"
   :blackboard-schema {...}
   :root-node {:node-type :sequence
               :children [{:node-type :leaf
                           :executor :code
                           :fn "my-research-executors/call-searchDocs"  ;; Points to exported executors
                           :reads [:query]
                           :writes [:searchDocs-result]}
                          ...]}})

(defn build!
  "Build this workflow in the event store. Returns sheet-id."
  [ctx]
  (sheet/build-workflow! ctx workflow))
```

### Full Pipeline: Build + Export

```clojure
;; Complete pipeline: MCP → analyze → build → export
(def result
  (msb/build-and-export-portable! ctx
    {:type :http :url "https://your-docs-mcp-server.example/mcp"}  ;; MCP connection options
    "exports/"                    ;; Output directory
    {:pattern :research-compilation
     :name "docs-research"}))     ;; Optional: custom name

;; Result:
;; {:sheet-id #uuid "..."
;;  :workflow-name "docs-research"
;;  :sheet-file "exports/docs-research.clj"
;;  :executors-file "exports/docs-research-executors.clj"
;;  :tools ["searchDocs" "getDocsPage" "getOverview"]}
```

### Using Exported Sheets

In any project with `orc-service`:

```clojure
;; 1. Load the exported files
(load-file "exports/docs-research-executors.clj")
(load-file "exports/docs-research.clj")

;; 2. Require the workflow namespace
(require 'docs-research)

;; 3. Build the sheet
(def sheet-id (docs-research/build! ctx))

;; 4. Execute with MCP session in context
(sheet/execute ctx sheet-id
  {:query "How do traces work?"}
  :context {:mcp-session my-mcp-connection})
```

---

## Part 8: Dynamic Executor System

### The Problem

What if you want to:
- Generate executors at runtime without exporting files
- Persist generated executors in the event store
- Reload executors on application restart

### The Solution: Dynamic Executor Loading

```clojure
(require '[ai.obney.orc.mcp-sheet-builder.interface :as msb])

;; Generate executor definitions
(def exec-defs
  (msb/generate-executors
    [{:name "searchDocs" :inputSchema {"type" "object" "properties" {"query" {"type" "string"}}}}
     {:name "getPage" :inputSchema {"type" "object" "properties" {"path" {"type" "string"}}}}]))

;; Each definition contains:
;; {:tool-id #uuid "..."
;;  :tool-name "searchDocs"
;;  :source-code "(ns mcp.executors.dynamic.t1234...)"
;;  :namespace-requires ["ai.obney...mcp-client" "...mulog"]
;;  :checksum "sha256-hash"
;;  :fn-reference "mcp.executors.dynamic.t1234/call-searchDocs"}
```

### Dynamic Loading into Isolated Namespaces

```clojure
;; Load executor into runtime registry
(let [{:keys [tool-id tool-name source-code namespace-requires checksum]} (first exec-defs)]
  (msb/load-executor! tool-id tool-name source-code namespace-requires
                      :checksum checksum))  ;; Optional: verify integrity

;; Resolve and call
(let [executor (msb/get-executor "searchDocs")]
  (executor {:inputs {:query "test"}
             :context {:mcp-session my-conn}}))

;; List all loaded executors
(msb/list-loaded-executors)
;; => ({:tool-id #uuid "..." :tool-name "searchDocs" :namespace mcp.executors.dynamic.t1234 :fn-reference "..."})

;; Clear for testing
(msb/clear-executor-registry!)
```

### SHA-256 Integrity Verification

Generated code includes checksums for tamper detection:

```clojure
;; Checksum computed on generation
(:checksum exec-def)
;; => "a1b2c3d4e5f6..."

;; Verified on load
(msb/load-executor! tool-id tool-name source-code requires
                    :checksum "a1b2c3d4e5f6...")  ;; Throws if mismatch
```

### Build Sheet with Persistent Executors

```clojure
;; Build sheet AND persist executors to event store
(def result
  (msb/build-sheet-with-executors! ctx
    {:type :http :url "https://your-docs-mcp-server.example/mcp"}
    {:pattern :research-compilation}))

;; Returns:
;; {:sheet-id #uuid "..."
;;  :workflow-name "..."
;;  :pattern :research-compilation
;;  :executors [{:tool-id #uuid "..." :tool-name "searchDocs" :fn-reference "..."}]
;;  :load-results [{:success? true :namespace ...}]
;;  :mcp-connection {...}}
```

### Key Files Reference (Updated)

| Purpose | File | Key Functions |
|---------|------|---------------|
| **Executor Generation** | `mcp-sheet-builder/core/executor_generator.clj` | `generate-executor-code`, `build-executor-definition`, `compute-checksum` |
| **Executor Runtime** | `mcp-sheet-builder/core/executor_runtime.clj` | `load-executor!`, `get-executor`, `list-loaded-executors`, `clear-registry!` |
| **Portable Export** | `mcp-sheet-builder/interface.clj` | `export-executors!`, `export-portable-sheet!`, `build-and-export-portable!` |

---

## Summary (Updated)

**New capabilities in this release:**

1. **Portable Export** - Export MCP-generated sheets as standalone `.clj` files
   - `export-executors!` - Export just the executor functions
   - `export-portable-sheet!` - Export complete package (workflow + executors)
   - `build-and-export-portable!` - Full pipeline: MCP → build → export

2. **Dynamic Executor System** - Runtime executor loading
   - SHA-256 checksums for integrity verification
   - Isolated namespace loading (no pollution)
   - Registry for executor lookup by name/ID/qualified-reference
   - `build-sheet-with-executors!` - Build + persist executors

**Use cases:**
- Share workflows with teams who don't have MCP access
- Deploy to production servers with minimal dependencies
- Version control standalone workflow definitions
- Generate executors dynamically from new MCP servers
