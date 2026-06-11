# Category C — Self-Improving Loop

**Parent PRDs:**
- [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md) — Category C overview
- [`docs/prd/category-c-2-living-descriptions.md`](../../prd/category-c-2-living-descriptions.md) — C-2 (Living Descriptions) deep PRD

**Status:** Local issue tracking — these files live in the repo on `feature/core-orc-upgrades`.
- **C-1 — SHIPPED** (live-verified; `:context` plumbing + `format-rich-pattern` extension + 1 hand-authored principle proven load-bearing in a 3-way comparison).
- **C-2a — SHIPPED** (Living Descriptions foundation; 22 seeds, consolidator with anti-recency safeguards, live-verify orchestrator, all real-LLM verified; committed to `feature/core-orc-upgrades`).
- **C-2b — Architecturally resolved** (2026-05-26 sub-grill); broken into C-2b-1 / C-2b-2 / C-2b-3.
- **C-2c — Architecturally resolved** (2026-05-27 sub-grill); broken into C-2c-1 / C-2c-2 / C-2c-3. **C-2c-3 deferred until C-2d-3 lands** (fresh-mint needs hierarchy).
- **C-2d — Architecturally resolved** (2026-05-27 sub-grill); hierarchical tree-classes via SKOS broader/narrower. Broken into C-2d-1 / C-2d-2 / C-2d-3. Unblocks C-2c-3.
- **C-3** and **C-4** remain HITL stubs awaiting their own sub-grills.

## Issue List

| # | Title | Type | Status | Blocked by |
|---|-------|------|--------|------------|
| [C-1](C-1-principle-storage-retrieval-injection.md) | Principle storage + retrieval + injection | AFK | **SHIPPED** | — |
| [C-2](C-2-task-class-classification.md) | Living Descriptions (parent/index) | — | Expanded into sub-issues | C-1 |
| └ [C-2a-1](C-2a-1-static-descriptions-and-seeds.md) | Static description event types + read model + 22 hand-authored seeds | AFK | **SHIPPED** | None |
| └ [C-2a-2](C-2a-2-rolling-aggregator-extension.md) | Rolling aggregator extension (cross-sheet + tree-fingerprint) | AFK | **SHIPPED** | None |
| └ [C-2a-3](C-2a-3-consolidator-and-reflection.md) | Consolidator + LLM reflection + anti-recency safeguards (3a/3b/3c) | AFK (HITL audit) | **SHIPPED** | C-2a-1 + C-2a-2 |
| └ [C-2a-4](C-2a-4-live-verify.md) | Live-verify orchestrator (3-run regression + seed-executability) | AFK | **SHIPPED** | C-2a-1 + C-2a-2 + C-2a-3 |
| └ [C-2b](C-2b-multi-granularity-indexing.md) | Multi-granularity indexing + retrieval (parent/index) | — | Expanded into sub-issues | C-2a complete |
| &nbsp;&nbsp;&nbsp;└ [C-2b-1](C-2b-1-reindex-processor-and-retrieval-api.md) | Re-index processor + retrieval API (pure ColBERT) | AFK | Ready | None (C-2a shipped) |
| &nbsp;&nbsp;&nbsp;└ [C-2b-2](C-2b-2-llm-reranker.md) | LLM reranker (intent-aware re-ordering) | AFK | Pending | C-2b-1 |
| &nbsp;&nbsp;&nbsp;└ [C-2b-3](C-2b-3-live-verify.md) | Live-verify orchestrator | HITL | Pending | C-2b-1 + C-2b-2 |
| └ [C-2c](C-2c-classifier-api.md) | Automatic classifier API (parent/index) | — | Expanded into sub-issues | C-2b-2 complete |
| &nbsp;&nbsp;&nbsp;└ [C-2c-1](C-2c-1-classify-task-fn-and-schemas.md) | Pure classify-task fn + event/command schemas | AFK | Unit-verified | None (C-2b-2 live-verified) |
| &nbsp;&nbsp;&nbsp;└ [C-2c-2](C-2c-2-defcommand-and-repl-researcher-integration.md) | defcommand + repl-researcher integration + :auto-classify? flag | AFK | Unit-verified | C-2c-1 |
| &nbsp;&nbsp;&nbsp;└ [C-2c-3](C-2c-3-live-verify.md) | Live-verify orchestrator (3-way comparison) | HITL | **Deferred until C-2d-3** | C-2c-1 + C-2c-2 + C-2d-3 |
| └ [C-2d](C-2d-hierarchical-tree-classes.md) | Hierarchical tree-classes (SKOS) | — | Expanded into sub-issues | C-2c-1 + C-2c-2 complete |
| &nbsp;&nbsp;&nbsp;└ [C-2d-1](C-2d-1-foundation-and-seeds.md) | Foundation: scope + parent field + processor + seed parent links | AFK | Ready | None |
| &nbsp;&nbsp;&nbsp;└ [C-2d-2](C-2d-2-walk-down-and-consolidator-inference.md) | Walk-down classifier + consolidator parent-inference | AFK | Pending | C-2d-1 |
| &nbsp;&nbsp;&nbsp;└ [C-2d-3](C-2d-3-live-verify.md) | Live-verify (4 scenarios; resolves C-2c-3 deferral) | HITL | Pending | C-2d-1 + C-2d-2 |
| [C-3](C-3-automated-extraction-judges-audit.md) | Automated principle extraction + judges audit | HITL (sub-grill) | Pending | C-1 |
| [C-4](C-4-cross-tree-pattern-reuse.md) | Cross-tree pattern reuse + node-type learning | HITL (sub-grill) | Pending | C-1, C-3 |

