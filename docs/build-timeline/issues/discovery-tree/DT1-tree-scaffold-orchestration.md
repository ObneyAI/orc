# DT1 — Discovery-tree scaffold + orchestration skeleton (tracer bullet)

**Type:** AFK · **Parent:** `docs/build-timeline/prd/2026-06-16-discovery-behavior-tree-redesign.md` · **Prototype:** YES

## What to build
The foundational tracer bullet: a two-level discovery behavior tree (graph-level
orchestration wrapping a per-source sub-tree) that runs, for ONE real source,
end-to-end: Profile -> Model -> Transform -> [deterministic full-extraction
apply-step, V20] -> `build!` -> read the CQ verdict. Nodes may be THIN at this
stage (minimal prompts / stub reasoning) — the point is to prove the
ORCHESTRATION: the hybrid fixed-core sequence runs, the inter-node blackboard
contract carries data between nodes (via the existing node-output /
node-input-profile drill-down primitives), the per-medium tool-leaf binds the
right specialist tools, the deterministic skeleton `build!` is invoked as an
intact sub-call, and its CQ verdict (`:status`/`:graph-health`/`:exit-criterion`)
is read back by the tree. Branch points (recovery, CQ-loop, greenfield-vs-
maintain, full-extract-vs-inline) exist as STUBS to be filled by later slices.

## Acceptance criteria
- [ ] A discovery tree runs Profile->Model->Transform->extract->build! for ONE real source end-to-end and returns a terminal result carrying the build! CQ verdict.
- [ ] The inter-node contract is honored: each node reads its predecessor's output from the blackboard and emits the PRD-specified shape; verified by reading the captured node outputs.
- [ ] `build!` is invoked unchanged (no skeleton edits) and its `:status`/`:graph-health` is surfaced on the tree result.
- [ ] The tree is itself a composable behavior-tree node (can be a node in a larger tree).
- [ ] Branch points exist as explicit stubs (named, no-op) so DT8/DT9 fill them without restructuring.
- [ ] Live verify on a real source (real LLM, real Grain) end-to-end; captured.

## Blocked by
None (foundational). Reuses V20 apply-step + `build!`.

## Prototype (YES)
Novel orchestration. Prove: the tree composes, nodes pass the contract via the blackboard, `build!` is invoked, and the CQ verdict is readable — before TDD hardening.

## Cross-references
PRD modules M1 (orchestration) + M7 (build!-CQ boundary). RLM-GUIDE (tree DSL, repl-researcher as a node, drill-down primitives). `run-discovery!` is what this ultimately replaces.

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
