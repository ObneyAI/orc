# S16 — Extraction bench (G2 harness)

**Type:** **HITL** (expected-graph ground truth requires user sign-off) · **Phase:** 3 (gate) · **PRD module:** M8/Testing · **Stories:** 25

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

The G2 hard gate: a real-task extraction bench that scores any builder
path (old sheets, rebuilt skeleton+RLM, future variants) on
concept/relationship precision + recall against known-good expected
graphs — establishing the old sheets' baseline BEFORE the rebuild so the
new path's superiority is verified, not vibes.

- **Bench corpus:** representative sources per format (CSV, JSON, SQL,
  text — and post-S09, a TTL ingest case), each with a hand-authored
  expected graph (concepts, relationships, and where applicable axioms/
  quantities/sequences). Expected graphs are the GROUND TRUTH —
  **HITL: the user adversarially reviews and signs off every expected
  graph before it becomes the gate** (a wrong ground truth corrupts
  every future verdict).
- **Scoring:** concept precision/recall (URI-canonicalized matching with
  label-similarity tolerance, deterministic and documented),
  relationship precision/recall (endpoint+predicate triples), plus
  per-feature sub-scores where the source exercises them (units
  captured, sequences ordered, axioms found).
- **Baseline run:** the current sheets are scored first and the results
  committed as the baseline the rebuild must beat.
- **Runs are real:** live LLM + ColBERT, captured outputs (never
  hand-invented), reproducible invocation documented.

## Acceptance criteria

- [ ] Bench corpus covers all source formats with expected graphs
      **signed off by the user** (HITL checkpoint — every expected graph
      enumerated by path for review)
- [ ] Scoring is deterministic given a produced graph: same input →
      same scores; matching rules documented in the harness
- [ ] Adversarial harness test: a deliberately-wrong produced graph
      (missing concepts, hallucinated relationships, wrong endpoints)
      scores measurably worse — proving the metric actually detects the
      failure modes it claims to (the metric itself is reviewed for
      QUALITY, not just implemented)
- [ ] Old-sheet baseline scores produced from real runs and committed
      with full captured outputs
- [ ] One-command bench invocation per path; per-source score report
      with diffs against expected (root-cause-ready output: which
      concepts/relationships missed, not just numbers)

## Blocked by

None — can start immediately. (Prerequisite for S18; benefits from S04/
S05/S07 schema features for expected-graph richness, which can be added
incrementally as those land.)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
