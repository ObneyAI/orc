# PRD — Ontology Verification Phase + BRYC Head-to-Head Comparison

**Status:** local, ready-for-agent. **Branch:** `feature/ontology-architecture`.
**Date:** 2026-06-15. **Predecessor:** `2026-06-12-ontology-substrate-and-builder-rebuild.md` (the 20-slice rebuild, shipped).

This PRD covers the VERIFICATION PHASE — the work to (a) close the intent gaps
the live-verify + intent-alignment review exposed in the rebuilt system, and (b)
prove, unbiased and adversarially, that the new system is qualitatively better
than the one that came before, via a real BRYC career/program graph head-to-head.

It synthesizes: `docs/build-timeline/live-verify/BRYC-COMPARISON-RUN-DESIGN.md`
(grill output), `VERIFICATION-PHASE-PREP.md`, `intent-alignment-verdict.md` +
`intent-alignment-checklist.md`, `SURFACE-AREA-AND-PROOF.md`,
`ontology-workflow-before-after.md`, `replacement-readiness-audit.md`, and
`2026-06-13-ontology-rebuild-live-verify-results.md`.

---

## Problem Statement

We rebuilt the ontology system across 20 slices and live-verified the
LLM-bearing pieces against a real model. The substrate, the fused retrieval
(BFS + embedding + ColBERT + lexical), and the discover/learn/maintain/access
lifecycle are sound and net-better than before. But two things block declaring
victory and retiring the old system:

1. **The new system does not yet fully deliver the stated goal.** The goal is:
   *take ANY source (csv, sql, text, …) and discover a general ontology usable
   for any retrieval; auto-find the fields that need embedding (embedding model
   or ColBERT); use those embeddings + BFS + ColBERT late-interaction to
   discover, learn, maintain, and access knowledge.* An intent-alignment review
   against the three grills + `ARCHITECTURE-ONTOLOGY.md` found the core intact
   but three real gaps in the NEW path:
   - **P2:** auto-detect-embeddable-fields exists in the codebase but is NOT
     wired into the new builder — its embed/index stages default to skip, so a
     graph built by the new path gets no automatic embedding/ColBERT field
     selection.
   - **P1:** the new builder can only ingest TTL + inline + RLM-over-text. It has
     NO path to ingest large structured sources (SQLite, CSV, Excel) — the very
     "any source" the goal names.
   - **Axioms:** discovery extracts axioms but they are dropped on ingest.
2. **"Better than the old system" is asserted, not measured.** There is no
   apples-to-apples comparison against what the old system actually produced.
   The extraction bench (G2) has no old-system baseline.

Until these close and a fair comparison is run, we cannot honestly say the new
system can replace the old one for real, source-driven knowledge work.

## Solution

A verification phase that closes the gating gaps and then runs an unbiased,
adversarial head-to-head on a real, known graph — the BRYC Louisiana
career/program recommendation graph (built from CIP↔SOC, IPEDS, O*NET, LA-OEWS
wages, PSEO) — comparing both the GRAPH and the EXPLORATION output, per vertical,
on the actual information returned.

Sequenced to manage risk and get early signal:

- **M1 — early read / brownfield proof.** Close P2 (auto-embed) and ingest the
  EXISTING production graph (TTL) into the new substrate; run the exploration
  probes. Proves "bring your own graph, we improve + extend it" and gives an
  early quality read with no new ingestion work.
- **M2 — close the gating features (as TDD slices + live verify).** P2
  auto-embed (also M1); P1 format-aware RLM-controlled source ingestion; axiom
  ingest. These are the work that makes the new builder able to consume the 5
  official sources and deliver Pillar 1.
- **M3 — official head-to-head.** Build the same graph from the same official
  sources with the old builder (baseline A1) and the new builder (B); compare
  against A1, the real production graph (A2), and the old explorers re-run live.

The verdict is judged qualitatively, per vertical, on verbatim returned info, by
both the user and the model — backed by an LLM-judge rubric and graph-structure
stats — hunting adversarially for any place the new system is WORSE.

---

## User Stories

1. As a **greenfield builder**, I want to point the system at raw official
   sources (SQLite, CSV, Excel, text) and get a general ontology, so that I
   don't have to hand-assemble or pre-enrich the data first.
