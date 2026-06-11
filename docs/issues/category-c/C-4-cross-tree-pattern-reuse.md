# C-4: Cross-tree pattern reuse + node-type learning

## Parent

PRD: [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md) — see "Out of Scope" → "C-4 sub-grill must decide" + the C-4 row in "Proposed Slicing."

## Status: STUB — sub-grill required

This issue is a placeholder. Architectural decisions are open and must be resolved by a `/grill-me` sub-grill before implementation can begin. C-4 is HITL — it requires both C-1 and C-3 to be done so the implementation has both a validated principle shape and an automated extraction layer to build on.

## What to build (sketch)

Broaden the principle reuse beyond per-task-class to include:

### 1. Node-type-level principles (FUTURE-VISION 4a.6)

Patterns shared across all nodes of the same type — e.g., "all `:map-each` nodes benefit from bounded `:max-concurrency` when concurrency > 5 + sub-LLM has rate limits" applies regardless of which task class is using the `:map-each`. Storage shape, retrieval, and injection composition need design.

### 2. LLM-discovered failure subtypes (FUTURE-VISION 4a.10)

Use LLM-based pattern discovery to identify *new* failure subtypes not in the static core ontology. The static core has things like `failure:Hallucination`, `failure:InstructionViolation`, `failure:ReasoningDefect`. LLM Pattern Discovery extends this with subtypes the system observes empirically (e.g., `failure:RateLimitExhaustion` as a new subtype of `failure:ResourceConstraint`).

### 3. Ontology-wide retrieval composition

When multiple signals match a new task — exact task-class match (from C-1+C-2) + node-type match (from C-4) + semantic similarity to past tree (from C-4 embedding lookup) — the retrieval layer must compose them. Today the executor's `:principles-fn` only does task-class lookup. C-4 broadens it to combined retrieval.

The RRF (Reciprocal Rank Fusion) + graph traversal infrastructure already exists in the ontology component (per FUTURE-VISION's Phase 4a notes about existing `graph_search.clj`). C-4 wires that to RLM principles.

## Open decisions (require sub-grill)

The PRD's "Open decisions deferred to sub-grills" section enumerates:

1. **Node-type-level principle storage:**
   - Where do node-type-level principles live? A separate `node-experiences` read-model section, or merged into the same tree-profile shape with a node-type tag?
   - Schema: how is a node-type-level principle keyed — just by node type, or by `(node-type, task-class)` tuples?

2. **Retrieval composition when multiple sources match:**
   - When task-class match + node-type match + embedding-similar past tree all return principles, how are they ranked/limited?
   - RRF score combination — same algorithm the ontology uses elsewhere?
   - Hard cap on number of injected principles to bound prompt size?

3. **Anti-pattern surfacing:**
   - Do anti-patterns from `:failure` traces get surfaced separately from `:weakness`-recorded ones?
   - Format: "DON'T do X" as its own prompt section, or interleaved with the strengths/weaknesses?

4. **LLM Pattern Discovery cadence:**
   - When does the discovery workflow run? Periodic batch, threshold-triggered, on-demand?
   - What's the prompt structure that asks an LLM to identify a new failure subtype?
   - How is a newly-discovered subtype validated before being added to the ontology (HITL gate, statistical threshold)?

5. **Embedding strategy for similar-task retrieval:**
   - What gets embedded — the task's instruction text, the `:reads`/`:writes` signature, the input-profile summary?
   - Existing ColBERT integration (FUTURE-VISION Theme 9, predict-rlm PR04 work) — reuse for principle retrieval, or use ada-style embeddings?

6. **Live-verify scenario for C-4:**
   - Does node-type-level principle injection move model behavior for a TASK CLASS that doesn't have any task-class-specific principles? (the "cold start, but node-type knowledge helps" case)
   - Does embedding-based retrieval surface a useful principle from a *semantically similar but different* task class?

## Acceptance criteria (high-level — refined in sub-grill)

The sub-grill must produce concrete acceptance criteria. Sketch:

- [ ] Node-type-level principle storage + retrieval works; principles applicable to a node type are surfaced for any task class using that node type.
- [ ] Composition rules documented and implemented: when N retrieval sources match, prompt-injected principles are ranked, capped, and deduplicated coherently.
- [ ] LLM Pattern Discovery workflow runs (cadence per sub-grill decision); discovers at least one new failure subtype on real benchmark data; subtype is validated before adding to the ontology.
- [ ] Embedding-based similar-task retrieval works; returns relevant principles from semantically-similar prior tasks.
- [ ] Live-verify scenarios pass for both cold-start cases (no task-class principles but node-type principles available) and semantic-similarity cases.
- [ ] No regression in C-1's or C-3's live-verifies — broader retrieval still produces actionable signal.

## Critical project rules

1. **Infrastructure existence is not quality** — the RRF + embeddings + graph traversal infrastructure exists, but reusing it for RLM principle retrieval needs adversarial audit. Does it actually retrieve relevant principles, or just lexically similar ones?
2. **Mocks for dev iteration only; live runs required before declaring done.**
3. **Trace bugs to root cause.**
4. **Never truncate model-authored output back to the model.** When composing N principles, render each in full or filter the list — don't show clipped principles.

## Blocked by

- [C-1 — Principle storage + retrieval + injection](C-1-principle-storage-retrieval-injection.md)
- [C-3 — Automated principle extraction + judges audit](C-3-automated-extraction-judges-audit.md)

C-1 validates the shape. C-3 supplies the automated principles that populate the broader retrieval. Without both, C-4 is composing retrieval over too little data to validate it.

## Cross-references

- Parent PRD: [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md)
- FUTURE-VISION 4a.6 (Node-Type Learning): [`docs/FUTURE-VISION.md`](../../FUTURE-VISION.md)
- FUTURE-VISION 4a.10 (LLM Pattern Discovery): [`docs/FUTURE-VISION.md`](../../FUTURE-VISION.md)
- FUTURE-VISION Theme 9 (Enhanced Retrieval with ColBERT): [`docs/FUTURE-VISION.md`](../../FUTURE-VISION.md)
- Existing ontology retrieval scaffolding: `components/ontology/src/.../core/retrieval.clj`, `components/ontology/src/.../core/discovery.clj`, `components/ontology/src/.../core/graph.clj`
- ColBERT integration (relevant for embedding-based retrieval): `components/colbert/`
