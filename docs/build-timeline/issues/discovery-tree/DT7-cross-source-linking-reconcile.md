# DT7 — Cross-source linking / reconciliation (against current graph state)

**Type:** AFK · **Parent:** `docs/build-timeline/prd/2026-06-16-discovery-behavior-tree-redesign.md` · **Prototype:** WORTH

## What to build
A graph-level reconciliation pass that runs AFTER per-source extraction and links
across sources, using S03 alignment registry + S12 dedup cascade + S21
embeddings/ColBERT + V18 referential integrity. It is where "discover
ambiguities" lives.

CRITICAL SEAM: the stage reads EXISTING concepts/relationships from the
projection and reconciles against CURRENT GRAPH STATE — it MUST NOT assume an
empty graph. This is the single thing that makes the deferred maintain branch a
clean later addition (see the maintain handoff).

## Acceptance criteria
- [ ] Entities from different sources that refer to the same real thing merge (shared canonical id / dedup / alignment) — verified on real cross-source data.
- [ ] Ambiguities (near-variant identities) are surfaced (V18 + S12 similarity), not silently merged or dropped.
- [ ] The stage reads + reconciles against current graph state (NOT empty-graph) — verified by running it twice / against a pre-populated graph: the second pass reconciles rather than duplicates.
- [ ] Reuses S03/S12/S21/V18 (no fork).
- [ ] Live verify on the real BRYC sources: cross-source links present, 0 dangling, ambiguities surfaced; captured.

## Blocked by
DT4 (extraction produces drafts to reconcile).

## Prototype (WORTH)
Prove cross-source merge + ambiguity surfacing against EXISTING graph state on real sources.

## Cross-references
PRD M5; S03 alignment, S12 dedup, S21 retrieval, V18 integrity; the maintain handoff (`docs/build-timeline/handoff-plan/2026-06-16-DEFERRED-maintain-incremental-discovery-handoff.md`).

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
