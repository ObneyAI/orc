# ORC

**Orchestrator** — a behavior-tree workflow execution engine built on [Grain](https://github.com/ObneyAI/grain).

ORC provides composable primitives for building, executing, optimizing, and evaluating LLM-powered workflows. It's designed as a library that consumers pull in as a git dependency.

> **Early-stage software.** ORC is under active development. Expect sharp edges and breaking changes — APIs, event schemas, and conventions may shift between commits. Pin to a specific `:git/sha` and review the diff before updating. Expect incomplete docs, use at your own peril!

## Quick Start

Add to your `deps.edn`:

```clojure
obneyai/orc {:git/url "https://github.com/ObneyAI/orc.git"
             :git/sha "..."
             :deps/root "projects/orc"}
```

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])

;; Define a workflow using the DSL
(def my-workflow
  (orc/workflow "summarizer"
    (orc/blackboard
      {:input   :string
       :summary :string})
    (orc/sequence "main"
      (orc/llm "summarize"
        :instruction "Summarize the input text in 2 sentences."
        :reads [:input]
        :writes [:summary]))))

;; Build it (idempotent — no-op if definition hasn't changed)
(orc/build-workflow! ctx my-workflow)

;; Execute it
(orc/execute ctx sheet-id {:input "Long article text..."})
;; => {:status :success, :outputs {:summary "..."}, :duration-ms 1234}
```

## Components

| Component | Namespace | Purpose |
|-----------|-----------|---------|
| **orc-service** | `ai.obney.orc.orc-service` | Core behavior tree execution, DSL, versioning, event sourcing |
| **gepa** | `ai.obney.orc.gepa` | LLM instruction optimization with Pareto frontier selection |
| **evaluation** | `ai.obney.orc.evaluation` | LLM-as-judge evaluation (grounding, reasoning, completeness) |
| **colbert** | `ai.obney.orc.colbert` | Late-interaction retrieval via Python ColBERT bridge |
| **ontology** | `ai.obney.orc.ontology` | Three-layer concept graph with embeddings and pattern discovery |
| **mcp-sheet-builder** | `ai.obney.orc.mcp-sheet-builder` | Dynamic workflow generation from MCP tool schemas |
| **langfuse** | `ai.obney.orc.langfuse` | Observability and tracing integration |

## Architecture

ORC is built on the **Grain** event-sourcing framework (CQRS pattern):

```
Commands -> Events -> Read Models -> Queries
              |
              v
        Todo Processors (side effects)
```

- **Sheets** are behavior trees stored as event streams
- **Nodes** are composable: `sequence`, `fallback`, `parallel`, `map-each`, `llm`, `code`, `condition`, `repl-researcher`
- **Execution** dispatches through the command processor, runs asynchronously via todo processors, and delivers results through a completion registry
- **Versioning** supports draft/published modes with stash/restore
- **The DSL** provides a declarative API for building workflows without touching events directly

### Execution Flow

```
1. orc/execute dispatches :sheet/tick-tree command
2. Command creates execution snapshot (isolated blackboard)
3. Event triggers todo processor (async)
4. Processor walks the behavior tree:
   - sequence: run children in order, fail on first failure
   - fallback: run children in order, succeed on first success
   - parallel: run children concurrently
   - llm: call LLM via DSCloj
   - code: evaluate Clojure via SCI
   - repl-researcher: iterative code generation + MCP tool calling
5. Result delivered via completion promise
```

### Optimization Loop (GEPA)

```
1. Define metric functions (exact-match, contains, judge-based)
2. Start optimization with training examples
3. GEPA proposes instruction variants
4. Evaluates candidates against metrics
5. Pareto frontier selection (multi-objective)
6. Repeat until budget exhausted
```

## Node Types

| Node | Type | Description |
|------|------|-------------|
| `orc/sequence` | Composite | Run children in order. Fails on first failure. |
| `orc/fallback` | Composite | Run children in order. Succeeds on first success. |
| `orc/parallel` | Composite | Run all children concurrently. |
| `orc/map-each` | Composite | Map a subtree over a collection input. |
| `orc/llm` | Leaf | Call an LLM with instruction + inputs -> outputs. |
| `orc/code` | Leaf | Execute Clojure code (SCI sandbox). |
| `orc/condition` | Leaf | Branch based on code predicate. |
| `orc/llm-condition` | Leaf | Branch based on LLM yes/no judgment. |
| `orc/repl-researcher` | Leaf | Iterative: generate code, call MCP tools, refine. |
| `orc/delegate` | Leaf | Execute another workflow with isolated blackboard. |

## Development Setup

### Prerequisites

- **Java 21+** (with module access for LMDB)
- **Clojure CLI** (`brew install clojure/tools/clojure`)
- **Python 3.10+** (optional, for ColBERT semantic search)

### Getting Started

```bash
# Clone
git clone git@github.com:ObneyAI/orc.git && cd orc

# Start nREPL (includes JVM flags for LMDB)
./scripts/nrepl.sh
```

### Running Tests

```bash
clj -M:poly test                     # changed bricks only
clj -M:poly test :all-bricks         # all bricks
clj -M:poly test brick:orc-service   # specific brick
```

### ColBERT Setup (Optional)

ColBERT provides late-interaction semantic retrieval. It requires a separate Python environment:

```bash
./scripts/setup-colbert.sh
```

This creates `.venv-colbert/` with RAGatouille, PyTorch, and sentence-transformers. The Clojure `colbert` component communicates with Python via `scripts/colbert_bridge.py` (subprocess JSON-RPC).

### Project Structure

```
orc/
├── CLAUDE.md                  # AI assistant instructions
├── README.md
├── deps.edn                   # Dev alias + Polylith config
├── workspace.edn              # Polylith workspace (top-ns: ai.obney.orc)
├── python.edn                 # libpython-clj config
├── requirements.txt           # Python dependencies
├── scripts/
│   ├── colbert_bridge.py      # ColBERT Python subprocess
│   └── setup-colbert.sh       # ColBERT environment setup
├── components/
│   ├── orc-service/           # Core execution engine
│   ├── gepa/                  # Prompt optimization
│   ├── evaluation/            # LLM-as-judge
│   ├── colbert/               # Semantic retrieval
│   ├── ontology/              # Concept graph
│   ├── mcp-sheet-builder/     # MCP workflow generation
│   ├── langfuse/              # Observability
│   └── grain-test-utils/      # Test infrastructure
├── projects/
│   └── orc/                   # Publishable project (git dep target)
├── development/
│   └── src/dev.clj            # REPL entry point
└── docs/                      # Component guides and architecture
```

## Consumer Requirements

ORC is a library — consumers provide:

- **Grain infrastructure**: event store (in-memory or Postgres), LMDB cache, control plane
- **LLM provider**: DSCloj configuration (`:dscloj-provider` in context)
- **Optional**: Langfuse client for tracing, MCP servers for tool calling, Python for ColBERT

## Documentation

| Guide | Description |
|-------|-------------|
| [ORC Service Guide](docs/ORC-SERVICE-GUIDE.md) | Core execution engine and DSL reference |
| [DSL Tutorial](docs/dsl-tutorial.md) | Step-by-step workflow building tutorial |
| [**RLM Guide**](docs/RLM-GUIDE.md) | Research Language Model — two-phase execution, recursive `emit-tree!`, drill-down primitives, and the Phase 2 tree DSL |
| [Architecture](docs/ARCHITECTURE.md) | System architecture and design decisions |
| [GEPA Guide](docs/GEPA-GUIDE.md) | Prompt optimization with GEPA |
| [Evaluation](docs/EVALUATION-COMPONENT.md) | LLM-as-judge evaluation framework |
| [ColBERT Integration](docs/COLBERT-INTEGRATION.md) | Semantic retrieval setup |
| [Ontology](docs/ONTOLOGY.md) | Concept graph and pattern discovery |
| [MCP Sheet Builder](docs/MCP-SHEET-BUILDER-GUIDE.md) | Dynamic workflow generation |
| [Event Store Patterns](docs/EVENT-STORE-PATTERNS.md) | Grain event sourcing patterns |
| [Pattern Compendium](docs/pattern-compendium.md) | Complete pattern reference |

### Benchmarks

| Document | Description |
|---|---|
| [Bench README](development/bench/README.md) | How to run the 5-task generalization benchmark suite |
| [Bench RESULTS](development/bench/RESULTS.md) | Headline report — RLM designs 4 distinct tree patterns + 1 "no-tree" decision across structurally different tasks; zero hallucinations across 37+ spot-checks |

## License

MIT. See [LICENSE](LICENSE).
