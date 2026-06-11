# C-3: Automated principle extraction + judges audit

## Parent

PRD: [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md) — see "Out of Scope" → "C-3 sub-grill must decide" + the C-3 row in "Proposed Slicing."

## Status: STUB — sub-grill required

This issue is a placeholder. Architectural decisions are open AND there is a substantial adversarial audit component that needs human judgment before implementation. A `/grill-me` sub-grill must resolve these before C-3 can be picked up. This is HITL by nature, not just by current scoping.

## What to build (sketch)

Two intertwined deliverables:

### 1. Automated principle extraction

Wire RLM `:tree-results` entries through `evaluation/evaluate-trace` to produce judge scores, then through an adapted GEPA proposer to extract a *principle* (in the C-1 storage shape) for each tree run that contains useful learning signal. The principles get written via the existing `:ontology/record-tree-strength`/`record-tree-weakness` commands — same shape C-1 already validated with hand-authored seeds.

GEPA's existing infrastructure (`gepa/core/reflection.clj` `ReflectiveExample` builder, `gepa/core/proposer.clj` `propose-new-instruction` / `propose-mutation`) is the reuse candidate. But GEPA was designed for static-workflow instruction optimization; whether its proposer's prompt strategy produces useful principles for RLM tree-design decisions is an OPEN QUESTION that the sub-grill must answer.

### 2. Adversarial judge audit (plan-doc issue 011)

The existing 4 rubric-based judges in `components/evaluation/` (Grounding, InstructionFollowing, Reasoning, Completeness) were designed to evaluate LLM-response *content* quality. RLM is about tree-*structure* quality. These two evaluation domains may diverge. The audit must answer:

- Do the existing judges produce signal useful for tree-structure decisions (concurrency, parallelism, aggregation, retry, fallback)?
- Or are they too output-content-focused to be useful for evaluating "did the model design a good tree?"
- If the latter, do we need new STRUCTURAL judges (tree-shape quality, efficiency, redundancy detection)?

This audit work is plan-doc issue 011 ("Verify judge feedback quality (HITL)"). It belongs in C-3 because that's where judges actually get wired to RLM for the first time — without this audit, C-3 ships automation against an unvalidated quality target.

## Open decisions (require sub-grill)

The PRD's "Open decisions deferred to sub-grills" section enumerates:

1. **GEPA reuse strategy:**
   - Use `propose-new-instruction` verbatim, treating each `:tree-results` entry like a GEPA training example?
   - Adapt GEPA's proposer prompt for *principle extraction* (more abstract than instruction optimization)?
   - Build an RLM-specific proposer (e.g., `rlm_proposer.clj`) reusing GEPA's reflection layer but with a custom proposer prompt?

2. **Judge audit findings (drives the next decision):**
   - Run the existing 4 judges against a sample of `:tree-results` entries (or representative trace data).
   - Inspect outputs — are they actionable for tree-design decisions or only response-content?
   - Decision: keep judges as-is, modify rubric prompts, or add new judges?

3. **Structural judge necessity:**
   - If the audit shows existing judges aren't enough, what structural judges do we need? Candidates: tree-shape quality, efficiency (wall-time vs theoretical-minimum), redundancy (duplicate sub-LLM calls), bounded-concurrency adherence.
   - What's the rubric shape for a structural judge? Score + dimensions, like the existing ones?

4. **When does reflection run:**
   - Per Phase 2 tree (every `emit-tree!` result)?
   - Per RLM session (only on `final!`)?
   - Threshold-triggered (only when score < threshold, only on `:partial`/`:failure`/`:timeout`)?
   - Hybrid (always score, only reflect when flagged)?

5. **Provenance / confidence for automated principles:**
   - C-1's hand-authored principles use `:confidence 1.0`. Automated ones should be lower — what algorithm computes confidence?
   - Do automated and hand-authored principles share the same `:strengths`/`:weaknesses` list, or are they tagged separately (`:provenance :hand` vs `:provenance :auto`)?
   - If a hand-authored principle and an automated one disagree for the same task class, which wins in retrieval?

6. **Live-verify scenario for C-3:**
   - Reuse C-1's 3-way comparison harness (does automated principle have the same effect as hand-authored)?
   - Or different — does automated principle SURVIVE multiple runs without drift, does it converge to useful principles, does it avoid noise?

## Acceptance criteria (high-level — refined in sub-grill)

The sub-grill must produce concrete acceptance criteria. Sketch:

- [ ] Existing 4 judges audited against RLM tree-design use case; findings documented; decision on modify-vs-add-new judges committed.
- [ ] Automated principle extraction wired to a subset of `:tree-results` entries (per the trigger rule chosen in sub-grill).
- [ ] Extracted principles land in the same `:strengths`/`:weaknesses` storage shape as C-1's hand-authored ones.
- [ ] Provenance and confidence semantics documented.
- [ ] Live-verify: automated extraction produces a principle that, when re-injected, moves model behavior in the way C-1's hand-authored principles did (or better).
- [ ] No regression in C-1's tests or live-verify.

## Critical project rules

1. **Infrastructure existence is not quality** — applies extra hard here. GEPA proposer EXISTS but was built for a different use case. The judges EXIST but were built for response evaluation. Both must be adversarially audited before relying on them.
2. **Mocks for dev iteration only; live runs required before declaring done.**
3. **Trace bugs to root cause.**
4. **Never truncate model-authored output back to the model.**
5. **Hand-authored principles from C-1 should not be silently overridden by automation** — explicit provenance and conflict-resolution rules required.

## Blocked by

- [C-1 — Principle storage + retrieval + injection](C-1-principle-storage-retrieval-injection.md)

C-1 must ship and live-verify that the principle shape is useful before C-3 automates generation. Otherwise C-3 ships automated principle generation into a shape that may need redesign — wasted work + downstream noise.

## Cross-references

- Parent PRD: [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md)
- GEPA reuse candidate: [`docs/GEPA-INTEGRATION-PLAN.md`](../../GEPA-INTEGRATION-PLAN.md)
- Existing judges: `components/evaluation/src/.../core/judges.clj` + `components/evaluation/src/.../core/rubrics.clj`
- Existing GEPA proposer: `components/gepa/src/.../core/proposer.clj`
- Existing GEPA reflection: `components/gepa/src/.../core/reflection.clj`
- FUTURE-VISION Phase 1 (GEPA Foundation): [`docs/FUTURE-VISION.md`](../../FUTURE-VISION.md) — Reflection Sheet → Instruction Proposal → Pareto frontier
- Plan-doc issue 011 source: this work was previously enumerated as the "Verify judge feedback quality (HITL)" issue in the RLM Phase 2 issue tracker
