# ORC Component Map

> **Which ORC components do I need?**
>
> ORC is a layered opt-in library. Most consumers only need the core execution engine
> (`orc-service`). Add components as capabilities require. This document maps each
> capability to its components, flags Python requirements, and calls out known issues
> you will hit before the code does.

---

## Opt-in layer table

| Layer | Capability | Component(s) needed | Python required | Evidence |
|-------|-----------|---------------------|:---------------:|---------|
| 0 | **Core execution** — behavior tree DSL, workflow execution, event-sourced state | `orc-service` | No | `components/orc-service/deps.edn`: an LLM-call layer, structured logging, and a safe Clojure interpreter — no DJL, no Python bridge |
| 1 | **LLM judges** — grounding, reasoning, completeness, instruction-following | `evaluation` | No | `evaluation/deps.edn`: JSON handling, the LLM-call layer, and orc-service — **no ontology**. The Living-Description gate in `judge_runtime.clj` is resolved lazily (`requiring-resolve`), so judges run with zero ontology, zero DJL, zero Python. Verified: `projects/orc-evaluation` resolves with none of those on the classpath. |
| 2 | **Observability** — Langfuse trace forwarding | `langfuse` | No | `components/langfuse/deps.edn`: empty deps map — no external deps at all |
| 3 | **Prompt optimization** — GEPA Pareto-frontier instruction evolution | `gepa` + `evaluation` | No | `components/gepa/deps.edn`: deps are mulog, orc/evaluation — no ontology dep, no Python |
| 4 | **DJL embeddings** — dense vector embeddings, semantic concept search | `ontology` | No | `components/ontology/deps.edn`: deps are ai.djl/api, ai.djl.pytorch/pytorch-engine (no Python); `embedding.clj:53` calls `(Criteria/builder)` from DJL directly — pure JVM model loading |
| 5 | **ColBERT retrieval** — 110M-param late-interaction scoring, PLAID indexing | `colbert` | **Yes** | `bridge.clj:2-14`: "Python subprocess bridge for ColBERT operations. Protocol: JSON-RPC over stdin/stdout" |
| 6 | **Evolutionary ontology builder** — ingest CSV/JSON/SQL/text, auto-discover concepts, event-sourced general-purpose memory | `ontology` + ORC sheets for extraction | No | `ontology/interface/evolutionary.clj`: `build-from-sources` + `evolve` entry points; ColBERT indexing is an optional phase-7 within the pipeline |
| 7 | **Self-improving loop** — auto-classify executions, pattern evolution, behavior minting | `orc-service` + `evaluation` + `ontology` + `colbert` | **Yes** (via colbert) | `judge_runtime.clj:559`: `(ontology/get-living-description-enabled? ctx)` is the single boolean gate that controls the entire loop; disabled by default |
| 8 | **MCP Sheet Builder** — dynamic workflow generation from MCP tool schemas | `mcp-sheet-builder` | No | `components/mcp-sheet-builder/deps.edn`: deps are clj-http, cheshire, sci — no ontology dep, no Python |

---

## Pull in only what you need

ORC is published as standalone packages from a single Polylith repo — pull in
only the package your layer needs, instead of the full umbrella. Full details
and deps.edn snippets: **[PACKAGES.md](PACKAGES.md)**.

| Package | Layers | DJL? | Python? |
|---------|--------|:----:|:-------:|
| `projects/orc-service` | 0 (engine) | No | No |
| `projects/orc-evaluation` | 0–2 (+ judges) | No | No |
| `projects/orc-gepa` | 0–3 (+ optimization) | No | No |
| `projects/orc-ontology` | 0, 4, 6 (+ memory) | Yes | No |
| `projects/orc-colbert` | 5 (retrieval upgrade) | No | Yes |
| `projects/orc-mcp-sheet-builder` | 8 | No | No |
| `projects/orc` (umbrella) | all (full self-improving loop) | Yes | Yes |

Example — judges with zero Python and zero DJL:

```clojure
;; in your project's deps.edn — lib name matches the package
obneyai/orc-evaluation {:git/url "https://github.com/ObneyAI/orc.git"
                        :git/sha "<sha>"
                        :deps/root "projects/orc-evaluation"}
```

Every non-leaf package bundles the engine (`orc-service` + its `langfuse`
tracing layer) transitively — you never pull the engine separately unless you
want *only* behavior-tree execution (`projects/orc-service`). The
self-improving loop is not a separate package: it is the capability you get when
`ontology` + `colbert` + `evaluation` run together on the engine (the umbrella,
or those packages combined).

---

## Component dependency graph

Each component's `deps.edn` declares its real hard dependencies (self-describing,
verified by `clojure -Spath` on each package). Lazy edges resolved via
`requiring-resolve`/`find-ns` are NOT hard deps — they degrade gracefully when
the target component is absent.