2. As a **structured-source consumer**, I want the system to ingest a SQLite DB
   (e.g. IPEDS, 60+ tables) by exploring its schema/tables/foreign-keys and
   sampling rows, so that I never have to fit a 72 MB database into an LLM
   context.
3. As a **structured-source consumer**, I want CSV crosswalks (e.g. CIP↔SOC) to
   become real relationship edges, not isolated concept lists, so that the graph
   is connected across sources.
4. As a **structured-source consumer**, I want Excel sources (e.g. O*NET v30.1,
   PSEO) ingested natively (by sheet/column sampling), so that I don't have to
   pre-convert them by hand.
5. As a **builder**, I want the system to auto-detect which fields are worth
   embedding and to embed/ColBERT-index them automatically, so that the graph is
   semantically searchable by default without me wiring embeddings.
6. As an **agent/RLM explorer**, I want format-aware source-access tools so I can
   CONTROL how I explore each source — a CSV differently from a SQL DB
   differently from free text — so that extraction fits the source's shape.
7. As an **agent/RLM explorer**, I want to query the graph with fused BFS +
   embedding + ColBERT + lexical retrieval (scoped), so that I find the right
   concepts whether or not I already know a URI.
8. As a **brownfield / bring-your-own-graph consumer**, I want to ingest an
   existing graph (TTL) into the new substrate and have it improved + extendable
   with additional sources, so that I'm not forced to rebuild from scratch.
9. As an **ontology maintainer**, I want discovered axioms (disjointness,
   characteristics, hierarchy) to actually land in the graph, so that the
   representation the discovery produced isn't silently dropped.
10. As an **ontology maintainer**, I want the build gated on a competency-
    question pass-rate and on round-trip fidelity, so that "the build worked" is
    a real, testable claim.
11. As a **career/program explorer** (the BRYC use case), I want to explore the
    graph by vertical — career, financial, outcome, academic, preference — and
    get recommendations with the connections + reasoning behind them, so that I
    can trust and act on them.
12. As the **verification reviewer**, I want a per-vertical, side-by-side
    document showing the ACTUAL info each system returns (recommendations,
    connections followed, reasoning, evidence), so that I can judge quality
    myself, not just read a score.
13. As the **verification reviewer**, I want the comparison run against the
    STRONGEST honest version of the old system (not a strawman), so that beating
    it is meaningful.
14. As the **verification reviewer**, I want the old explorers re-run LIVE under
    the same conditions/probes, so that the baseline isn't stale or cherry-picked.
15. As the **verification reviewer**, I want an explicit adversarial pass that
    hunts for where the NEW system is WORSE, so that the verdict is honest.
16. As a **decision-maker**, I want a clear "can it replace the old system?"
    verdict backed by evidence per dimension, so that I can approve a cutover (or
    not) with eyes open.
17. As **ORC itself** (self-improving builder), I want the format-aware source
    tools and auto-embed to feed the same event-sourced substrate + R-Inject
    boundary, so that source-driven discovery participates in the existing
    learning loop without new opt-in surfaces.
18. As a **builder**, I want the deterministic skeleton to keep owning the
    contracts (parse→normalize→dedup→validate→embed→index→exit) while the LLM
    does the discovery inside it, so that builds stay reproducible, gateable, and
    debuggable even though the knowledge work is LLM-driven.
19. As a **builder**, I want a fair embedding baseline (same model + ColBERT
    config across old and new) in the comparison, so that retrieval differences
    reflect graph quality, not embedding choice.
20. As a **downstream consumer**, I want the new builder to ingest the 5 official
    Louisiana sources end-to-end and produce a connected program↔CIP↔SOC↔earnings
    graph, so that the "any source → general ontology" promise is demonstrated,
    not assumed.

## Implementation Decisions

Organized by module. The deterministic skeleton remains the orchestration spine;
the LLM/RLM does the knowledge work inside the stages (discovery, dedup judge, CQ
judge). "Deterministic" = the pipeline contracts, NOT the absence of LLMs.

