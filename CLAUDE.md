# CLAUDE.md

## Project Overview

ORC (Orchestrator) is a behavior-tree-based workflow execution engine built on the Grain event-sourcing framework. It provides composable primitives for building, optimizing, and evaluating LLM-powered workflows.

**Top namespace**: `ai.obney.orc`

This is a **library** — no web layer, no auth, no database config. Consumers pull it in as a git dep and provide their own Grain infrastructure (event store, cache, web server).

> **Read first:** [`docs/ORC-PRINCIPLES.md`](docs/ORC-PRINCIPLES.md) — the durable, framework-level principles for thinking about and building with ORC (the node palette, composition via `:delegate`, events-first discipline, fitness-gate-as-objective, adversarial verification). Read it before designing or verifying a workflow.

## Components

| Component | Purpose |
|-----------|---------|
| **orc-service** | Core behavior tree execution engine, DSL for workflow building, event-sourced state |
| **gepa** | LLM instruction optimization with Pareto frontier selection |
| **evaluation** | LLM-as-judge evaluation with grounding, reasoning, completeness judges |
| **colbert** | Late-interaction retrieval via Python ColBERT bridge |
| **ontology** | Three-layer concept graph with embeddings and pattern discovery; also a general-purpose evolutionary ontology extractor for JSON / CSV / SQL sources |
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
# Start nREPL
clj -M:dev -m nrepl.cmdline --port 7888
```

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

## RLM Generalization Benchmark

The repo includes an end-to-end benchmark suite that demonstrates ORC RLM (`emit-tree!`) genuinely adapts behavior tree design to task type:

- Entry point: [`development/bench/README.md`](development/bench/README.md)
- Headline report: [`development/bench/RESULTS.md`](development/bench/RESULTS.md)
- Runner: `development/bench/runner.clj`
- Aggregator: `development/bench/all.clj`
- Per-task examples: `development/bench/{document_analysis,risk_analysis,contract_comparison,contract_comparison_validated,legal_issue_detection}.clj`

5 task types tested with goal-only instructions (no hardcoded trees) on real documents. The model designed 4 distinct tree patterns + 1 "no tree" decision, with zero hallucinations across spot-checks. See the headline report for the story.

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
# Run a single task
clj -M:dev -e '(require (quote [risk-analysis :as t])) (require (quote [runner])) (runner/start!) (runner/run! t/task) (runner/stop!)'
# Or run the full 5-task suite
clj -M:dev -e '(require (quote [all :as bench])) (bench/start!) (bench/run-all!) (bench/summary!) (bench/stop!)'
```

## RLM Mode (repl-researcher node)

The `:repl-researcher` node type provides a two-phase execution pattern. See [`docs/RLM-GUIDE.md`](docs/RLM-GUIDE.md) for the comprehensive guide — including the recursive-mode opt-in, sandbox primitives, drill-down primitives (`tree-detail`, `tree-trajectory`, `tree-failures`, `node-output`, `node-input-profile`), the Phase 2 tree DSL (including `:code` for model-authored transforms), observability events, partial-result handling, budget controls, and how to compose `:repl-researcher` as a node inside a larger behavior tree.

**Two modes**, controlled by the `:rlm` config on the node:

```clojure
;; Terminal mode (default) — model designs ONE tree, Phase 2 runs, result returns
{:rlm true}                          ; or {:rlm {:debug? true}}

;; Recursive mode (opt-in) — emit-tree! returns to Phase 1; model can inspect and continue
{:rlm {:recursive? true}}            ; or {:rlm {:recursive? true :debug? true}}
```

In recursive mode, after Phase 2 completes (any status), the tree's outputs are merged into sandbox-vars, a lightweight summary entry is appended to `:tree-results`, and control returns to the Phase 1 loop. The model can run follow-up `(llm ...)` / `(code ...)`, drill into prior trees via `(tree-detail)` / `(tree-failures)` / etc. when the summary isn't enough, emit another tree, or call `(final! ...)` to terminate. Existing callers using `:rlm true` are unaffected — terminal behavior is preserved.

## Live Streaming

Executions can be observed live via an ephemeral, in-process stream — node lifecycle, progress, incremental node results, RLM phase activity — without changing the durable event-sourced model. See [`docs/STREAMING.md`](docs/STREAMING.md).

```clojure
(let [{:keys [events-ch result]} (orc/execute-stream ctx sheet-id inputs)]
  ...)                                   ;; or orc/subscribe-execution + own dispatch
(orc/cancel! ctx tick-id)                ;; best-effort cancellation, cascades to child ticks
```

Hub: `orc-service/core/streaming.clj`. Envelope schemas: `interface/stream_schemas.clj` (`:orc.stream/type`-dispatched, monotonic `:seq`, sliding-buffer loss model with event-store reconciliation). Tests: `streaming_test.clj`. Live demo consumer: `development/src/streaming_live_verify.clj`.

## Self-Improving Loop

> **Alpha-stage.** The components below all work end-to-end. The aggregate behavior on workflows that align with the shipped seed corpus is reliable; the behavior on workflows far outside the corpus shows force-fit classifications (high-confidence shape-matches that miss domain semantics) and rare mint events. See `docs/SELF-IMPROVING-LOOP.md#current-capabilities-and-known-limitations` for an honest current-state breakdown grounded in a 21-task OOD evidence sweep. Active investigation in `development/bench/ood-stress-results/HANDOFF.md`.

Workflows that opt into `:rlm {:auto-classify? true :recursive? true}` participate in a self-improving loop:

- **Pattern injection at design time** — before the model designs its tree, the top-fitting pattern from the seed corpus (including capabilities, observed strengths with worked-example DSL snippets, weaknesses with recommended fixes) is prepended to the model's instruction.
- **Body evolution from execution evidence** — after enough tasks classify to the same pattern, a reflection step updates the pattern body. New strengths and traits emerge from observed runs; evidence-counts climb on existing entries. Anti-recency safeguards prevent single-bad-burst overcorrection.
- **Behavioral mints** — when the model encounters work no existing pattern fits, it can contribute a new behavior via `(mint-behavior! ...)`. Minted behaviors persist for future retrieval across all consumers and surface in classify-behaviors at high confidence when behavioral shape matches (even across domains).
- **Focused failure recovery** — when a leaf in an emitted tree throws, the next iteration's `:tree-results` summary surfaces `:failed-leaves` with the node-id + error. The model typically emits a single-node recovery tree reading surviving sandbox-vars rather than rebuilding the pipeline.

External-consumer entry point: [`docs/SELF-IMPROVING-LOOP.md`](docs/SELF-IMPROVING-LOOP.md). Architecture detail: [`docs/LIVING-DESCRIPTIONS.md`](docs/LIVING-DESCRIPTIONS.md). Recursive RLM reference: [`docs/RLM-GUIDE.md`](docs/RLM-GUIDE.md).

## Skills

### ORC Domain
- `/orc-workflow` — Build a behavior tree workflow using the DSL
- `/orc-optimize` — Set up GEPA prompt optimization for a workflow
- `/orc-evaluate` — Set up LLM-as-judge evaluation

### Grain Framework
- `/grain-command-handler`, `/grain-read-model`, `/grain-query-handler`, `/grain-todo-processor`, `/grain-schema`, `/grain-service` — Framework patterns for building new features
