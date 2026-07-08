## What to build

Rename `docs/pattern-compendium.md` → `docs/contributors/CONTRIBUTOR-GRAIN-PATTERNS.md`. Create the `docs/contributors/` directory. Add a prominent banner clarifying this is for contributors building ORC internals (defcommand, defreadmodel, etc.) — not for consumers building behavior-tree workflows. Update all cross-references.

## Read first

1. `docs/pattern-compendium.md` — first 50 lines (audience and purpose)
2. All docs that cross-reference `pattern-compendium.md` — grep for it

## Prototype required?

No.

## TDD cycle

- **Red:** Doc named "pattern-compendium" which collides with the "pattern corpus" in the ontology/self-improving loop. Content is exclusively Grain CQRS patterns for contributors — not behavior-tree composition patterns for consumers.
- **Green:** Create directory, rename, add banner, update references.
- **Refactor:** Confirm all references updated. Confirm no consumer-facing doc links to it without a clear contributor-context warning.

## Acceptance criteria

- [ ] `docs/contributors/` directory created
- [ ] File moved to `docs/contributors/CONTRIBUTOR-GRAIN-PATTERNS.md` (git mv)
- [ ] Banner at top: "**This doc is for ORC/Grain contributors** building framework internals (`defcommand`, `defreadmodel`, `defprocessor`, `defperiodic`, etc.). If you are building behavior-tree workflows with the ORC DSL, see [GETTING-STARTED.md](../GETTING-STARTED.md)."
- [ ] `grep -r "pattern-compendium" docs/` returns only this issue file
- [ ] All references updated to `docs/contributors/CONTRIBUTOR-GRAIN-PATTERNS.md`

## Do NOT touch

Any content inside the file — preserved exactly. Any component source.

## Live QA the orchestrator runs

`grep -r "pattern-compendium" docs/ --include="*.md"` — only this issue file should appear.

## Blocked by

Wave 1 complete.

## Handoff note

Use `mkdir -p docs/contributors && git mv docs/pattern-compendium.md docs/contributors/CONTRIBUTOR-GRAIN-PATTERNS.md` to preserve git history.