# EB3 — Model subbehavior sheet (entity model + grain + scope + embed-fields + candidate axioms)

**Type:** AFK · **Prototype:** WORTH · **Parent:** `docs/build-timeline/prd/2026-06-17-general-purpose-evolutionary-ontology-builder.md`

## What to build
The Model subbehavior as a delegatable sheet: a single focused `:llm` node
(NOT a repl-researcher) that reads goal × the EB2 profile and decides the model-
spec: entity types, URI-keying fields, grain strategy (`:canonical-row-filter` |
`:breakdown-as-entity`), scope filter, the embed-worthy FIELDS (P2), and CANDIDATE
TBox/axioms (feeds EB6). Re-house the proven DT3 grain/scope reasoning; ADD the
embed-fields + candidate-axioms outputs.

## Acceptance criteria
- [ ] Model sheet, delegated goal + a real profile, emits the model-spec incl. grain-strategy + scope-filter (the V17/V20 over-extraction fix) + embed-fields + candidate TBox/axioms.
- [ ] It is ONE `:llm` node (think-before-emit: `:reasoning` first, discipline 13) — not a recursive session; F3 does not apply.
- [ ] Reads the profile TOLERANTLY (value-shape variance). Domain-agnostic (12).
- [ ] Independently testable on a captured-real profile; live verify (grain+scope on a breakdown-heavy source); captured.

## Blocked by
EB1, EB2.

## Handoff focus
DT3 grain/scope; the model-spec contract; the V17/V20 over-extraction lesson; the embed-field + candidate-axiom additions (what EB7/EB6 consume); profile value-shape tolerance.

## Prototype (WORTH)
Prove grain+scope+embed-fields+candidate-axioms on a real profile that previously over-extracted.

## Cross-references
PRD M-A(b), M-F(P2, axioms). Re-houses DT3.

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
