# CLAUDE.md

## Project Overview

ORC (Orchestrator) is a behavior-tree-based workflow execution engine built on the Grain event-sourcing framework. It provides composable primitives for building, optimizing, and evaluating LLM-powered workflows.

**Top namespace**: `ai.obney.orc`

This is a **library** — no web layer, no auth, no database config. Consumers pull it in as a git dep and provide their own Grain infrastructure (event store, cache, web server).

## Components

| Component | Purpose |
|-----------|---------|
| **orc-service** | Core behavior tree execution engine, DSL for workflow building, event-sourced state |
| **gepa** | LLM instruction optimization with Pareto frontier selection |
| **evaluation** | LLM-as-judge evaluation with grounding, reasoning, completeness judges |
| **colbert** | Late-interaction retrieval via Python ColBERT bridge |
| **ontology** | Three-layer concept graph with embeddings and pattern discovery |
| **mcp-sheet-builder** | Dynamic workflow generation from MCP tool schemas |

## Architecture

Built on **Grain v2** (event sourcing + CQRS):
- `defcommand` — validate and emit events
- `defreadmodel` — project events into queryable state
- `defquery` — compose read models, return data
- `defprocessor` — event-driven side effects (auto-registered)
- `defperiodic` — scheduled trigger events (auto-registered)

## Development Setup

```bash
# Start nREPL — keep this running for the whole session
clj -M:dev -m nrepl.cmdline --port 7888
```

### RDD workflow (preferred for orc development)

orc is built around an embedded REPL — `(dev/start!)` returns a fully-warm
system map (in-memory event store, LMDB cache, todo processors,
doc-skills tools, DSCloj providers). Connect to nREPL once and do all
exploratory / debugging work interactively rather than restarting the JVM
per change.

- Connect via the `/nrepl-connect` skill (uses the `nrepl` MCP server
  declared in `.mcp.json`). Tries port 7888, falls back to `.nrepl-port`.
- Then in your session: `(require '[dev]) (def sys (dev/start!)) (def ctx (dev/ctx))`
- Single-shot driver experiments without restarting:
  ```clojure
  (require '[bench.run-orc :as bench]
           '[ai.obney.orc.workflow-driver.interface :as driver])
  (def deg (get @bench/degradations-cache :invoice_processing))
  ;; build sheet, run one driver loop, inspect result by handle
  ```
- `(tap> v)` ships any value to Portal/Reveal/CIDER inspector — drill
  into eval reports, proposal forms, event traces without re-running.
- Edit a function (e.g. `summarize-eval-failure` in workflow-driver),
  eval the buffer, run ONE more turn against the same sheet-id. No JVM
  restart, caches stay warm.

The `clj -X:bench run-orc/run` batch entry point still exists — use it
for clean reproducible sweeps that produce numbers for REPORT.md. Use
RDD for everything upstream of that.

## Running Tests

```bash
clj -M:poly test                    # changed bricks only
clj -M:poly test :all-bricks        # all bricks
clj -M:poly test brick:orc-service  # specific brick
```

## Consumer Usage

Add to your project's `deps.edn`:

```clojure
obneyai/orc {:git/url "https://github.com/ObneyAI/orc.git"
             :git/sha "..."
             :deps/root "projects/orc"}
```

Then require components:

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])
(require '[ai.obney.orc.gepa.interface :as gepa])
```

## Polylith Structure

```
components/{service}/
├── src/ai/obney/orc/{service}/
│   ├── interface.clj              # Public API
│   ├── interface/schemas.clj      # Malli schemas
│   └── core/
│       ├── commands.clj           # defcommand handlers
│       ├── read_models.clj        # defreadmodel projections
│       ├── queries.clj            # defquery handlers
│       └── todo_processors.clj    # defprocessor side effects
└── test/ai/obney/orc/{service}/
```

## Skills

### ORC Domain
- `/orc-workflow` — Build a behavior tree workflow using the DSL
- `/orc-optimize` — Set up GEPA prompt optimization for a workflow
- `/orc-evaluate` — Set up LLM-as-judge evaluation

### Grain Framework
- `/grain-command-handler`, `/grain-read-model`, `/grain-query-handler`, `/grain-todo-processor`, `/grain-schema`, `/grain-service` — Framework patterns for building new features