### M-P2 — Auto-embed / ColBERT field detection in the skeleton
- The skeleton's embed + index stages must, by default, DETECT embeddable fields
  and embed + ColBERT-index them — instead of skipping. The detection capability
  already exists (a heuristic schema scan and an LLM-data-sample analyzer, plus
  the old builder's auto-detect-colbert-fields path); the work is wiring it into
  the embed/index stages so a graph built by the new path is semantically
  searchable by default.
- Caller may still override the embed/index fns; the default becomes
  detect-then-embed rather than skip.
- Gates M1 (Mode A needs embeddings to explore the ingested graph).

### M-P1 — Format-aware, RLM-controlled source ingestion (ADR)
- **Decision (ADR-grade, hard to reverse):** the new system gains per-format
  SOURCE-ACCESS TOOLS granted to the discovery RLM, so the RLM explores each
  source by its shape WITHOUT loading it, designs the extraction, and feeds the
  one new skeleton. This mirrors the S19 graph-tools pattern (tools to explore a
  graph without loading it), now applied to SOURCES. Chosen over (a) reusing the
  old sheets on the old pipeline, (b) deterministic per-format adapters with only
  RLM enrichment, (c) a bespoke pre-normalization ETL.
- **The tool set (decision-encoding shape):**
  - csv: `peek-columns`, `sample-rows`, `profile-column`
  - sql/sqlite: `list-tables`, `table-schema`, `foreign-keys`, `sample-rows`, `query`
  - excel: `list-sheets`, `sheet-columns`, `sample-rows`
  - text: `read` (existing)
- The old `csv_ontology` / `sql_ontology` extraction KNOWLEDGE (schema→classes,
  columns→properties, foreign-keys→relationships, rows→individuals) is ported
  into the tools' internals — reuse, not rewrite.
- The skeleton's parse stage gains a route from "raw structured source" → this
  RLM-controlled ingestion (today it handles only TTL + inline). Output flows
  through the existing `:inline-concepts` / `:inline-relationships` seam the
  skeleton already supports.
- Scale constraint: tools sample/query; they never dump a whole source into the
  model context.
- This is the gating build for Pillar 1 (any source → general ontology).

### M-Axioms — Discovery axiom ingest
- The discovery→ingest adapter currently records discovered axiom-drafts as
  "skipped". It must instead route axiom-drafts to the S07 axiom commands
  (disjointness, characteristics, sub-property, chain), applying the same
  JSON-string→keyword coercion discipline already used for concept scope and
  relationship confidence-class (the live-verify ingest finding).

### M-Compare — Head-to-head harness
- **Graph A1:** the old ORC `main` evolutionary builder at its strongest honest
  config — embeddings ON, the crosswalk's CIP↔SOC extracted as explicit edges,
  IPEDS foreign-key extraction — run on the 5 official sources via a minimal
  build harness on a `main` worktree; the artifact is saved.
- **Graph A2:** the real production BRYC graph (the cached graph in
  daryls-area51), used as a "what we actually had" reference bar. Its
  pre-computed embeddings can't be controlled — noted.
- **Graph B:** the new builder on the SAME 5 official sources.
- **Bar:** B ≥ A on every dimension AND strictly > on the rebuild-target
  dimensions (cross-source connections, property/representation depth,
  grounding/evidence, retrieval recall); no regression on the old system's strong
  cases. B must beat A1 decisively and at least match A2.
- **Graph diff:** structure stats — nodes, edges by type, cross-source links,
  properties per concept, coverage — A1 vs A2 vs B.
- **Source set (locked):** IPEDS `output.db` (SQLite), `cip_soc_crosswalk.csv`,
  O*NET `db_30_1_excel` (Excel), LA-OEWS `louisiana_occupation_wages.csv`, PSEO
  `pseo_la.xlsx`. The hand-made `louisiana_programs_with_embeddings.csv` is
  EXCLUDED — each builder assembles its own program set + embeddings (the auto-
  embed test). Scholarships + TOPS deferred.

### M-Explore — Exploration harness
- **Old side:** stand up daryls-area51's 5 bespoke explorers (career / financial
  / outcome / academic / preference; old ORC SHA) over graph A2 and re-run them
  LIVE on the probe set — fresh, same conditions.
- **New side:** the new system's GENERAL RLM exploration (S19 graph tools +
  recursive-RLM) over graph B, framed in the SAME 5 verticals on the SAME
  profiles. (A deliberately high bar: general new exploration vs hand-tuned
  bespoke explorers.)
- **Probes:** the 3 recorded student profiles (Trinity / Aminata / Reagan) +
  adversarial probes — career differentiation across profiles, SOC→CIP→program
  multi-hop incl. crosswalk-only paths, financial/wage grounding, HBCU
  sensitivity, apprenticeship-vs-degree, hard-filter correctness.