## Dependency Graph

```
C-1 (SHIPPED)
 │
 ├──→ C-2a (SHIPPED — all four sub-slices live-verified)
 │     │
 │     └──→ C-2b ─→ C-2b-1 (re-index + search) ─→ C-2b-2 (LLM reranker) ─→ C-2b-3 (live verify)
 │                                                                          │
 │                                                                          └──→ C-2c ─→ C-2c-1 (classify-task fn + schemas) ─→ C-2c-2 (defcommand + repl-researcher integration) ─→ C-2c-3 (live verify, DEFERRED)
 │                                                                                                                                                                                       │
 │                                                                                                                                                                                       └──→ C-2d ─→ C-2d-1 (foundation + seeds) ─→ C-2d-2 (walk-down + consolidator) ─→ C-2d-3 (live verify; unblocks C-2c-3)
 │
 ├──→ C-3 (judges + automated extraction; sub-grill pending)
 │
 └──→ C-4 (cross-tree pattern reuse; sub-grill pending) [also depends on C-3]
```

C-2a is fully shipped. C-2b-1 is committed; C-2b-2 and C-2b-3 are live-verified and pending bundle commit. C-2c-1 + C-2c-2 are unit-verified; C-2c-3 is deferred until C-2d-3 lands (hierarchy is needed for fresh-mint). C-2d is architecturally resolved with three sub-slices ready; **C-2d-1 is the next-action slice**.

## Priority — C-2d-1 is the next-action slice

C-2a fully shipped. C-2b live-verified (bundle pending commit). C-2c-1 + C-2c-2 unit-verified. C-2c-3 deferred until C-2d-3 (the hierarchy unlocks meaningful fresh-mint). The next concrete unit of work is **C-2d-1 — foundation: concept-graph scope + parent field + reactive processor + seed parent links**.

Recommended order for picking up Category C from here:

