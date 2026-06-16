# Discovery Behavior-Tree Redesign — Issue Slices

Local issue slices for the discovery-tree redesign. Parent PRD:
`docs/build-timeline/prd/2026-06-16-discovery-behavior-tree-redesign.md`.

Goal: replace the monolithic open-ended `run-discovery!` loop + ~400-line
mega-prompt with a cohesive discovery behavior tree — focused repl-researcher
reasoning nodes (profile / model+grain+scope / transform-design) + the
deterministic skeleton (`build!`) as an intact sub-call + a CQ-driven adaptive
loop. Re-orchestration, not a rewrite: reuses V06/V19 source tools, V20
full-extraction apply-step, V18 referential integrity, S12 dedup, S03 alignment,
S13 evidence, S21 hybrid retrieval, S14/S15 ORSD+CQ.

Local-only; not published to an external tracker. Every slice carries the SAME
binding **Core Disciplines** block (standard 7 + redesign additions 8–12),
identical across all slices — additions 8 (re-orchestration not rewrite), 9
(adversarial verdict + honest negatives), 10 (skeleton wraps LLM discovery), 11
(standing ops), 12 (domain/industry-agnostic; format specialists encouraged).

## Slices

| # | Slice | Type | Prototype | Blocked by |
|---|-------|------|-----------|------------|
| DT1 | Tree scaffold + orchestration skeleton (tracer bullet) | AFK | YES | — |
| DT2 | Profile node (focused) | AFK | WORTH | DT1 |
| DT3 | Model + grain + scope node (focused) | AFK | WORTH | DT2 |
| DT4 | Transform-design + validate node (+ V20 apply wiring) | AFK | SOFT | DT3 |
| DT5 | Requirements / CQ node (graph-level) | HITL | WORTH | DT2 |
| DT6 | Node prompt-assembly promotion seam (static→living) | AFK | NO | DT2,DT3,DT4 |
| DT7 | Cross-source linking / reconcile (against graph state) | AFK | WORTH | DT4 |
| DT8 | CQ-driven loop + focused recovery | AFK | YES | DT5,DT7 |
| DT9 | Greenfield-vs-maintain branch point (maintain stub) | AFK | NO | DT1 |
| DT10 | End-to-end live verify on the BRYC 5 (acceptance) | HITL | NO | DT1–DT9 |

## Dependency graph

```
DT1 ─┬─> DT2 ─┬─> DT3 ─> DT4 ─> DT7 ─┐
     │        └─> DT5 ──────────────┤
     │        DT2,DT3,DT4 ─> DT6     ├─> DT8 ─┐
     └─> DT9                         │        ├─> DT10 (acceptance, HITL)
                                     └────────┘
```

## Posture

- Sequenced slice-by-slice (the user's directive): one slice at a time, each with
  its `/handoff` + `/prototype` (where flagged) + `/tdd`, then the binding
  after-each verification before the next. We never 100% trust a subagent — deeply
  check the work + inspect what we expect against the disciplines.
- The contract chain DT2→DT3→DT4 is sequential (each node consumes the prior's
  output shape). DT5 (after DT2), DT6, DT9 are parallel-able but we run serially
  for inspection discipline unless a wave is clearly safe.
- Maintain (P4) is OUT OF SCOPE here — context preserved in
  `docs/build-timeline/handoff-plan/2026-06-16-DEFERRED-maintain-incremental-discovery-handoff.md`;
  DT7 + DT9 keep it a clean later addition.
- Dispatch detail (per-slice handoff context, prototype directives, the
  after-each protocol):
  `docs/build-timeline/handoff-plan/2026-06-16-discovery-tree-dispatch-plan.md`.
