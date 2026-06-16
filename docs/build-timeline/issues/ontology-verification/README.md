# Ontology Verification Phase — Issue Slices

Local issue slices for the verification phase. Parent PRD:
`docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`.

Goal: close the three intent gaps in the rebuilt system (auto-embed, format-aware
source ingestion, axiom ingest) and then run an unbiased, adversarial BRYC
head-to-head — building the same Louisiana career/program graph from the same 5
official sources with both the old and new builders and comparing the graph AND
the per-vertical exploration output on the actual information returned.

Local-only; not published to an external tracker. Every slice carries the SAME
binding **Core Disciplines** block (standard 7 + verification-phase additions
8–11) — identical across all slices, no reinterpretation.

## Slices

| # | Slice | Type | Milestone | Blocked by |
|---|-------|------|-----------|------------|
| V01 | Auto-embed/ColBERT field detection in skeleton | AFK | M1 | — |
| V02 | Mode A early read (ingest existing TTL + per-vertical read) | HITL | M1 | V01 |
| V03 | CSV source-access tools | AFK | M2 | — |
| V04 | SQL/SQLite source-access tools | AFK | M2 | — |
| V05 | Excel source-access tools | AFK | M2 | — |
| V06 | RLM-controlled ingestion wiring | AFK | M2 | V03,V04,V05 |
| V07 | Axiom-draft ingest | AFK | M2 | — |
| V08 | Graph A1 build harness (old builder, strongest honest config) | HITL | M3 | sources gathered |
| V09 | Graph B build (new builder, 5 sources) | AFK | M3 | V01,V06,V07 |
| V10 | Graph-diff harness (A1/A2/B structure stats) | AFK | M3 | V08,V09 |
| V11 | Old exploration re-run live (daryls-area51 explorers over A2) | HITL | M3 | A2 available |
| V12 | New exploration (5 vertical framings over B) | AFK | M3 | V09 |
| V13 | Head-to-head report (per-vertical side-by-side + verdict) | HITL | M3 | V10,V11,V12 |
| V14 | TTL ingest: brownfield concept-type recognition + no-false-green | AFK | M3 | — (gates A2/Mode-A) |
| V15 | hybrid-search result label enrichment (event-sourced projection) | AFK | M3 | — (gates V12) |
| V16 | ColBERT index-creation timeout scaling (+ LMDB map-size note) | AFK | M3 | — (gates V09) |
| V17 | Graph B full-scale rebuild — autonomous discovery, no hardcoded joins | AFK | M3 | V09 |

**V17 supersedes V09's 434-concept sample as the comparison artifact.** V09 proved
the pipeline but its connectivity was driver-engineered (hand-fed offsets/keys/
LIMITs). V17 hands the evolutionary builder only the sources + tools + domain goal
and tests whether it DISCOVERS the cross-source connections (incl. the earnings↔
program bridge) on its own — NO hardcoded joins in the driver (HITL directive).
The earnings→program link is a measured test result, not a task.

### Builder-hardening track (surfaced by V17's honest-partial result)

V17 ran true (no hardcoding) and honestly showed the autonomous builder is
currently partial: it discovered the central crosswalk bridge but produced no
program nodes, 119/249 dangling edges, no earnings (PSEO `:no-output` from Excel
tool arity errors), and no comprehensive coverage. These are GENERAL,
domain-agnostic builder gaps to fix (format/medium specialists are encouraged;
no education/industry tuning) before re-running.

| # | Slice | Type | Milestone | Blocked by |
|---|-------|------|-----------|------------|
| V18 | Referential integrity as an always-on structural invariant (auto-mint implied entities) | AFK | M3 | — |
| V19 | Format-specialist tool ergonomics + count affordance + stream-all | AFK | M3 | — |
| V20 | General scaffolding (entity-modeling + extract-to-coverage) + deterministic full-extraction | AFK | M3 | V18, V19 |
| V21 | Re-run V17 autonomously to measure the improved builder | AFK | M3 | V18, V19, V20 |

Per HITL direction, coverage is solved BOTH ways (count affordance + guidance AND
deterministic full-extraction where the specialist designs the transform on a
sample and the skeleton applies it to the full streamed source).

**Fix-slices V14–V16** were surfaced by the V02 Mode-A early read — real,
root-caused bugs the verification phase was designed to expose. Routed as focused
fix-slices ahead of their dependents (the chosen "fix-slices before dependents"
posture). They touch three non-overlapping files (`ttl_ingest.clj`,
`retrieval.clj`, `colbert/core/bridge.clj`) so they run as a parallel fix-wave.
Evidence: `docs/build-timeline/live-verify/V02-mode-a-early-read.md`.

## Dependency graph

```
M1:  V01 ──> V02 (early read, HITL)
M2:  V03 ┐
     V04 ├─> V06 ──┐
     V05 ┘         │
     V07 ──────────┤
M3:  V16 ─> V09 (ColBERT scale before build)
     V01,V06,V07 ─> V09 ─┐
     (sources) ──> V08 ──┼─> V10 ─┐
     V14 ──> A2 ──> V11 ──────────┤   (V14 unblocks brownfield A2 ingest)
                  V15 ─> V12       │   (V15 = labeled hits before exploration)
                  V09 ─> V12 ──────┴─> V13 (verdict, HITL)
```

## Posture

- Sequenced M1 → M2 → M3 (early read first; build the gating features; then the
  official head-to-head). V01 also gates V02.
- The 5 official sources: IPEDS `output.db` (SQLite), `cip_soc_crosswalk.csv`,
  O*NET `db_30_1_excel` (Excel), LA-OEWS `louisiana_occupation_wages.csv`, PSEO
  `pseo_la.xlsx`. The hand-made `louisiana_programs_with_embeddings.csv` is
  EXCLUDED — each builder makes its own embeddings.
- The format-aware RLM source-ingestion (V03–V06) is the ADR-grade architecture
  and the gating P1 work that delivers Pillar 1 (any source → general ontology).
- HITL slices need human input: V02 (review the early read), V08 (source files +
  a `main` worktree), V11 (old-SHA setup), V13 (the qualitative verdict).
