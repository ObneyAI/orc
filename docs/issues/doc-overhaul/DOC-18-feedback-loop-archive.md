## What to build

Archive `docs/FEEDBACK-LOOP.md`. First verify that all content worth preserving has been migrated to `SELF-IMPROVING-LOOP.md` (by DOC-14). Then move the file to `docs/archived/FEEDBACK-LOOP.md` with a header explaining why it was archived and where the current content lives. Update all cross-references.

## Read first

1. `docs/FEEDBACK-LOOP.md` — full file
2. `docs/SELF-IMPROVING-LOOP.md` — DOC-14 output (confirm migration complete)
3. All docs that reference `FEEDBACK-LOOP.md` — grep for it

## Prototype required?

No.

## TDD cycle

- **Red:** FEEDBACK-LOOP.md predates the RLM architecture. Its "Future Automated Loop" describes `:auto-classify?` as a future feature. Its 7-stage diagram omits Phase 1/2 RLM mechanics.
- **Green:** Confirm 3 sections migrated to DOC-14. Create `docs/archived/`. Move file. Add archived header. Update all references.
- **Refactor:** Grep all docs for `FEEDBACK-LOOP.md` — confirm all updated.

## Acceptance criteria

- [ ] Pre-flight check: confirm DOC-14 (SELF-IMPROVING-LOOP.md) contains the 7-stage diagram, batch workflows, and troubleshooting table
- [ ] `docs/archived/` directory created
- [ ] File moved to `docs/archived/FEEDBACK-LOOP.md` (use git mv)
- [ ] Archived header added at the very top: "**Archived.** This document predates the RLM architecture (2025). The self-improving loop pipeline it describes is now implemented differently. See [SELF-IMPROVING-LOOP.md](../SELF-IMPROVING-LOOP.md) for the current consumer guide and [LIVING-DESCRIPTIONS.md](../LIVING-DESCRIPTIONS.md) for architecture detail."
- [ ] `grep -r "FEEDBACK-LOOP" docs/` returns only the archived file itself and this issue file
- [ ] All references in other docs updated to point to `SELF-IMPROVING-LOOP.md`

## Do NOT touch

`docs/SELF-IMPROVING-LOOP.md` (content already added by DOC-14). Any component source.

## Live QA the orchestrator runs

`grep -r "FEEDBACK-LOOP" docs/ --include="*.md"` — confirm only the archived file and issue file remain. Open `docs/archived/FEEDBACK-LOOP.md` — confirm archived header is first thing visible.

## Blocked by

Wave 1 complete, DOC-14 (content migration to SELF-IMPROVING-LOOP.md must be done first).

## Handoff note

Use `git mv docs/FEEDBACK-LOOP.md docs/archived/FEEDBACK-LOOP.md` to preserve git history. Do not delete and recreate.