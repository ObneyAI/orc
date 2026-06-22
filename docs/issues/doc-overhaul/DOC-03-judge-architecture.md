## What to build

Create `docs/JUDGE-ARCHITECTURE.md` — the north-star reference for anyone building an ORC judge. This doc traces the design lineage from Verdict/RAGAS through the tier-1 implementation, explains the criteria × stance × scale three-part architecture, defines what a "perfect judge" looks like (8 canonical properties), and documents the deferred tier-2 patterns and the coming pluggable-scale gap.

This is the doc that makes building a custom judge approachable and correct.

## Read first

1. `components/evaluation/src/ai/obney/orc/evaluation/core/scale.clj` — full file
2. `components/evaluation/src/ai/obney/orc/evaluation/core/rubrics.clj` — full file
3. `components/evaluation/src/ai/obney/orc/evaluation/core/judges.clj` — full file
4. `docs/EVALUATION-COMPONENT.md` — the existing reference (to avoid duplication)
5. `components/gepa/src/ai/obney/orc/gepa/core/metrics.clj` — how GEPA consumes judge output
6. `components/evaluation/src/ai/obney/orc/evaluation/core/judge_runtime.clj` — the two deployment modes
7. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

**Yes.** Before writing the feedback-shape section: run `(eval/evaluate-trace trace-data)` with a mock LLM against a sample contract-analysis trace. Capture the FULL structured output envelope: `:level`, `:reasoning`, `:grounded-claims`, `:ungrounded-claims`, `:feedback`, `:score`. Embed verbatim in the "Structured feedback shape" section. Verify scale mapping: level 3 → score 0.5; level 5 → score 1.0. Verify no-run-through gate throws on nil input.

## TDD cycle

- **Red:** No standalone judge architecture doc exists. EVALUATION-COMPONENT.md has the tier-1 content but leads with the tier-1 caveat block, not with the newcomer "why and how" framing.
- **Green:** Write each section (why judges matter → lineage → recipe → custom judge → tier-2 coming) satisfying each acceptance criterion.
- **Refactor:** Verify band descriptions against `rubrics.clj` verbatim. Verify `discrete-scale` constructor signature against `scale.clj`. Verify the two deployment modes from `judge_runtime.clj`. Verify GEPA feedback consumption from `metrics.clj`.

## Acceptance criteria

- [ ] Opening: judges are the crux of all downstream learning — scores feed ontology → Living Descriptions → GEPA. Judge noise propagates into every learning mechanism. This must come first.
- [ ] Design lineage section: Verdict's `Unit × Scale × Extractor` → ORC's `criteria × stance × scale`. What ORC adopted, what it explicitly did NOT adopt (ensembles, logprob extraction, pairwise) and the cost/value rationale for each deferral.
- [ ] Perfect judge checklist (8 canonical properties, all non-optional), verified against source: (1) decoupled scale, (2) discrete 1-5 bands with explicit descriptions, (3) adversarial stance, (4) reason before score — field order enforced in schema, (5) evidence lists per dimension, (6) typed blackboard (no `:output-schemas`, no JSON-in-prompt), (7) no-run-through gate, (8) score derived from level — never self-reported.
- [ ] GROUNDING_SCALE band descriptions quoted verbatim from `rubrics.clj` as a worked illustration.
- [ ] Structured feedback envelope shown with a real captured REPL output from prototype step.
- [ ] "How to build a custom judge" section: `scale/discrete-scale` constructor shown with custom band map; `gate-banded-output` wiring; custom workflow via `:type :custom` + `:sheet-id`; the `:criteria`-override path on built-in judges (workflow-level `sheet/judges`).
- [ ] Two deployment modes clearly distinguished: (a) event-subscribed async learning path (`:judge/score-emitted` → consolidator → GEPA), (b) in-pipeline BT gate logic. A judge is never "inherently a pass/fail gate."
- [ ] "Coming soon" section: pluggable scales on built-in LLM judges (verified not in source); tier-2 hierarchical verify + ensemble + pairwise.
- [ ] How GEPA consumes the output: `:score` as fitness signal, `:feedback` text as reflective mutation input — verified from `metrics.clj`.

## Do NOT touch

`docs/EVALUATION-COMPONENT.md` — that is DOC-10's scope. Component source files.

## Live QA the orchestrator runs

Run `(eval/evaluate-trace {...})` with mock LLM. Confirm output shape matches doc section. Read `rubrics.clj` GROUNDING_SCALE — confirm band 3 description matches doc verbatim. Read `scale.clj` `level->unit-score` — confirm level 3 → 0.5. Execute `(scale/discrete-scale {:min 1 :max 5 :bands {1 "A" ...}})` — confirm it constructs cleanly.

## Blocked by

None — can start immediately (parallel with DOC-01).

## Handoff note

This doc must NOT duplicate the full API reference from EVALUATION-COMPONENT.md. It is the "why and how to build" guide; EVALUATION-COMPONENT.md is the reference. Cross-reference instead of copying. The Verdict research lineage is in orc-sessions internal docs — summarize the pattern, do not cite internal docs.