# EB8 Handoff — Validate+CQ subbehavior (derive/persist grounded CQs + semantic gate; HITL)

Fresh-context brief for EB8 (`docs/build-timeline/issues/evolutionary-builder/EB8-validate-cq-subbehavior.md`).
Crafted post-merge from EB2's REAL profile contract + the REAL S14/S15/DT5
signatures. Work DIRECTLY on `feature/ontology-architecture` (NOT a worktree). DO NOT
COMMIT/PUSH — leave staged; the orchestrator `/inspect-orc`s then commits locally,
**and surfaces the derived CQs to the human for HITL review** (this is a HITL slice).
Implement via `/tdd`.

## The goal
The **Validate+CQ** subbehavior as a delegatable sheet (mirror EB4–EB7 registry
pattern via `orc-service.interface`). It (1) DERIVES competency questions from
goal × the source profile(s) — grounded, goal-anchored, AFTER profiling; (2)
PERSISTS them as the S14 ORSD spec (the gate spec `build!`'s S15 reads); (3) runs
the S15 CQ gate (SEMANTIC — retrieve-then-judge, not structural lints) → a verdict;
and (4) SURFACES the derived CQs for HITL review. Consumer-supplied CQs OVERRIDE the
derived ones. Re-houses DT5.

## Read first
1. `components/ontology/src/ai/obney/orc/ontology/core/survey_subbehavior.clj` — EB2's profile output shape (the EB8 input: goal + the source profile(s)). Plus the EB4–EB7 sheets for the `:code`/`:llm`-orchestration + C1 + boundary-correct pattern.
2. `components/ontology/src/ai/obney/orc/ontology/core/discovery_tree.clj`:`cq-node-prompt` (~L805) + the DT5 Requirements/CQ node (~L781) — the RE-HOUSE target: the focused `:llm` prompt that derives competency questions (a vector of NL question strings). DT5 derives from the GOAL; EB8 GROUNDS them in goal × profile (so the CQs fit what the sources actually contain — "after profiling"). `:reasoning` FIRST (#13). Only `:competency-questions` persists into the ORSD.
3. `components/ontology/src/ai/obney/orc/ontology/interface/schemas.clj` (~L135) — `ontology-spec-body` (the S14 ORSD: `:purpose :scope :intended-uses :competency-questions :natural-language-statements :non-functional`). The persist command is `:ontology/record-ontology-spec`.
4. `components/ontology/src/ai/obney/orc/ontology/interface.clj`: `get-ontology-spec` (~L187, read the persisted spec back) ; `evaluate-cqs!` (~L283 — the S15 CQ runner: per-CQ three-layer retrieve-then-judge → `:pass`/`:fail`/`:unknown` over closed-world evidence + the graph-health metric) ; `get-cq-evaluation-latest` (~L257, read the verdict back).

## What EB8 must build
A sheet `ontology-validate-cq/...@v1`. Reads `[:ontology-id :goal :profile]` (+ an
optional `:consumer-cqs` override) → writes `[:reasoning :competency-questions
:cq-verdict :graph-health]` (or similar). Node mix:
- **`:llm` DERIVE** — re-house `cq-node-prompt`, EXTENDED to ground the CQs in the goal × the EB2 profile (the entities/fields the sources actually hold), so the CQs are answerable-in-principle. `:reasoning` FIRST. When `:consumer-cqs` is supplied, they OVERRIDE (skip derivation, use the consumer's). C1: `:competency-questions` is a `[:vector :string]` write (structured).
- **`:code` PERSIST** — `:ontology/record-ontology-spec` with the ORSD body carrying the CQs (read back via `get-ontology-spec` — discipline 7).
- **`:code` GATE** — `evaluate-cqs!` (S15) → the verdict (`:pass`/`:fail`/`:unknown` per CQ + graph-health). Read back via `get-cq-evaluation-latest`. This is SEMANTIC validation (retrieve-then-judge), NOT hardcoded phrase matching (#7/#12). An `:unknown` is a first-class honest verdict (graph lacks the fact-kind), not a fallback.
- **HITL surface** — the derived CQs are a first-class output for human review (capture them verbatim; the orchestrator surfaces them).

## Do NOT
- Fork `cq-node-prompt` / `record-ontology-spec` / `evaluate-cqs!` (reuse). Use structural lints or hardcoded phrase matching AS the semantic validation (it must be CQ/retrieve-grounded). Hardcode CQs or vertical vocab (CQs come from goal × profile). Silently drop an `:unknown` (surface it). Touch EB1–EB7 or unrelated files. Commit/push. Create a worktree.

## Prototype (WORTH — do first)
On a REAL profile + goal: derive grounded, goal-anchored CQs; persist them as the
ORSD spec; read them back via `get-ontology-spec` (proving they're the gate spec).
Show a consumer-CQ override path. Capture the derived CQs verbatim (for HITL review).

## Verify
- `/tdd` red→green; sheet built + registered; delegated goal + profile; CQs derived + persisted (read back via `get-ontology-spec` — discipline 7); the gate verdict read back via `get-cq-evaluation-latest`.
- **LIVE verify:** on a real profile/graph, derived CQs + the S15 gate verdict (with real retrieve-then-judge). Capture verbatim (`docs/build-timeline/live-verify/EB8-validate-cq.md`): the derived CQs (for HITL review), the persisted ORSD, and the per-CQ verdicts + graph-health.
- **Gate hygiene:** the DERIVE (`:llm`) + the S15 GATE (retrieve-then-judge LLM + ColBERT/embeddings) are multi-second/integration → the LIVE test goes to `development/ontology-integration/...` (on-demand `:dev:test`). A fast hermetic test (structure/contract/reuse + persist→read-back over a real in-memory store + the consumer-override path, with derivation/judge stubbed) stays in the brick gate. Decide per measured runtime; report which.
- Green under BOTH `clj -M:poly test brick:ontology` AND `:dev:test` of the EB8 ns.
- JVM hygiene: bounded runs; 0 orphan THIS-repo JVMs after (exclude sibling worktrees — kill only your own by PID; ColBERT bridge by its PID).

## Report back (raw data)
Sheet + node design; the prototype result (derived CQs verbatim + persisted + read back + override); the LIVE capture (the derived CQs **surfaced for HITL review**, the ORSD, per-CQ verdicts + graph-health); which lane the test went in; dual-runner totals; "0 orphan THIS-repo JVMs"; every file changed by path; honest negatives. DO NOT COMMIT/PUSH. Binding Core Disciplines block 1–13 (in the EB8 issue) in force verbatim.