1. **C-2d-1** first — foundation infra: `:scope :tree-class` on concepts graph + `:parent-tree-id` on description body + new reactive processor + hand-author parent links for 5 benchmark seeds.
2. **C-2d-2** after 1 — walk-down algorithm in `classify-task` + `:walk-down?`/`:specificity-threshold` opts + consolidator's first-time parent inference.
3. **C-2d-3** after 1 + 2 — 4-scenario live-verify (walk-down-to-leaf / control / no-descendant / fresh-mint). Unblocks **C-2c-3** as a follow-on (or fold its scenarios into c2d's).
4. **C-3** — sub-grill needed first; can be developed in parallel.
5. **C-4** — sub-grill needed first; needs C-3 in place.

## Sequence Rules

- All Category C work stays on `feature/core-orc-upgrades` per the saved branch policy. No commits to main during Category C until the full initiative is reliable end-to-end.
- Each slice is end-to-end testable on its own and leaves the branch in a runnable state.
- Each slice ends with a **live real run** (real LLM, real event store, real Grain pipeline) confirming production-truth — per the project's verification rule.
- All event emissions flow through proper Grain commands (commands emit events; processors react). No direct event-store writes.
- Recursive RLM is the default everywhere — no terminal-mode-forcing in new seeds, examples, or live-verify scripts.
- Framework tests must remain green on the RLM-related namespaces.

## Why this order

**C-1 shipped first** because it defined the principle-shape contract — `:trait` + `:good-when`/`:avoid-when` + `:recommended-pattern`/`:recommended-alternative` + `:confidence` + `:evidence-count`. Every C-2 description entry reuses that shape, and the C-1 `format-rich-pattern` extension renders them in the model's prompt without further work.

**C-2 expanded** beyond the original "compute a task-class UUID" stub when the grill surfaced the broader vision: a self-description library spanning trees + nodes at three granularities (node-type, node-instance, tree-fingerprint), evolving from rolling judge feedback on the event store. See the C-2 PRD for the full architectural decisions.

**C-3 third** because:
- It automates principle generation — wires `:tree-results` entries through `evaluation/evaluate-trace`, adapts GEPA's `propose-new-instruction` for principle extraction.
- Includes the adversarial audit of the existing 4 rubric-based judges: do they produce signal useful for tree-structure decisions, or are they too output-content-focused?
- May add structural judges (tree-shape quality, efficiency).
- Sub-grill needed: GEPA reuse strategy, judge audit findings, structural-judge necessity, when-to-trigger-reflection.

**C-4 last** because:
- It broadens retrieval to node-type-level principles (FUTURE-VISION 4a.6) and ontology-wide pattern discovery (4a.10).
- Builds on C-3's automated extraction to populate the ontology automatically.
- Sub-grill needed: node-type principle storage shape, retrieval composition across signals, anti-pattern handling.

## Cross-references to prior work

Category C builds on:

- [`docs/prd/rlm-recursive-emit-tree.md`](../../prd/rlm-recursive-emit-tree.md) — R-1 + R-2 (DONE). Provides the `:tree-results` accumulating vector + drill-down primitives. This IS the data layer Category C learns from.
- [`docs/prd/phase2-observability-layer.md`](../../prd/phase2-observability-layer.md) — O01-O03 (DONE). Per-node usage, `:input-profile`, trajectory bookend. The per-tree evidence C-3 will consume.
- [`docs/prd/category-d-resilience.md`](../../prd/category-d-resilience.md) — D-008 + D-003 (DONE). `:partial-summary`, budget-aware Phase 2 timeout. The failure-mode evidence sources hand-authored principles in C-1 reference.
- [`docs/FUTURE-VISION.md`](../../FUTURE-VISION.md) — Phase 4a (Ontology Knowledge System) + Theme 6 (Tree Hierarchy & Self-Description) + Theme 9 (ColBERT/RAGatouille integration).
- [`docs/SELF-LEARNING-MANUAL.md`](../../SELF-LEARNING-MANUAL.md) — Existing `:ontology/record-tree-strength`/`record-tree-weakness` command surface that C-1 reuses verbatim and C-2 extends with description events.
- [`docs/FEEDBACK-LOOP.md`](../../FEEDBACK-LOOP.md) — The 7-stage continuous improvement cycle. C-1 wires stages 5-6; C-2's consolidator wires stages 2-4; C-3 enriches stages 2-4 further.
- [`docs/GEPA-INTEGRATION-PLAN.md`](../../GEPA-INTEGRATION-PLAN.md) — Existing GEPA proposer + reflection infrastructure. The reuse candidate for C-3's automated extraction.

## Out of scope (deferred or orthogonal)

- Personality layer (FUTURE-VISION Theme 7) — orthogonal.
- Conversational debugging (FUTURE-VISION Theme 5 / Phase 5) — orthogonal.
- Skills → Trees pipeline (FUTURE-VISION Theme 1 / Phase 6) — orthogonal.
- Tree hierarchy graph (FUTURE-VISION Phase 3.4) — orthogonal.
- Rolling-metric threshold alerts (FUTURE-VISION Phase 2.3-2.4) — covered by separate work.
- TTL/SKOS export of the ontology (FUTURE-VISION 4a.7) — already implemented.
