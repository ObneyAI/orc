# EB11 — Maintain (evolutionary): new discoveries + new classes/attrs against existing graph

**Type:** AFK · **Prototype:** WORTH · **Parent:** `docs/build-timeline/prd/2026-06-17-general-purpose-evolutionary-ontology-builder.md`

## What to build
The Maintain branch (the front-of-tree `:condition` selects it when a graph
already exists): compose Survey/Model/Extract against the EXISTING graph (not from
scratch), evolve the TBox (introduce NEW taxonomy classes + NEW properties/
attributes + how they relate to existing classes/properties via EB6), and run
MULTI-GRANULARITY Reconcile (EB5) — connect NEW entities to existing entities AND
NEW attributes/features to existing entities' attributes/features — then re-gate CQs
(a new source may ADD CQs). Re-house DT9's branch from a stub into the real
evolutionary-maintain composition.

## Acceptance criteria
- [ ] Fed a NEW source against an EXISTING graph, Maintain makes new discoveries, INTRODUCES new classes + properties (TBox evolution lands via S07), and connects new entities AND new attributes to existing entities + attributes (multi-granularity reconcile).
- [ ] Idempotent: re-running an unchanged source reconciles, does NOT duplicate (the against-graph-state seam).
- [ ] Reuses Survey/Model/Extract + EB5 Reconcile + EB6 Axiom/TBox (no fork). Any `:llm` reasoning `:reasoning` first (13).
- [ ] Live verify: a real second source introduces a new class whose attribute connects to an existing entity's attribute; captured. (Full multi-source incremental at-scale verification is staged with EB12.)

## Blocked by
EB5, EB6, EB10.

## Handoff focus
DT9 greenfield/maintain branch; the EB5 multi-granularity reconcile + EB6 TBox-evolution; the maintain handoff (`docs/build-timeline/handoff-plan/2026-06-16-DEFERRED-maintain-incremental-discovery-handoff.md`); attributes-as-first-class.

## Prototype (WORTH)
Prove a new source introduces a new class + a new attribute that connects to an existing entity's attribute, against an existing graph.

## Cross-references
PRD M-E. Realizes the deferred maintain handoff.

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
