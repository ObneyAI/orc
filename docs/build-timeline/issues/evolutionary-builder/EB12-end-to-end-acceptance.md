# EB12 — End-to-end acceptance on the BRYC 5 (verify-not-assume)

**Type:** HITL · **Prototype:** NO · **Parent:** `docs/build-timeline/prd/2026-06-17-general-purpose-evolutionary-ontology-builder.md`

## What to build
The acceptance run: the full central evolver over the 5 official BRYC sources via
the composed subbehaviors, gated by S15 CQ, captured for HITL review. This is where
the VERIFY-NOT-ASSUME items become explicit acceptance criteria.

The 5 sources: IPEDS `output.db` (SQLite), `cip_soc_crosswalk.csv`, O*NET
`db_30_1_excel` (Excel), `louisiana_occupation_wages.csv`, `pseo_la.xlsx` (Excel).

## Acceptance criteria
- [ ] Full real build over all 5 sources via the composed evolver reaches a terminal CQ verdict; captured verbatim.
- [ ] VERIFY-NOT-ASSUME: (a) the `:delegate`-per-subbehavior child-tick overhead at scale is measured + acceptable; (b) the P1 LLM-authored transform yields sane scoped counts AUTONOMOUSLY (no hand-correction); (c) P2 auto-embed actually fires (concepts semantically retrievable); (d) attribute-level reconciliation connects new attributes to existing ones.
- [ ] LA scale (not national raw dump); entities are NODES not edges-only; earnings/wages are queryable ATTRIBUTES; axioms landed; 0 dangling; cross-source links present.
- [ ] Real LLM + real embeddings/ColBERT + real Grain; no mocks; no false green; JVM hygiene (0 orphans).
- [ ] HITL: the user reviews the captured result + CQ verdict and signs off.

## Blocked by
EB1-EB11.

## Handoff focus
The 5 sources + paths; the verify-not-assume acceptance; the V09/V17/V20 capture pattern; the head-to-head (this graph becomes the new-side artifact once accepted).

## Prototype (NO)
This IS the acceptance run.

## Cross-references
PRD Testing Decisions + M-F verify-not-assume. The new-side artifact for the BRYC head-to-head.

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
