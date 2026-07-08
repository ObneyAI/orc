## What to build

Restructure `docs/EVALUATION-COMPONENT.md` to lead with the judge architecture north star (the "why and how") rather than the dense tier-1 caveat block. The existing doc has excellent content but loses newcomers in the first 30 lines with technical migration context. Restructure: (1) why judges matter → (2) the two deployment modes → (3) rubric architecture → (4) built-in judges → (5) custom judges → (6) API reference.

## Read first

1. `docs/EVALUATION-COMPONENT.md` — full file
2. `docs/JUDGE-ARCHITECTURE.md` — DOC-03 output (don't duplicate; cross-reference instead)
3. `components/evaluation/src/ai/obney/orc/evaluation/interface.clj` — API signatures to verify
4. `components/evaluation/src/ai/obney/orc/evaluation/core/scale.clj` — verify level→score mapping
5. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

Yes — run `(eval/evaluate-trace trace-data)` and verify the structured output shape matches the doc's "structured feedback envelope" section exactly.

## TDD cycle

- **Red:** Doc leads with tier-1 caveat block ("Status: all four built-in LLM judges run the tier-1 shape"). Newcomer cannot find "what is a judge" before drowning in migration context.
- **Green:** Move tier-1 caveat block to later (it's a changelog entry for people who know the old system). Open with "why judges matter" and "two deployment modes."
- **Refactor:** Verify every API call signature against `interface.clj`. Confirm the custom judge section matches the pattern in JUDGE-ARCHITECTURE.md.

## Acceptance criteria

- [ ] Opening: "Why judges matter" (the downstream learning chain: scores → ontology → Living Descriptions → GEPA) before any API reference
- [ ] Two deployment modes shown early: (a) event-subscribed async learning path, (b) in-pipeline BT gate — a judge is never "inherently a pass/fail gate"
- [ ] Cross-reference to `docs/JUDGE-ARCHITECTURE.md` introduced early as "the north star guide for building judges"
- [ ] Tier-1 caveat block preserved but repositioned to an "Implementation details" section (not the opening)
- [ ] Built-in judge `:score` derivation chain shown: level → `level->unit-score` → `[0,1]` — never self-reported by the model
- [ ] Custom judge section cross-references GETTING-STARTED Phase 3
- [ ] "Coming soon" section: pluggable scales on built-in judges
- [ ] All API signatures verified against `evaluation/interface.clj`
- [ ] Structured feedback envelope shown with real captured output from prototype

## Do NOT touch

`docs/JUDGE-ARCHITECTURE.md`. Any component source.

## Live QA the orchestrator runs

Run `(eval/evaluate-trace {:inputs {...} :outputs {...} :instruction "..."})` with mock LLM. Confirm the output shape matches the doc's feedback envelope section exactly. Read `scale.clj level->unit-score` — confirm band 3 → 0.5 matches doc.

## Blocked by

Wave 1 complete (DOC-03 JUDGE-ARCHITECTURE must exist).

## Handoff note

Do not delete the tier-1 caveat block — it is accurate and important for users who know the old system. Move it, don't remove it. The goal is: newcomer can understand "what is a judge and how do I use one" in the first screen, before seeing any migration/changelog context.