### Leaf components (no hard ORC-component deps)

```
langfuse          deps: (none — empty deps map)
colbert           deps: mulog, data.json  [+Python subprocess at runtime]
                  (its ontology reference is lazy — fn-body require, not a hard dep)
```

### Hard-dependency edges (declared in deps.edn)

```
orc-service        →  langfuse                      (tracing layer)
mcp-sheet-builder  →  orc-service → langfuse
evaluation         →  orc-service → langfuse         (+ lazy ontology gate)
gepa               →  evaluation → orc-service
ontology           →  orc-service → langfuse         (+ lazy colbert)  [+ DJL, in-JVM]
```

The graph is acyclic. The two lazy edges (`evaluation → ontology` gate,
`ontology → colbert` retrieval) are deliberately lazy so judges, GEPA, and the
ontology each build without dragging in heavier layers.

### Full umbrella project

```
projects/orc  →  orc-service
                 evaluation   → orc-service
                 gepa         → evaluation
                 ontology
                 colbert      [+Python]
                 mcp-sheet-builder
                 langfuse
```

### When is Python required?

Python is only required when you add the `colbert` component. Every other component
runs entirely on the JVM. The `ontology` component uses DJL to load embedding models
locally over PyTorch — this is JVM-managed; no Python process is spawned.

---

## Known issues

### BFS scoping — fixed in feature branch, not yet on main

**Source (main):** `components/ontology/src/ai/obney/orc/ontology/core/retrieval.clj:116`

On the current `main` branch, `expand-concept-neighborhood` has no `:ontology-id`
parameter and walks all concepts regardless of ontology boundary. Embedding + ColBERT
searches are correctly scoped, but BFS is not.

**Fix landed:** `feature/ontology-architecture` (branch at `/Users/darylroberts/Desktop/Code/orc`)
has the S02 fix fully implemented. `expand-concept-neighborhood` now accepts
`:ontology-id`/`:ontology-ids` and builds a scoped concept graph accordingly.
Back-compat preserved: callers without `:ontology-id` behave identically to before.

```clojure
;; Feature branch signature (S02 fix):
(defn expand-concept-neighborhood
  [seed-uris & {:keys [max-depth decay graph ctx ontology-id ontology-ids ...]}]
  ...)
```

**Status on main:** Use `hybrid-search` with explicit `:signals #{:embedding :colbert}` if
you need reliable ontology-scoped results today. The BFS signal will cross ontology
boundaries until the feature branch merges.

---

### Pluggable judge scales — built-in judges sealed

**Source:** `components/evaluation/src/ai/obney/orc/evaluation/core/scale.clj:30-60`

All four built-in LLM judges (grounding, reasoning, completeness, instruction-following)
use hard-coded discrete 1–5 bands defined as `discrete-scale` calls inside the evaluation
component. The `scale.clj` `discrete-scale` constructor is decoupled from the criteria
(per ADR 0011, PA-3) but built-in judge functions do not accept a caller-supplied scale at
invocation time. Changing the scoring bands requires writing a `:custom` judge (a consumer-
owned ORC workflow sub-executed via `judge_runtime.clj:invoke-custom-judge`).

**Coming soon:** plug your own band definitions into the existing built-in judges without
writing a full custom judge sheet.

---

### `tree-fingerprint` is structural — instruction variants collapse

**Source:** `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_fingerprint.clj:30-50`

The fingerprint hash normalizes the tree before hashing, replacing every `:instruction`
string with the canonical placeholder `:fingerprint/instruction` and every `:fn` body with
`:fingerprint/fn`:

```clojure
(def instruction-placeholder
  "Instructions are content; the fingerprint is keyed on structure."
  :fingerprint/instruction)
```

As a result, two `:repl-researcher` nodes that share the same tree shape but carry
completely different `:instruction` content hash to the **same fingerprint**. Consequences:

- LLM instruction A/B experiments within one tree shape collapse into a single fingerprint
  bucket in rolling-metrics aggregation.
- Domain-specific specialist emergence — grouping trees by *what they do*, not just *how
  they're shaped* — requires a separate `:tree-class` axis that is **not yet auto-assigned**.

---

## Further reading

- [`PACKAGES.md`](PACKAGES.md) — the standalone packages and their deps.edn snippets (pull in only what you need)
- [`GETTING-STARTED.md`](GETTING-STARTED.md) — installation, first workflow, common patterns
- [`JUDGE-ARCHITECTURE.md`](JUDGE-ARCHITECTURE.md) — judge types, custom judges, scale design, composite scoring
- [`ORC-PRINCIPLES.md`](ORC-PRINCIPLES.md) — durable framework-level principles: node palette, `:delegate` composition, events-first discipline, fitness gate as objective
