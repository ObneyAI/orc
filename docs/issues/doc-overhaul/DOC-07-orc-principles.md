## What to build

Review `docs/ORC-PRINCIPLES.md`. The 14 principles are solid and timeless — this doc likely survives mostly intact. The overhaul focuses on: adding a navigation header pointing to COMPONENT-MAP.md and GETTING-STARTED.md, updating all cross-references to current doc names (e.g., `DSL-REFERENCE.md`), and adding code-verified examples for any principle that currently lacks one.

## Read first

1. `docs/ORC-PRINCIPLES.md` — full file
2. `docs/COMPONENT-MAP.md` — to write accurate cross-references
3. `docs/GETTING-STARTED.md` — to confirm examples in principles match guide
4. `components/orc-service/src/ai/obney/orc/orc_service/core/runtime.clj` — verify Principle 14 (map-each partial)
5. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

No. Principles are conceptual. Verify any code claims from source, but no execution needed.

## TDD cycle

- **Red:** Principles has no navigation header; some cross-references point to docs being renamed; Principle 1 does not note `:repl-researcher` recursive-as-default.
- **Green:** Add navigation header; update cross-references; verify each principle against current codebase state; flag any aspirational items.
- **Refactor:** Re-read each principle against ORC codebase — find any that claim shipped behavior that isn't yet shipped.

## Acceptance criteria

- [ ] Navigation header at top: "Start at GETTING-STARTED.md for a progressive introduction. See COMPONENT-MAP.md for the dependency decision table."
- [ ] All "See also" cross-references updated to current doc names (`DSL-REFERENCE.md`, `GETTING-STARTED.md`, `COMPONENT-MAP.md`)
- [ ] Principle 1 (node palette): `:repl-researcher` note — "recursive mode is the default; terminal mode is deprecated"
- [ ] Principle 14 (parallel map-each leaf) verified by reading `runtime.clj` leaf-collection behavior — confirmed matches description verbatim
- [ ] Principle 8 (events-first) confirmed: no example suggests bare appends
- [ ] Any aspirational principle tagged with its shipped/coming-soon state
- [ ] No content deleted — all 14 principles preserved

## Do NOT touch

Any component source. `docs/GETTING-STARTED.md`. `docs/COMPONENT-MAP.md`.

## Live QA the orchestrator runs

Read `components/orc-service/src/.../core/runtime.clj` map-each collection logic — confirm it matches Principle 14 description verbatim. Read `executor.clj:2176` — confirm Principle 1 note on recursive-default is accurate.

## Blocked by

Wave 1 complete (DOC-01 through DOC-05 merged and inspected).

## Handoff note

Do not rewrite the principles — they are carefully worded. Additions (navigation header, cross-ref updates, tagged items) only.