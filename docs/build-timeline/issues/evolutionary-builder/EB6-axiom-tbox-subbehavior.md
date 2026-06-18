# EB6 — Axiom/TBox subbehavior sheet (representation depth)

**Type:** AFK · **Prototype:** WORTH · **Parent:** `docs/build-timeline/prd/2026-06-17-general-purpose-evolutionary-ontology-builder.md`

## What to build
The Axiom/TBox subbehavior as a delegatable sheet (MINT — a genuine gap). It emits
real TBox axioms from the EB3 candidate-axioms + the extracted graph: disjointness,
property characteristics (functional/transitive/etc.), closure axioms (when a
source enumerates a set completely), and subClassOf / subPropertyOf / domain /
range. Reuse the S07 axiom commands. Closes the still-open `:axioms-skipped` drop.
This makes attributes/features FIRST-CLASS typed properties that can themselves
relate (the TBox depth `detect-schema-extensions` never reached).

## Acceptance criteria
- [ ] Axiom/TBox, delegated the candidate axioms + graph, emits real TBox axioms via the S07 commands (events land — read the projection back); NOT recorded `:axioms-skipped`.
- [ ] Covers disjointness, property characteristics, closure, subClass/subProperty/domain/range — domain-agnostically (the axioms come from the runtime model, not hardcoded vocab).
- [ ] Any `:llm` reasoning writes `:reasoning` first (13); no hardcoded phrase matching (7/12).
- [ ] Live verify: real axioms emitted + landed for a real source; captured.

## Blocked by
EB1, EB3.

## Handoff focus
S07 axiom commands (assert-disjointness / property-characteristic / sub-property / chain); the V07 coercion precedent; the candidate-axioms output from EB3-Model; the `:axioms-skipped` drop this closes.

## Prototype (WORTH)
New behavior — prove real axioms (disjointness + a characteristic + subClass) are derived from a model-spec + graph and land via S07.

## Cross-references
PRD M-A(e), M-F(axioms). Closes the intent-alignment axiom drift.

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
