# C-2: Living Descriptions for trees and nodes (parent issue)

> **This file was originally a one-sentence-stub for "automatic task-class classification."** A subsequent grill expanded the scope to a full *self-description library* spanning trees + nodes at three granularities — see the dedicated PRD. This file is now a pointer/index to the sub-issues that actually do the work.

## Parent

PRD: [`docs/prd/category-c-2-living-descriptions.md`](../../prd/category-c-2-living-descriptions.md)

## What this issue is now

C-2 is the **Living Descriptions** initiative — every node-type, node-instance, and tree-fingerprint gets a self-description that:

1. **Seeds** from hand-authored entries for a controlled set (18 seeds covering 6 node types + 5 benchmark task classes + 7 generic patterns) + LLM-structural-baseline at confidence 0.1 for everything else.
2. **Evolves** over time as the system observes execution + judge events (rolling aggregator → consolidator processor → LLM reflection → new description event).
3. **Surfaces** to the model via semantic retrieval (ColBERT + RRF) — answers queries like "find me an `:llm` node that validates research data" OR "find me a tree shaped like research-then-synthesize" OR "is there a description for this specific node in workflow X?".

Every description entry is principle-shaped at every confidence level (the same shape C-1 already renders via `format-rich-pattern`).

Per the PRD's Q2 decision, C-2 splits into three sub-categories — only the first (C-2a) is detailed and ready for implementation; C-2b and C-2c are stubs awaiting their own sub-grills.

## Sub-issues

### C-2a — Self-description generation (DETAILED, ready)

| # | Title | Type | Blocked by |
|---|-------|------|------------|
| [C-2a-1](C-2a-1-static-descriptions-and-seeds.md) | Static description event types + read model + 18 hand-authored seeds | AFK | None |
| [C-2a-2](C-2a-2-rolling-aggregator-extension.md) | Rolling aggregator extension — cross-sheet node-type + tree-fingerprint | AFK | None |
| [C-2a-3](C-2a-3-consolidator-and-reflection.md) | Consolidator processor + LLM reflection + triggers + anti-recency safeguards | AFK (with HITL audit) | C-2a-1 + C-2a-2 |
| [C-2a-4](C-2a-4-live-verify.md) | Live-verify — 3-run regression + seed-executability + retrieval-quality | AFK | C-2a-1 + C-2a-2 + C-2a-3 |

### C-2b — Multi-granularity indexing + retrieval (STUB)

| # | Title | Type | Blocked by |
|---|-------|------|------------|
| [C-2b](C-2b-multi-granularity-indexing.md) | ColBERT integration + RRF rank-fusion across granularities | HITL (sub-grill required) | C-2a complete |

### C-2c — Automatic classifier API (STUB)

| # | Title | Type | Blocked by |
|---|-------|------|------------|
| [C-2c](C-2c-classifier-api.md) | Thin wrapper assigning `:tree-id` automatically when `:context` unset | HITL (sub-grill required) | C-2a + C-2b complete |

## Dependency graph

```
C-2a-1 (seeds + storage) ─┐
                          ├──→ C-2a-3 (consolidator) ──→ C-2a-4 (live-verify) ──→ C-2b ──→ C-2c
C-2a-2 (aggregator)    ─┘
```

C-2a-1 + C-2a-2 are parallelizable (independent surfaces). C-2a-3 needs both. C-2a-4 gates all of C-2a. C-2b needs the full C-2a corpus. C-2c needs C-2b's retrieval.

## Priority

C-2a-1 is the next-action slice. Pick that up first.

C-2b and C-2c remain stubs because they each have open architectural decisions that need their own grill before implementation can begin. See each file's "Open decisions" section.

## Cross-references

- Parent PRD: [`docs/prd/category-c-2-living-descriptions.md`](../../prd/category-c-2-living-descriptions.md)
- Category C top-level PRD: [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md)
- Prior work this builds on: [C-1 — Principle storage + retrieval + injection](C-1-principle-storage-retrieval-injection.md) (SHIPPED — provides the principle-shape contract C-2 reuses).
- Downstream: [C-3 — Automated extraction + judge audit](C-3-automated-extraction-judges-audit.md) (judges that feed the consolidator). [C-4 — Cross-tree pattern reuse](C-4-cross-tree-pattern-reuse.md).
