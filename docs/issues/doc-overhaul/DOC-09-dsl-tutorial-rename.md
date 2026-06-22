## What to build

Rename the legacy DSL overview doc to `docs/DSL-REFERENCE.md`. Add a prominent header marking the first ~135 lines as "the newcomer path" and positioning the rest as reference material. Update all cross-references across all docs. The rename removes the misleading "tutorial" framing for what is a 1508-line complete reference.

## Read first

1. `docs/DSL-REFERENCE.md` — full file (note: 1508 lines; first 135 are newcomer-accessible)
2. `docs/GETTING-STARTED.md` — what's already covered (the intro material is now there)
3. All docs that cross-reference `DSL-REFERENCE.md` — grep for `DSL-REFERENCE` across `docs/`
4. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

No — rename and restructure headers only. Spot-check 3 code examples from the existing doc against orc-template to confirm they still work.

## TDD cycle

- **Red:** Doc is called a tutorial but is 1508 lines of reference. Newcomers open it and are overwhelmed. The good introductory material is buried.
- **Green:** Rename. Add navigation banner. Mark the first 135 lines explicitly as the newcomer entry. Leave all content intact.
- **Refactor:** Update all cross-references. Spot-check 3 examples.

## Acceptance criteria

- [x] File renamed to `docs/DSL-REFERENCE.md` (git mv)
- [x] Banner at very top: "If you're new to ORC, start at GETTING-STARTED.md. This is the complete DSL reference. The Core Concepts section (lines 90–135) is the newcomer entry point; the rest is reference material."
- [x] The "Core Concepts" section is explicitly marked as "Newcomer entry point — start here"
- [x] All cross-references updated to `DSL-REFERENCE.md` across every doc
- [ ] No content deleted — all 1508 lines preserved
- [ ] 3 code examples spot-checked against orc-template — output matches

## Do NOT touch

Any component source. `docs/GETTING-STARTED.md`.

## Live QA the orchestrator runs

`grep -r "DSL-REFERENCE" docs/` should show the new name. `grep -r "dsl-tutorial" docs/` should return zero results. Spot-check 3 examples: the CRM lead qualification workflow, the `parallel` example, and the `map-each` aggregation example.

## Blocked by

Wave 1 complete.

## Handoff note

Used `git mv` on the original file to preserve git history.
