# Meaningful multi-table extraction — issue index

Parent PRD: [`../../prd/meaningful-multi-table-extraction.md`](../../prd/meaningful-multi-table-extraction.md).

The pipeline extracts the WRONG containers from a many-table source and models them shallowly. Root-caused in code+fs during the O\*NET due-diligence: `orchestrate-extract-containers` does `(take cap (list-source-containers source))`; the excel-dir tool sorts alphabetically; O\*NET's two alphabetically-first `.xlsx` are junction/bridge tables (`Abilities to Work Activities`, `Abilities to Work Context`) that carry NO occupation data — so the pipeline extracts noise and never the occupation-centric tables. Long-form tables (`Skills`) are never aggregated (per-row transform → ~91 fragments/occupation), and occurrence-merge across containers is suspected absent.

**Design (grilled, resolved):** deterministic container shape-classifier (drop bridge/reference noise, tag entity/long-form/wide-stats) → Survey/Model relevance-rank the survivors, bounded → Model-authored aggregating transform (long-form group-by → top-N flat attribute) → occurrence-merge per-entity drafts across containers → flat top-N attributes (A2 + GM-1 aligned). **Sequence: land this BEFORE the A2-vs-B comparison** (fair head-to-head; B can beat A2's empty `topSkills`).

## Slice table + cadence

| Slice | What | Type | Blocked by | /prototype |
|-------|------|------|-----------|-----------|
| **MT-1** | Deterministic container shape-classification + structural noise pre-filter (drop bridge/lookup; tag entity/long-form/wide-stats) | AFK | — | **YES** — the structural signals on real containers |
| **MT-2** | Survey-driven relevance rank + bounded container selection (replaces take-first-N) | HITL | MT-1 | — |
| **MT-3** | Model-authored aggregating transform (long-form group-by → top-N flat array attribute), streamed/bounded | HITL | MT-2 | **YES** — the aggregating-transform shape + streaming |
| **MT-4** | Within-source occurrence-merge (verify reconcile/landing unions same-key drafts' attributes; build if absent) | AFK | MT-3 | — |
| **MT-5** | Acceptance: comprehensive O\*NET build → occupations with populated topSkills/topKnowledge/jobZone/riasec; then A2-vs-B | HITL | MT-4 | — |
| **MT-6** | List-valued aggregation (repeating-key attribute tables → flat bounded list, not per-row nodes) — LANDED off the MT-5 acceptance findings ([handoff](../../handoff-plan/MT-6-list-valued-aggregation-handoff.md)) | AFK | MT-5 findings | **YES** — killed the structural :list-form rule |
| **MT-7** | ~~GC-1 keying-resolution patch~~ — SUPERSEDED ([why](MT-7-entity-type-identity-consistency.md)): the /prototype disproved it; re-designed via /grill-with-docs → [ADR-0001](../../../../components/ontology/docs/adr/0001-canonical-vocabulary-binding.md) | — | — | **YES** — disproved the patch |
| **MT-7a** | Vocabulary binding + enforcement seam (authors bound to the canonical vocabulary; exact-match → one re-ask → honest fail; empty vocab = hard stop) | AFK | MT-6 | — |
| **MT-7b** | Vocabulary proposal path (explicit new-type admission; local during concurrent extract; deterministic post-extract dedup; keying-collision surfaced not merged) | AFK | MT-7a | — |
| **MT-7c** | Acceptance: unfragmented comprehensive build ×2; CQ-gate as the cross-run criterion; A2-vs-B reframed as capability | HITL | MT-7a + MT-7b | — |

## The build loop
Each slice: **/handoff → /prototype (where flagged) → /tdd (tests FIRST, red→green) → /inspect-orc** (adversarial re-verify on REAL O\*NET data before commit). 13 Core Disciplines embedded verbatim per issue. **Run ONE bounded build at a time** with `pgrep -f` JVM hygiene.

## Relationship to other lines
- **GM-1 (graph-grounded modeling):** MT stays flat per GM-1's grain-reify (long-form element = layout artifact = flat attribute). No new reify.
- **ER-3 (institution fragmentation):** SEPARATE — an ipeds/institution naming issue, independent of O\*NET; not blocked by / blocking MT.
- **MC line:** MT builds on MC-0 bounding + GC-13 parallel extraction (reuse, don't fork).
