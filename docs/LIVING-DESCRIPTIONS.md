# Living Descriptions — how ORC trees and nodes learn to describe themselves

> **A running explainer.** This doc grows alongside the Category C work. It explains, in layered detail, how ORC builds up self-knowledge about the workflows it runs — what each tree and each node is good at, what it tends to fail at, and how those descriptions evolve over time as the system observes more events.
>
> Audiences: non-technical stakeholders (Part 1), developers using ORC (Part 2), implementers maintaining the system (Part 3).
>
> **⚠ Alpha-stage capability.** The Living Descriptions system is
> functional end-to-end but the corpus + classifier interplay is still
> being investigated for out-of-distribution behavior. See the
> [Current capabilities and known limitations](SELF-IMPROVING-LOOP.md#current-capabilities-and-known-limitations)
> section of the consumer guide for what's solid today and what's
> rough.

## Part 1 — The 30-second story (for stakeholders)

ORC runs LLM-powered workflows as **behavior trees** — structured plans of LLM calls, code transforms, parallel branches, and so on. When the same kind of workflow runs many times, patterns emerge: certain tree shapes work well for certain tasks, certain nodes consistently produce good (or poor) results in certain contexts.

The **Living Descriptions** system captures those patterns automatically:

- Every tree and every node carries a self-description: *what it's good at, when it works well, when it tends to fail, and what to prefer or avoid in similar situations.*
- These descriptions are not written by humans (mostly). They're produced by an automated **reflection step** that periodically reads the recent execution events for that tree/node, compares them against history, and updates the description to reflect what was learned.
- A human-authored seed gets the system started (so it has some sensible starting descriptions on day one), but as workflows run, descriptions evolve from real evidence — successes climb in confidence; failures get principle-shaped lessons attached.
- When a developer designs a new workflow, the system can surface relevant prior descriptions as inspiration: *"a tree shaped like this has worked well before; here's what it tends to be good at."*

The result: ORC gets smarter over time without needing developers to babysit it.

## Part 2 — The developer mental model

If you're building or operating an ORC workflow, here's how to think about Living Descriptions:

### What a description looks like

Every entity — a node type (`:llm`, `:map-each`, etc.), a specific node in a specific workflow, or a tree shape (like "chunked-extraction") — has a description that looks roughly like this:

```clojure
{:capabilities ["chunks large documents", "extracts entities per chunk"]

 :strengths
 [{:trait "bounded :max-concurrency on :map-each over chunks"
   :good-when "input is a large chunked document"
   :recommended-pattern "[:map-each {... :max-concurrency 3} [:llm {...}]]"
   :confidence 0.92
   :evidence-count 45}]

 :weaknesses
 [{:trait "rate-limit exhaustion under unbounded parallelism"
   :avoid-when "input has >6 chunks AND :max-concurrency is unset"
   :recommended-alternative "set :max-concurrency to 3 on the :map-each"
   :confidence 0.6
   :evidence-count 8}]

 :representative-uses ["document-analysis benchmark", "legal-issue-detection"]
 :summary "When extracting from chunked documents, prefer bounded map-each concurrency."
 :version 4
 :consolidated-from-event-count 53}
```

Every strength and weakness is **principle-shaped**: a concrete trait, a context guard (when does it apply?), and actionable advice (a recommended pattern or alternative). Confidence weights the entry — high-confidence entries are surfaced to the model when designing new trees; low-confidence entries are tracked but hidden until evidence accumulates.

### Three granularities

| Granularity | What it groups | Example use |
|---|---|---|
| **node-type** | All instances of `:llm` (or `:map-each`, etc.) across all workflows | *"In general, what do `:map-each` nodes tend to do well?"* |
| **node-instance** | One specific node in one specific workflow | *"The 'validator' LLM in my report-generation workflow has been reliable in the last 50 runs."* |
| **tree-fingerprint** | Trees with the same structural shape (regardless of content) | *"Chunked-extraction trees with a final synthesizer step work well for medium-large docs."* |

### How descriptions evolve

Descriptions update through three signals:

1. **Hand-authored seeds** — at the start, an initial catalog of ~18 descriptions captures known patterns (the 6 basic node types + the 5 benchmark task classes + 7 generic tree shapes). These are reviewed for quality and ground every claim in real benchmark evidence.
2. **Cold-start LLM baseline** — when a brand-new entity appears (a new node instance, an unfamiliar tree shape), the system asks an LLM to write a low-confidence initial description based on the entity's structural shape. Marked at confidence 0.1 so it's invisible to retrieval until real evidence reinforces it.
3. **Rolling consolidation** — as the system observes execution events (successes, failures, and judge scores from the per-event evaluator runtime — see "Judge integration" below), a periodic reflection step updates the description. Stable patterns climb in confidence; anomalies appear as low-confidence-but-actionable entries; outdated entries decay and eventually auto-archive.

### What protects descriptions from over-reacting to recent runs

Four safeguards keep descriptions stable over time:

1. **Decoupled threshold and window.** Consolidation triggers when ~10 new events accumulate, but each consolidation reads the last 500 events — so a single bad burst doesn't reshape the description.
2. **Aggregate + delta in the prompt.** The reflection LLM sees all-time aggregate metrics alongside the recent window's slice — and is told explicitly: "update only when recent evidence is consistent AND substantial."
3. **Per-entry confidence with demotion-not-deletion.** New consistent evidence climbs an entry's confidence; contradicting evidence reduces it proportionally. Confidence below 0.2 hides the entry from retrieval but keeps it in the description; below 0.05, the entry archives to history.
4. **Anomalies are principle-shaped, not flags.** When recent metrics deviate from historical, the new entry is a low-confidence-but-actionable principle (concrete avoid-when + concrete recommended-alternative). Never "investigate" or "observed" placeholders.

### How developers use this

Most developers won't interact with the system directly — they just write their workflows normally, and the system uses recorded descriptions in two ways:

1. **Pattern injection** — when a repl-researcher runs with `:auto-classify? true` on its `:rlm` config, the top-fitting pattern's full body (capabilities + worked-example DSL snippets + observed strengths and weaknesses) is prepended to the model's instruction before it starts designing the tree.

2. **Cross-task behavioral retrieval** — when the model designs a new tree, behavioral subtrees that match the task's accomplishment shape are surfaced as candidates. The model can adopt, adapt, or — if nothing fits — contribute a new behavior via the `(mint-behavior! ...)` sandbox primitive. Minted behaviors persist for future retrieval across all tasks.

For developers who want explicit control:

- Read the current description for any tree-class, behavioral subtree, node-instance, or node-type via `ontology/get-description`.
- Inspect a description's history (`ontology/get-description-history`) to see how the body evolved across consolidation cycles.
- Tune the consolidation threshold per granularity via `:ontology/set-consolidation-threshold` (default 10 events accumulated per target before a reflection cycle fires).
- See [`SELF-IMPROVING-LOOP.md`](SELF-IMPROVING-LOOP.md) for the end-to-end consumer guide with example workflows.

## Part 3 — Implementer's view (architecture + status)

### Architecture

```
EVENT STREAM (existing) — :sheet/node-execution-completed,
  :sheet/rlm-tree-execution-completed, :judge/score-emitted (when judges land)
        ↓
ROLLING AGGREGATOR (extends existing rolling-metrics)
  per-node-instance | per-node-type (cross-sheet) | per-tree-fingerprint
        ↓
CONSOLIDATION TRIGGER (new Grain processor)
  threshold (default 10) OR on-demand
        ↓
CONSOLIDATOR (new todo processor)
  Pulls window=500 recent events + accumulated metrics + structural context.
  Runs a single LLM reflection with structured Malli output.
  Emits the appropriate :ontology/*-description-updated event.
        ↓
ONTOLOGY DESCRIPTION READ MODEL
  Projects events into "current description" + "history" per (granularity, target-id).
        ↓
COLBERT RE-INDEX (C-2b)
  Async; re-indexes the :summary into ColBERT on every *-description-updated.
        ↓
SEMANTIC RETRIEVAL
  Model queries via natural language → ColBERT search → RRF rank-fusion across granularities.
```

### Judge integration (added 2026-06)

The consolidator's reflection input was extended to include judge scores alongside raw execution events. Verified live on `legal-issue-detection` multi-cycle:

- **Per-event evaluator runtime** (`components/evaluation/src/.../core/judge_runtime.clj`) subscribes to `:sheet/node-execution-completed` and fires attached judges (default + custom) in parallel via futures with a 60s per-judge timeout. When the Living Description opt-in flag is on, repl-researcher nodes get 5 default judges auto-attached (heuristic-structural + grounding + reasoning + completeness + instruction-following).
- **Score events** (`:judge/score-emitted`) land in the event store tagged with `[:sheet :tick :node]`.
- **Consolidator joins them** via `gather-recent-tree-class-events`: per-observation `:judge-scores` from the recent window + `:judge-averages` per judge across the target's lifetime in `:aggregate-metrics`.
- **Reflection instruction explains the data**: tells the LLM that `:judge-averages` is the stable baseline and to weight per-tick judge divergence with the same anti-recency discipline as success-rate deltas.
- **Custom judges** plug into the same pipeline via `:type :custom` + `:sheet-id` referencing a consumer-built eval workflow. See [`RLM-GUIDE.md`](RLM-GUIDE.md#attaching-judges-to-your-behavior-trees).

Composite scoring (shipped 2026-06): when 2+ judges fire on the same (sheet, node, tick), the runtime emits a `:judge/composite-score-computed` event with the weighted composite. Default policy: even-weight (1/N) when consumers don't set explicit weights; consumer-set `:judge-config :weight` values normalize to sum to 1.0. The per-judge `:judge-averages` map stays alongside in the consolidator's reflection input — consumers wanting the independent per-judge signal aren't disrupted.

### Capabilities surface

The following capabilities are part of the shipped self-improving loop:

| Capability | What it provides |
|---|---|
| **Hand-authored seed corpus** | Initial body for tree-classes (structural patterns) and behavioral subtrees (accomplishment patterns), shipped with confidence-weighted strengths and weaknesses. |
| **Cold-start description generation** | When a brand-new entity is observed, the system produces a low-confidence initial description so it's tracked from event 1 — invisible to retrieval until evidence accumulates. |
| **Rolling consolidation** | A periodic reflection step reads recent execution events alongside accumulated metrics and emits an updated description. Anti-recency safeguards prevent single-bad-burst overcorrection. |
| **Per-event judges** | Default judges (heuristic-structural, grounding, reasoning, completeness, instruction-following) attach to repl-researcher nodes when the opt-in flag is on. Scores feed the consolidator alongside raw success/failure signal. |
| **Custom judges** | Consumers plug in their own per-task quality criteria as `:type :custom` judges referencing a sheet-id of their own evaluation workflow. |
| **Composite scoring** | When multiple judges fire on the same (sheet, node, tick), a weighted composite is emitted alongside the per-judge scores. Default is even-weight; consumers can set explicit weights that normalize to 1.0. |
| **Behavioral mint affordance** | The recursive RLM sandbox primitive `(mint-behavior! ...)` lets the model contribute a new pattern when none of the existing corpus entries fits at meaningful confidence. The minted behavior persists in the corpus and is retrievable for future tasks. |
| **Classifier API + R-Inject prepend** | `:auto-classify? true` on a repl-researcher's `:rlm` config classifies the task against the corpus and prepends the top-fitting pattern's body to the model's instruction. |
| **Recursive RLM with drill-down** | The model can design a tree, run it, inspect via `(tree-detail)` / `(tree-failures)` / `(node-output ...)`, and emit focused recovery trees when leaves fail — without rebuilding the whole pipeline. |
| **Description-history audit trail** | Every body update is an append-only event; `ontology/get-description-history` returns the chronological sequence of every version with timestamps. |

### Where this fits in the broader ORC architecture

- **Consumer entry point:** [`SELF-IMPROVING-LOOP.md`](SELF-IMPROVING-LOOP.md) — practical guide for developers using the loop in their workflows
- **Recursive RLM reference:** [`RLM-GUIDE.md`](RLM-GUIDE.md) — tree DSL, sandbox primitives, drill-down, sub-LLM costs
- **Storage and event surface:** [`SELF-LEARNING-MANUAL.md`](SELF-LEARNING-MANUAL.md) — events emitted, read-model projections, query API
- **Continuous-improvement framing:** [`FEEDBACK-LOOP.md`](FEEDBACK-LOOP.md) — the larger cycle ORC fits into
- **Building behavior trees:** [`ORC-SERVICE-GUIDE.md`](ORC-SERVICE-GUIDE.md) — node types, blackboard schemas, composition
- **Pattern library:** [`pattern-compendium.md`](pattern-compendium.md) — common tree shapes worked-out
