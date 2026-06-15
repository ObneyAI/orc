# V13 — Head-to-head report (per-vertical side-by-side + verdict)

**Type:** HITL · **Milestone:** M3 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

The capstone: a documented head-to-head that lets the user (and the model) judge,
qualitatively and adversarially, whether the new system is better. It combines
(1) the graph-structure stats (V10), (2) the per-vertical VERBATIM exploration
outputs old (V11) vs new (V12), side-by-side, and (3) an LLM-judge rubric pass —
into one reviewable report, ending in an evidence-backed verdict.

## Acceptance criteria

- [ ] PER-VERTICAL side-by-side (career / financial / outcome / academic /
      preference), per profile: old output (V11) next to new output (V12),
      VERBATIM, so the actual info + connections + reasoning are readable.
- [ ] Graph-layer table (V10) included: B vs A1 vs A2 on each dimension with
      regression flags.
- [ ] LLM-judge rubric pass scoring each dimension (coverage, cross-source
      connection richness, property/representation depth, recommendation
      relevance, reasoning/grounding, retrieval recall) for B vs A1 vs A2.
- [ ] Explicit ADVERSARIAL section: every place the NEW system is WORSE or
      thinner than old, named — with a root-cause + fix-or-accept note. (No
      "both completed" passing; no false-better.)
- [ ] The verdict applies the locked bar: B ≥ old on every dimension AND
      strictly > on the rebuild-target dimensions; beats A1 decisively; at least
      matches A2. Stated plainly: better / mixed / not-yet, with the load-bearing
      reasons.
- [ ] A "can it replace the old system?" recommendation backed by the evidence.
- [ ] HITL: the user reviews the per-vertical output + verdict and signs off.

## Blocked by

V10, V11, V12.

## Cross-references

- PRD modules M-Compare/M-Explore + the bar + rubric in
  `BRYC-COMPARISON-RUN-DESIGN.md`; updates `replacement-readiness-audit.md`'s
  verdict with measured evidence.

## Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation)

1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.

### Verification-phase additions (binding for this initiative)

8. No strawman / unbiased baseline. The old side (graph A1) always runs at its STRONGEST honest config (embeddings on, crosswalk CIP↔SOC extracted as edges, FK extraction). Never hobble or weaken the old system to make the new one look better — beating a weakened baseline proves nothing.
9. Adversarial qualitative verdict. The comparison is judged on the ACTUAL verbatim information returned, per vertical — actively hunt for where the NEW system is WORSE. "Both completed" is not a pass; no false-better.
10. "Deterministic skeleton" ≠ LLM-free. The ontology is DISCOVERED BY LLMs (recursive-RLM discovery + LLM dedup/CQ judges) inside the deterministic skeleton; verify BOTH the deterministic contracts AND the LLM-discovery quality.
11. Standing ops rules: the real OpenRouter key is passed as a shell env var ONLY, never committed; never truncate model-authored output when capturing/comparing (pass verbatim); retrieval-facing descriptions are self-contained (no file paths / SHAs / slice names); HITL audit every changed/added file by path before any commit; branch = feature/ontology-architecture; one commit per slice; co-author `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
