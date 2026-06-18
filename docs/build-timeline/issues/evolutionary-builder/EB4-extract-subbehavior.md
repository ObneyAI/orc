# EB4 — Extract subbehavior sheet (author + apply per-row transform)

**Type:** AFK · **Prototype:** SOFT · **Parent:** `docs/build-timeline/prd/2026-06-17-general-purpose-evolutionary-ontology-builder.md`

## What to build
The Extract subbehavior as a delegatable sheet composed of real nodes:
`:code` (sample real rows + key-shape) → `:llm` (author the per-row transform,
grounded in the real key shape, `:reasoning` first) → `:code` (the V20 apply-step
over the FULL source, per-row error counting). Re-house DT4 + the DT4-grounding
field-grounding fix. Honors the model-spec's grain + scope.

## Acceptance criteria
- [ ] Extract sheet, delegated a model-spec + source, authors a field-grounded transform and applies it over the FULL source → a sane scoped concept count (NOT a raw-row dump; nodes not edges; fields grounded — the V17/V20/DT4-grounding lessons), per-row errors counted, no abort.
- [ ] Transform-authoring is an `:llm` node (`:reasoning` first); sampling + apply are `:code` nodes; reuses the V20 apply-step (no fork).
- [ ] Independently testable; LIVE verify the AUTONOMOUS transform yields a sane scoped count with NO hand-correction (the P1 verify-not-assume criterion); captured.

## Blocked by
EB1, EB3.

## Handoff focus
DT4 + DT4-grounding (the real-row-key-shape surfacing); the V20 apply-step; the transform contract; the model-spec input.

## Prototype (SOFT)
Transform shape is V20-proven; light probe of the authoring node from a model-spec.

## Cross-references
PRD M-A(c), M-F(P1). Re-houses DT4 + DT4-grounding.

## Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation)

1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation for an LLM-node result. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue to its root with explicit debug text/logging; heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (REAL Grain event store, REAL LLM calls, REAL ColBERT/embeddings where involved) is mandatory before declaring done. No invented fixtures — node/tree/model outputs are captured from real runs. No false green — a passing fallback / degenerate / empty path is not proof (a build that "completed" with 0 concepts / dangling edges / a raw-row dump is a FAIL).
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces (a subbehavior via its :reads/:writes contract), never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; NO bare event-store appends; assert events LANDED by reading the projection back (not by trusting a return value); recursive-only RLM; no hardcoded phrase matching as quality gates.
8. Re-orchestration, not rewrite. Reuse the proven deterministic skeleton (build!) + capabilities (V06/V19 source tools, V20 apply-step, V18 integrity, S12 dedup, S03 alignment, S07 axioms, S13 evidence, S21 hybrid retrieval, S14/S15 ORSD+CQ) + the seed-corpus behaviors via :delegate. Do not duplicate or fork them.
9. Adversarial qualitative verdict. Judge the ACTUAL output produced — actively hunt for where it is WRONG. "It ran" is not a pass; no false-better; surface honest negatives (unanswerable CQs, under-coverage) rather than masking them.
10. "Deterministic skeleton" ≠ LLM-free. The skeleton owns the contracts; the subbehaviors do the knowledge work. Verify BOTH the deterministic contracts AND the LLM-reasoning quality.
11. Standing ops rules: the real OpenRouter key is a shell env var ONLY, never committed; never truncate model-authored output when capturing/comparing (verbatim); retrieval-facing descriptions are self-contained (no file paths / SHAs / slice names); HITL audit every changed/added file by path before any commit; branch = feature/ontology-architecture; one commit per slice; co-author `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; JVM hygiene — bounded live runs (future + deref timeout + System/exit), kill orphans by PID, confirm 0 this-repo orphan JVMs after.
12. Domain/industry-agnostic. NO tuning toward the education/crosswalk example or any vertical (no baked-in CIP/SOC/OPEID knowledge or industry schema). The evolver's focus comes from the runtime goal/docs. Format/medium SPECIALISTS (CSV/SQL/Excel/text, own tools + instructions) ARE encouraged; encoding a domain answer is not.
13. Every `:llm` node writes `:reasoning` FIRST in its `:writes` (chain-of-thought before any structured output — force think-before-emit). In CONCURRENT contexts (`:parallel`, `:map-each`) or where the reasoning must be inspected per-node, use a NODE-SCOPED reasoning key (e.g. `:<node>-reasoning`) so concurrent nodes do not trample one another's `:reasoning` on the shared blackboard.
