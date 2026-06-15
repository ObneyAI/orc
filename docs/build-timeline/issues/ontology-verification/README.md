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

## Dependency graph

```
M1:  V01 ──> V02 (early read, HITL)
M2:  V03 ┐
     V04 ├─> V06 ──┐
     V05 ┘         │
     V07 ──────────┤
M3:  V01,V06,V07 ─> V09 ─┐
     (sources) ──> V08 ──┼─> V10 ─┐
                  A2 ──> V11 ──────┤
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
