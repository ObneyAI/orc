# S12 — Dedup cascade + check-before-mint

**Type:** AFK · **Phase:** 2 · **PRD module:** M5 · **Stories:** 12

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

The tiered, cheapest-first dedup cascade as the substrate's canonical
compare-to-existing path — with hard correctness guards and
equivalence-kind verdicts:

1. **Tiers, in order:** exact normalization → entropy gate (skip
   low-information labels) → MinHash/LSH blocking → string-similarity
   verification → focused LLM merge/keep verdict ONLY for candidates in
   the ambiguity band. Type-based blocking (proven in the existing text
   pipeline) applies at every tier.
2. **Hard guards:** the disjointness KEEP-guard is the FIRST gate —
   concepts under disjoint classes (S07 axioms) never merge, no LLM call
   spent. The LLM verdict prompt carries the explicit rule: *differ in
   any number, negation, or entity → KEEP* — and numeric/negation
   differences are positive evidence for a `:different-from` record, not
   merely evidence against merging.
3. **Verdict outputs carry equivalence KIND** (S08): merge-as-same
   individual vs equivalent-class vs equivalent-property vs distinct.
   Cross-section matches record the equivalence into the alignment
   section instead of merging across sections.
4. **Co-occurrence recording:** concept-pair co-occurrence counts are
   emitted as events from day one (write-only for now — context-aware
   disambiguation activates in a later slice once data accumulates).
5. **Check-before-mint:** at graph-merge time, candidates are searched
   against the deployment's other ontology sections; on a hit, the
   equivalence is recorded (alignment section) and the existing canonical
   URI reused instead of minting a duplicate.

LLM calls in the cascade are ontology-mechanism functionality —
unconditional, budget-knobbed, NOT gated on the R-Inject opt-in.

## Acceptance criteria

- [ ] Adversarial merge fixtures: near-duplicates that MUST merge (case/
      whitespace/alias variants) and look-alikes that MUST NOT
      (number variants "Model 3"/"Model 30", negation pairs, distinct
      entities sharing surface forms, disjoint-class pairs) — every
      verdict asserted including its KIND
- [ ] Disjointness guard fires BEFORE any LLM call (instrument call
      counts; assert zero LLM invocations for disjoint pairs)
- [ ] Cheap tiers resolve cheap cases: instrumented test proving
      exact-norm/blocking handle their fixtures with zero LLM calls
      (cost discipline is part of QUALITY here)
- [ ] Cross-section duplicate: check-before-mint reuses the canonical
      URI + emits the equivalence event into the alignment section —
      and does NOT merge events across section streams
- [ ] Co-occurrence events emitted with correct pair counts
- [ ] Live verify: real multi-source build where the cascade's verdict
      log (explicit debug output per tier per candidate) is captured and
      adversarially reviewed — every merge and every KEEP justified

## Blocked by

- S07 (disjointness axioms for the guard)
- S08 (equivalence events for the verdicts)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
