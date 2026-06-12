# S15 — CQ evaluation runner (judge-based)

**Type:** AFK · **Phase:** 2 · **PRD module:** M7 · **Stories:** 2, 16, 21

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

The acceptance-test half of the ORSD contract: each competency question
in an ontology's stored spec (S14) is evaluated against the built graph,
producing per-CQ result events and a pass-rate-over-time health metric.

- **Evaluation flow per CQ:** retrieval assembles evidence (scoped
  hybrid-search + BFS neighborhood via S19's tools) → an LLM judge
  scores answerability over that evidence. Result event shape (from the
  grill): `{:cq-index :answerable? :confidence :evidence-uris :gaps
  :evaluated-at}` — `:gaps` names what's missing when unanswerable
  (actionable for the next grow cycle).
- **Negation posture (three layers, locked round 3):** the judge is the
  closed-world evaluator over the fetched evidence set (absence in
  bounded evidence is meaningful and the basis is auditable via
  `:evidence-uris`); the deterministic helpers from S19 (`exists?`,
  `absent-in-graph?`, `filter-by-label-pattern`) handle simple cases
  without a judge; export-to-triplestore remains the documented escape
  hatch for heavy workloads. We do NOT rebuild SPARQL.
- **Health metric:** a projection over CQ-result events yields pass-rate
  history per ontology; re-running evaluation after a grow cycle appends
  to the series.
- Runs on demand (command) and as a post-build step when a spec with CQs
  exists.

## Acceptance criteria

- [ ] Per-CQ result events carry all fields; the pass-rate projection
      aggregates correctly across evaluations over time
- [ ] An answerable CQ over a seeded graph scores answerable with
      evidence URIs that ACTUALLY support the answer — adversarially
      audited (an evidence set that doesn't entail the answer must not
      pass; test with a deliberately misleading neighborhood)
- [ ] An unanswerable CQ produces `:answerable? false` with concrete
      `:gaps` naming the missing knowledge — not generic filler
- [ ] A negation-shaped CQ ("which X have no Y") evaluates correctly via
      the judge over bounded evidence (positive case: correctly absent;
      adversarial case: Y present but outside the fetched neighborhood —
      the evaluation must widen or report the boundary, never silently
      answer wrong)
- [ ] Grow-cycle re-evaluation appends to history; improvement after
      adding the missing source is visible in the pass-rate series
- [ ] Live verify (mandatory — judge quality is the product): real LLM
      judge over a real built graph for ≥5 CQs spanning lookup,
      aggregation-flavored, and negation shapes; every verdict + evidence
      set captured and adversarially reviewed by hand

## Blocked by

- S14 (the stored CQs)
- S19 (retrieval tools + negation helpers the runner uses)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
