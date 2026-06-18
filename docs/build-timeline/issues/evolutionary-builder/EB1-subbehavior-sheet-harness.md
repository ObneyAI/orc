# EB1 — Subbehavior-sheet harness + :delegate composition seam (tracer bullet)

**Type:** AFK · **Prototype:** YES · **Parent:** `docs/build-timeline/prd/2026-06-17-general-purpose-evolutionary-ontology-builder.md`

## What to build
The foundational tracer: prove that a SUBBEHAVIOR can be built as its own composed
ORC sheet (via the DSL + create-sheet/build-workflow!), registered, and invoked
from a CENTRAL tree via `:delegate` with mapped `:reads`/`:writes` (real child
tick, isolated blackboard, lineage). Build ONE thin subbehavior sheet (a trivial
`:llm`-or-`:code` body that echoes a contract) + a thin central tree that
`:delegate`s to it end-to-end, and prove: the child tick runs, mapped `:reads`
flow in, the subbehavior's `:writes` flow back to the parent blackboard, and the
contract is honored. Establish the subbehavior-sheet REGISTRY pattern (how a
subbehavior is named/versioned/looked-up for delegation).

## Acceptance criteria
- [ ] A subbehavior sheet is built via the DSL + registered; a central tree `:delegate`s to it; the child tick runs against a REAL Grain store and the contract round-trips (mapped reads in, writes back).
- [ ] The subbehavior is a first-class, independently-runnable sheet (testable via its `:reads`/`:writes` contract in isolation).
- [ ] The registry/lookup pattern for delegatable subbehaviors is established + documented.
- [ ] Any `:llm` body in the harness writes `:reasoning` first (discipline 13).
- [ ] Live verify: the delegate round-trip on real Grain; captured. VERIFY-NOT-ASSUME: record the child-tick delegation overhead (timing) so EB12 can judge it at scale.

## Blocked by
None (foundational).

## Handoff focus
RLM-GUIDE + the node palette (`:delegate` semantics in todo_processors `execute-delegate-node`; the DSL `dsl/delegate`); build-workflow!/create-sheet; how a sheet is registered + executed (runtime/execute child tick); the DT1 scaffold (what's being replaced by real composition).

## Prototype (YES)
Novel seam: prove delegate-as-composition + the subbehavior-sheet registry round-trips on real Grain before TDD.

## Cross-references
PRD M-B (composition/delegation). Supersedes DT1's hand-rolled orchestration.

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
