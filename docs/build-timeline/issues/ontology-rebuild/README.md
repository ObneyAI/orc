# Ontology Substrate + Builder Rebuild — Slice Index

Parent PRD: [`../../prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`](../../prd/2026-06-12-ontology-substrate-and-builder-rebuild.md)
Published: 2026-06-12 · Branch: `feature/ontology-architecture` · Execution: `/tdd` per slice

Every slice carries the **Core Disciplines block verbatim** — binding on
every implementer, human or subagent. Read it in any slice file before
starting work; it is identical in all of them by design.

## Phase 1 — Substrate correctness + representation

| Slice | Title | Type | Blocked by |
|---|---|---|---|
| [S01](S01-per-source-rrf-caps.md) | Per-source caps before RRF fusion | AFK | — |
| [S02](S02-uniform-ontology-id-scoping.md) | Uniform ontology-id scoping across signals + accessors | AFK | — |
| [S03](S03-alignment-section-registry.md) | Alignment-section registry + auto-widening | AFK | S02 |
| [S04](S04-labels-datatypes-annotations.md) | Labels/datatypes/annotations schema + export | AFK | — |
| [S05](S05-quantities-and-sequences.md) | Quantity+unit + ordered sequences | AFK | — |
| [S06](S06-edge-metadata.md) | Edge metadata schema'd + serialized | AFK | — |
| [S07](S07-axioms-as-data.md) | Axioms-as-data events + OWL export | AFK | — |
| [S08](S08-equivalence-events.md) | Equivalence events with `:kind` | AFK | S03 |
| [S09](S09-ttl-round-trip-gate.md) | **TTL ingestion + G1 round-trip gate** | AFK | S04–S08 |

## Phase 2 — Validation + hygiene

| Slice | Title | Type | Blocked by |
|---|---|---|---|
| [S10](S10-lint-registry-core.md) | Lint registry + EDN-SHACL interpreter core | AFK | — |
| [S11](S11-full-lint-set-shacl-export.md) | Full lint set + SHACL export + consumer shapes | AFK | S10, S07 |
| [S12](S12-dedup-cascade.md) | Dedup cascade + check-before-mint | AFK | S07, S08 |
| [S13](S13-evidence-tier-1.md) | Evidence Tier-1 (deterministic, always-on) | AFK | S12 |
| [S14](S14-orsd-spec-storage.md) | ORSD spec storage | AFK | — |
| [S15](S15-cq-evaluation-runner.md) | CQ evaluation runner (judge-based) | AFK | S14, S19 |

## Phase 3 — Builder

| Slice | Title | Type | Blocked by |
|---|---|---|---|
| [S16](S16-extraction-bench.md) | **Extraction bench (G2 harness)** | **HITL** | — |
| [S17](S17-deterministic-skeleton.md) | Builder deterministic skeleton | AFK | S12, S10 |
| [S18](S18-rlm-discovery-and-seeds.md) | Recursive-RLM discovery + seed corpus (**G2-gated**) | **HITL** | S17, S19, S16 |

## Phase 4 — Agent integration

| Slice | Title | Type | Blocked by |
|---|---|---|---|
| [S19](S19-rlm-ontology-tools.md) | RLM ontology tools (builder-facing subset) | AFK | S02 |
| [S20](S20-graph-orientation-card.md) | Graph orientation card (deterministic skeleton) | AFK | S19, S07, S14 |

## Dependency graph (start-anywhere roots: S01, S02, S04, S05, S06, S07, S10, S14, S16)

```
S02 ──► S03 ──► S08 ──┐
S04 ──────────────────┤
S05 ──────────────────┼──► S09 (G1 gate)
S06 ──────────────────┤
S07 ──┬───────────────┘
      ├──► S11 ◄── S10
      └──► S12 ──► S13
              └──► S17 ──┐
S02 ──► S19 ──┬──► S15   ├──► S18 (G2 gate) ◄── S16
              └──► S20 ◄─┴── S07, S14
```

NEXT-tail items (Tier-2 consolidation, chain synthesis, HITL review UI,
temporal retrieval, communities, affected-set, general tool exposure,
card prose layer, TTL-shape ingestion) remain in the PRD — sliced later.
