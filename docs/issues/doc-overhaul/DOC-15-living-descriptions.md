## What to build

Tighten all three audience sections in `docs/LIVING-DESCRIPTIONS.md`. Part 1 (30-second stakeholder story) is good but can be tightened. Part 2 (developer mental model) loses newcomers at the "three granularities" table. Part 3 (implementer view) needs the judge integration updated for ADR 0011 (discrete bands, reason-before-score, composite scoring).

## Read first

1. `docs/LIVING-DESCRIPTIONS.md` — full file
2. `docs/SELF-IMPROVING-LOOP.md` — DOC-14 output (to avoid duplication; cross-reference instead of repeating)
3. `components/evaluation/src/ai/obney/orc/evaluation/core/judge_runtime.clj` — verify the boolean gate description
4. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

No — restructure and update. Verify specific claims from source.

## TDD cycle

- **Red:** Part 2 "three granularities" table has no link to examples. Part 3 judge integration section references the old soft 0.0-1.0 rubric approach. "Coming soon" items may include shipped work.
- **Green:** Tighten Part 1. Add examples links in Part 2. Update Part 3 judge integration for ADR 0011.
- **Refactor:** Verify every "coming soon" in Part 3 capabilities surface table against current source.

## Acceptance criteria

- [ ] Part 1 (30-sec story): 5 sentences maximum; links to Part 2 for "how"
- [ ] Part 2 "three granularities" table: each row has a link to a code example in GETTING-STARTED.md or the source function
- [ ] Part 2 "four safeguards" section: each safeguard verified against implementation (rolling window, confidence demotion, aggregate+delta, anomaly principle-shaping)
- [ ] Part 3 judge integration: updated with ADR 0011 — discrete 1-5 bands, reason-before-score, composite scoring. No reference to old soft 0.0-1.0 rubrics.
- [ ] `:auto-classify?` vs GEPA orthogonality reflected (LD loop feeds GEPA; GEPA is the static-instruction actuator)
- [ ] Part 3 capabilities surface table: every row verified against current source — mark shipped vs coming-soon accurately
- [ ] Cross-reference to SELF-IMPROVING-LOOP.md at top: "For the consumer entry point see SELF-IMPROVING-LOOP.md. This doc covers architecture and implementation detail."

## Do NOT touch

`docs/SELF-IMPROVING-LOOP.md`. Any component source.

## Live QA the orchestrator runs

Read `judge_runtime.clj` — confirm the boolean gate (`get-living-description-enabled?`) description matches doc. Read `judge_runtime.clj` — confirm 5 default judges for `:repl-researcher` nodes matches the Part 3 table. Confirm composite scoring section reflects what's in `judge_runtime.clj` (`:judge/composite-score-computed` event).

## Blocked by

Wave 1 complete, DOC-14 (SELF-IMPROVING-LOOP.md must be updated first to avoid content duplication).

## Handoff note

Do not duplicate content from SELF-IMPROVING-LOOP.md — cross-reference it instead. This doc's value is in the architectural depth (three granularities, four safeguards, consolidation mechanics, judge integration pipeline). Keep that depth; reduce or cross-reference the "how to use it" consumer content that is now in SELF-IMPROVING-LOOP.md.