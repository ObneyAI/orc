# BRYC Head-to-Head Comparison — Run Design (grill output)

Output of the verification-phase grill. Locks the design for an unbiased,
adversarial proof that the rebuilt ontology system is qualitatively BETTER than
the system that came before — by recreating the BRYC Louisiana career/program
graph from the same official sources with both systems and comparing the graph
AND the exploration output, per vertical, side-by-side.

Companion: `VERIFICATION-PHASE-PREP.md` (gaps), `intent-alignment-verdict.md`
(four pillars), the plan at `~/.claude/plans/optimized-kindling-flame.md`, and
daryls-area51 `docs/BRYC-GRAPH-ANALYSIS.md`.

---

## Goal (one line)

Prove the new system builds a richer, better-connected graph AND explores it
with qualitatively better results than the old system — judged on the ACTUAL
information returned, per vertical, by both the user and the model, not just
"both completed."

## Baselines & the comparand (grill Q1 — "both baselines")

- **Graph A1** — old ORC `main` evolutionary builder at its STRONGEST honest
  config (embeddings ON, crosswalk CIP↔SOC extracted as edges, IPEDS FK
  extraction) on the 5 official sources. Same-builder lineage.
- **Graph A2** — the REAL production BRYC graph (Python-DSPy-built TTL +
  crosswalk augmentation; `daryls-area51/.bryc-graph-cache-with-embeddings.json`,
  ~14,018 nodes). "What we actually had."
- **Graph B** — the NEW system on the SAME 5 official sources.
- **Bar (Q5):** B must be ≥ A on EVERY dimension AND strictly > on the
  rebuild-target dimensions (cross-source connections, property/representation
  depth, grounding/evidence, retrieval recall), with NO regression on the old
  system's strong cases. New must beat A1 decisively and at least MATCH A2's
  exploration quality. Any regression is flagged + explained/fixed.

## Source set (5 official; user-confirmed)

IPEDS `output.db` (SQLite) · `cip_soc_crosswalk.csv` · O*NET v30.1
`db_30_1_excel` (Excel) · LA-OEWS `louisiana_occupation_wages.csv` · PSEO
`pseo_la.xlsx` (Census 2025Q4). The user's hand-made
`louisiana_programs_with_embeddings.csv` is EXCLUDED — each builder assembles
its own program set + its OWN embeddings (the direct P2 auto-embed test).
Scholarships + TOPS deferred.

---

## ARCHITECTURE DECISION (ADR-grade): format-aware, RLM-controlled source ingestion

**Status:** accepted (grill Q2 + Q3). **Context:** the new system today only
parses `:ttl` + `:inline-*` and runs RLM discovery over source *text*; it has NO
path to ingest large structured sources (IPEDS 72 MB SQLite, O*NET 50 tables,
PSEO 119 MB sheet) — you can't pour those into an LLM context. The old builder's
`csv_ontology`/`sql_ontology` sheets parse CSV/SQLite structurally (no Excel).

**Decision:** the new system gains **format-aware ingestion through the new
process** (NOT by bolting the old sheets onto the old pipeline). The discovery
RLM is granted **per-format SOURCE-ACCESS TOOLS** so it CONTROLS its exploration
of each source by shape — mirroring how S19 gave the RLM tools to explore the
graph without loading it:
- CSV: peek-columns / sample-rows / profile-column
- SQL/SQLite: list-tables / table-schema / foreign-keys / sample-rows / query
- Excel: list-sheets / sheet-columns / sample-rows (or sheet→CSV access)
- text: read (existing)
The RLM samples/queries the source (never dumps it), designs the extraction, and
feeds the ONE new skeleton (normalize → dedup cascade S12 → evidence S13 →
validate S10/11 → auto-embed P2 → index → CQ S15). The old sheets' extraction
KNOWLEDGE (schema→classes, FKs→edges, rows→individuals) is ported into the
tools' internals.

**Alternatives rejected:** (a) reuse old sheets as-is into the new skeleton's
parse stage — loses "discovery controls exploration" + general-format intent;
(b) deterministic per-format adapters + RLM enrichment — less RLM control;
(c) pre-normalize everything to TTL/inline ETL — bespoke glue + confound.

**Consequence:** this is real net-new feature work (the gating P1), and it
directly delivers Pillar 1 (any source → general ontology). Hard to reverse (it
defines the general builder's ingestion architecture), so recorded here.

---

## Sequencing (grill Q4 — milestones)

- **M1 — Mode A early read + brownfield proof.** Ingest the existing
  `louisiana_programs_full.ttl` into the new substrate (S09 TTL ingest), close
  **P2 auto-embed** (gates this — exploration needs embeddings), run the
  exploration probes. Zero new ingestion work; early signal on substrate +
  retrieval + exploration quality; proves "bring your own graph, we improve +
  extend it."
- **M2 — build the gating features (TDD slices + live verifies):**
  1. P2 auto-embed/ColBERT field detection wired into the skeleton (also M1).
  2. P1 format-aware RLM source ingestion (CSV/SQL/Excel source-access tools +
     RLM control) per the ADR above.
  3. Axiom-draft ingest (stop dropping discovered axioms).
- **M3 — Mode B official head-to-head.** Build graph A1 (old builder, `main`
  worktree) + use graph A2 (production); build graph B (new). Diff graphs + run
  exploration per vertical on all; document side-by-side; adversarial review.

## Exploration comparison (grill Q6 — re-run old explorers live)

- **Old side:** stand up `daryls-area51`'s 5 bespoke explorers (career /
  financial / outcome / academic / preference; old ORC SHA) over graph A2 and
  **re-run them live** on the probe set — fresh, same conditions, nothing stale.
- **New side:** run the new system's GENERAL RLM exploration (S19 graph tools +
  recursive-RLM) over graph B, framed in the SAME 5 verticals on the SAME
  profiles. (High bar: general new exploration vs hand-tuned bespoke explorers.)
- **Probe set:** the 3 recorded student profiles (Trinity / Aminata / Reagan) +
  adversarial probes — career differentiation across profiles, SOC→CIP→program
  multi-hop incl. crosswalk-only paths, financial/wage grounding, HBCU
  sensitivity, apprenticeship-vs-degree, hard-filter correctness.

## Fairness controls

- A1 and B embed with the SAME model + ColBERT config (retrieval differences
  reflect graph quality, not embedding choice). A2's pre-computed embeddings
  can't be controlled — noted; A2 is a reference bar.
- Same exploration probes + same 5 vertical framings on both sides.
- Outputs captured VERBATIM and diffed adversarially (hunt for where NEW is
  worse), not "both completed."

## Review artifact (grill Q5 — qualitative, per-vertical)

A documented side-by-side report, organized PER VERTICAL (career / financial /
outcome / academic / preference) per probe/profile, showing the FULL info each
system returned (recommendations, connections followed, reasoning, evidence) —
so the user + model can read the actual output and judge quality directly. Plus
an LLM-judge scoring pass on the rubric dimensions and a graph-structure stats
table (nodes, edges by type, cross-source links, properties per concept,
coverage). The qualitative read is primary; the scores support it.

## Dimensions scored (rubric)

coverage · cross-source connection richness · property/representation depth ·
recommendation relevance · reasoning/grounding (evidence + tradeoffs) ·
retrieval recall/precision. Per dimension: B vs A1 vs A2, with a regression flag.

## Immediate next step

Begin M1: close P2 auto-embed (TDD slice + live verify), then run Mode A on the
existing TTL and produce the first per-vertical side-by-side read.
