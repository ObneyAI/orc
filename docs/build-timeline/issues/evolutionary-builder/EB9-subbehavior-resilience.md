# EB9 — Subbehavior-internal resilience (fallback / condition / troubleshoot)

**Type:** AFK · **Prototype:** WORTH · **Parent:** `docs/build-timeline/prd/2026-06-17-general-purpose-evolutionary-ontology-builder.md`

## What to build
A reusable RESILIENCE pattern composed into each subbehavior sheet so a subbehavior
self-corrects or fails CLEANLY WITH A DIAGNOSIS before continuing down its tree or
returning: `:fallback` (try a primary/cheap path → fall back to a robust one),
`:condition`/`:llm-condition` gates on intermediate state, and a reasoning/
troubleshoot node (compose Investigation = root-cause + Validation = check). Apply
it to EB2-EB8. `:fallback` works in hand-composed top-level sheets today; the
RLM-emitted `:fallback` (Phase-2) stays a tracked follow-up.

## Acceptance criteria
- [ ] A reusable resilience sub-tree (fallback + gate + troubleshoot) exists and is composed into each subbehavior (EB2-EB8).
- [ ] A subbehavior that hits a bad intermediate state TROUBLESHOOTS (root-causes) and either self-corrects via the fallback OR returns a clean failure WITH a diagnosis — verified (inject a failure → it does not poison downstream).
- [ ] Investigation + Validation behaviors reused (no fork). Any `:llm` reasoning writes `:reasoning` first (13).
- [ ] Live verify: a subbehavior recovers via fallback on a real induced failure, and fails-with-diagnosis when it cannot; captured.

## Blocked by
EB2-EB8 (the subbehaviors it hardens).

## Handoff focus
`:fallback`/`:condition`/`:llm-condition` semantics; Investigation + Validation corpus behaviors; the focused-failure-recovery pattern; the FallbackRecovery emit-ability caveat (`emit_tree_extensions_pending`).

## Prototype (WORTH)
Settle the reusable resilience sub-tree (fallback + troubleshoot) on one real induced subbehavior failure.

## Cross-references
PRD M-D.

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