- **Fairness:** A1 and B embed with the same model + ColBERT config; same probes
  + same vertical framings on both sides; outputs captured verbatim.

## Testing Decisions

- **Good tests** verify behavior through public interfaces and survive refactors
  — the same posture as the 20-slice suite. Each gap (P2, P1, axioms) ships as
  TDD vertical slices (test → implementation), exactly like S01–S21.
- **Live verification is mandatory before "done"** — real Grain event store, real
  LLM (gemini-3-flash-preview default), real ColBERT/embeddings where involved.
  Synthetic green is the floor. (Per the recurring live-verify discipline.)
- **Seams:**
  - P2 — test through the skeleton build result: a build over a fixture with
    embeddable fields produces embeddings + a ColBERT index, and those fields
    become retrievable via hybrid-search. Prior art: the S13/S17 skeleton tests
    + the existing embedding/field-analyzer interface.
  - P1 — test through the discovery/skeleton public entry: given a CSV / SQLite /
    Excel fixture, the RLM (with source tools) produces concept/relationship
    drafts that ingest into a connected graph. Adversarial: a tool must SAMPLE,
    never dump (assert no full-source load). Prior art: the S18 discovery tests +
    the S19 sandbox-tools tests (the tool-isolation + docstring-quality pattern
    transfers directly to source tools).
  - Axioms — test through `compile-discovery-source!`: discovered axiom-drafts
    land as S07 axiom events (not skipped); string→keyword coercion covered.
    Prior art: the S18 scope/confidence-class coercion tests.
- **The comparison IS an acceptance test, judged adversarially:** the dual
  baseline (A1 + A2) vs B, per-vertical qualitative side-by-side on verbatim
  outputs (primary), an LLM-judge rubric pass over the dimensions, and graph-
  structure stats. The pass condition is the bar above; the review explicitly
  hunts for regressions.
- **Hard gates carried in:** G1 (TTL round-trip triple-set equivalence) and G2
  (extraction bench). This comparison supplies the old-sheets baseline G2 was
  missing — graph A1 is that baseline.

## Out of Scope

- The BRYC PRODUCT flow (the recommendation app, student UX, scoring formulas).
  We compare graph-to-graph + exploration-output only.
- Scholarships (Career OneStop) and TOPS coverage — deferred for now.
- Retiring / deleting the old evolutionary builder + the 5 sheets — they keep
  coexisting; cutover is a separate decision after this comparison.
- A SPARQL endpoint, an embedded DL reasoner, and the other NEXT-tail items from
  the rebuild PRD.
- Fixing the pre-existing `repl_researcher_test` drift is tracked but not gating
  this PRD (it's the generic executor, not ontology code).

## Further Notes

- **Pivotal finding (verify-without-bias):** the old BRYC graph was NOT built by
  the Clojure ORC evolutionary builder — a Python DSPy pipeline
  (`CSVOntologyBuilder.run_with_enrichment`) built the TTL; the Clojure side
  loaded + augmented + explored it. That is why the baseline must be chosen
  carefully (graph A1 = ORC main's general builder at its strongest honest
  config; A2 = the real production artifact). Beating a naive strawman would
  prove nothing.
- **Framing:** "deterministic skeleton" is the orchestration spine, NOT an
  LLM-free pipeline. The ontology is still DISCOVERED BY LLMs (recursive-RLM
  discovery + LLM dedup/CQ judges) inside that skeleton. Verification covers BOTH
  the deterministic contracts AND the LLM-discovery quality.
- **Cross-cutting invariants:** events-first (all writes via commands → schema-
  validated events; the graph is a cached projection); recursive-only RLM;
  descriptions self-contained; no hardcoded phrase matching as a quality gate;
  gemini-3-flash-preview default; live runs mandatory before "done".
- **Live-verified to date (carried context):** S12 dedup 12/12, S15 CQ runner
  18/18 adversarial (open-world), S18 discovery end-to-end → skeleton build
  `:complete`, S19 tools converge correctly. Full regression green except the
  pre-existing generic `repl_researcher_test` drift.
- This PRD is amendable; `/to-issues` (local slice files under
  `docs/build-timeline/issues/`) is the intended next step on the user's go.
