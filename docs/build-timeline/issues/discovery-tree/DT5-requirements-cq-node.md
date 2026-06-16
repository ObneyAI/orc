# DT5 — Requirements / competency-question node (graph-level)

**Type:** HITL · **Parent:** `docs/build-timeline/prd/2026-06-16-discovery-behavior-tree-redesign.md` · **Prototype:** WORTH

## What to build
A graph-level node that, AFTER profiling all sources, derives competency
questions from goal X the profiles (grounded + goal-anchored), surfaces them for
HITL review/override, and persists them as the S14 ORSD spec that S15 judges.
Consumer-supplied CQs seed/override the derived set.

## Acceptance criteria
- [ ] Derives CQs only AFTER profiling (grounded in real profiles), goal-anchored (tests what the graph SHOULD answer, not just what was extracted).
- [ ] CQs persist as the S14 ORSD spec; S15 can judge against them; default gate pass-rate>=0.8 + unknown-rate<=0.3 (overridable).
- [ ] Derived CQs are surfaced for HITL review + consumer override/seed.
- [ ] Domain-agnostic: CQs come from the runtime goal, not hardcoded.
- [ ] Live verify: derived CQs on the real BRYC profiles are sane + reviewable; captured.

## Blocked by
DT2 (profiles).

## Prototype (WORTH)
CQ quality is load-bearing (it is the exit gate). Prove derived CQs are sane on real profiles before TDD.

## Cross-references
PRD M3; S14 ORSD store; S15 CQ runner.

## Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation)

1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — node/tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
8. Re-orchestration, not rewrite. Reuse the proven deterministic skeleton (build!) + capabilities (V06/V19 source tools, V20 apply-step, V18 integrity, S12 dedup, S03 alignment, S13 evidence, S21 hybrid retrieval, S14/S15 ORSD+CQ). Do not duplicate or fork them.
9. Adversarial qualitative verdict. Behaviors are judged on the ACTUAL output produced — actively hunt for where it is WRONG. "It ran" is not a pass; no false-better; surface honest negatives (e.g. unanswerable CQs) rather than masking them.
10. "Deterministic skeleton" ≠ LLM-free. The skeleton is the deterministic spine; the knowledge work (profile, model, transform, CQ/dedup judging) is done by LLMs at the nodes/stages. Verify BOTH the deterministic contracts AND the LLM-reasoning quality.
11. Standing ops rules: the real OpenRouter key is passed as a shell env var ONLY, never committed; never truncate model-authored output when capturing/comparing (pass verbatim); retrieval-facing descriptions are self-contained (no file paths / SHAs / slice names); HITL audit every changed/added file by path before any commit; branch = feature/ontology-architecture; one commit per slice; co-author `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
12. Domain/industry-agnostic. NO tuning toward the education/crosswalk example or any vertical (no baked-in CIP/SOC/OPEID knowledge or industry schema). The evolver's focus comes from the runtime goal/docs. Format/medium SPECIALISTS (CSV/SQL/Excel/text, each with their own tools + instructions + trees) ARE encouraged — improving a specialist's ergonomics is in scope; encoding a domain answer is not.
