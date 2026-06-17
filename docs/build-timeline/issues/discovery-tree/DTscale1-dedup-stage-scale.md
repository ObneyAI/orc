# DTscale-1 — Dedup-stage scale: LSH blocking + pure pre-filter + project-once

**Type:** AFK · **Parent:** `docs/build-timeline/prd/2026-06-16-discovery-behavior-tree-redesign.md` · **Prototype:** NO (root cause already characterized)
**Surfaced by:** the DT1 end-to-end live-verify — a real, captured 2.5-hour hot loop (thread dump) at thousands of concepts. GATES every real-scale build (DT7, DT10) + the unbounded DT1 run.

## Root cause (from a live thread dump — not hypothesis)
At thousands of concepts the dedup stage hot-loops at >150% CPU. Two compounding faults:
1. `candidate-pairs` (deterministic_skeleton) uses a DELIBERATELY-COARSE token-overlap blocking (its own docstring: "LSH from S12 is a future optimization"). For thousands of similar-labeled concepts this enumerates a near-O(n^2) pair set.
2. `dedup-stage` dispatches an `:ontology/run-dedup-cascade` COMMAND per pair, and EACH command re-projects `:ontology/axioms` (a full event-store scan). The cheap blocking tiers (T5 type, T7 jaccard) live INSIDE that per-pair command — they cut LLM calls but NOT the per-pair command + projection cost. Total: O(pairs x events).

## What to build (root-cause fix; no bandaid)
1. **Real LSH/MinHash blocking at pair generation** — bucket concepts by MinHash signature bands; enumerate candidate pairs ONLY within shared buckets, so the pair set is pruned from O(n^2) to the genuinely-similar neighborhoods. Replace the coarse all-token-overlap enumeration. (The dedup_cascade core already has the jaccard/shingle machinery to build on.)
2. **Pure cheap pre-filter at the stage** — run the deterministic blocking tiers (T5 type-mismatch -> :distinct; T7 jaccard < threshold -> :skip) as a PURE function over the blocked pairs, deciding the vast majority with NO command, NO projection, NO events. Expose a pure pre-filter fn from dedup_cascade (the tier logic exists; lift the cheap tiers out of the command path).
3. **Project once, not per-pair** — hoist the `:ontology/axioms` projection (and any other per-pair-constant read-model state) into `dedup-stage`; project ONCE and pass it into the cascade. Only pairs that SURVIVE the pure pre-filter dispatch the full `run-dedup-cascade` command (LLM tier + event emission), using the shared projection.

Net: O(pairs x project) -> O(n x blocking) + O(survivors x full-cascade-with-shared-state).

## Also confirm (the rest of the spine at scale)
While verifying at scale, CONFIRM the other deterministic-spine stages survive thousands of concepts (embed = one `:ontology/concept-embedded` event per concept; ColBERT index per V16's scaled timeout). If either is a second wall, root-cause + note it (a follow-up is fine if dedup is the dominant fix).

## Acceptance criteria
- [ ] Dedup-stage over a REAL thousands-of-concept graph completes in reasonable wall-time (minutes, not hours) — no hot loop; verified live.
- [ ] LSH/MinHash blocking prunes the candidate-pair set to similar neighborhoods (pair count is sub-quadratic in concept count) — asserted on a real graph.
- [ ] The cheap blocking tiers run as a PURE pre-filter (no command/projection/events for skipped+distinct pairs); only survivors hit the full cascade command.
- [ ] The axioms projection happens ONCE per stage, not per pair.
- [ ] Dedup VERDICTS are unchanged for genuine candidates (the fix is performance, not behavior) — the S12 dedup suite stays green; verdicts on a known fixture match pre-fix.
- [ ] Same Grain discipline: writes via commands -> events; no bare appends.
- [ ] Live verify: the dedup stage over the full crosswalk-extraction graph (thousands of concepts) completes; captured (concept count, pair count pre/post blocking, wall-time, verdict counts). This is the scale proof.

## Blocked by
None (touches deterministic_skeleton dedup-stage/candidate-pairs + dedup_cascade + the run-dedup-cascade command). Gates the unbounded DT1 end-to-end run + DT7/DT10.

## Cross-references
PRD M5/M7 + the spine; S12 dedup_cascade (tier logic + jaccard); the DT1 thread dump (commands.clj:1474 run-dedup-cascade -> rmp/project per pair).

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
