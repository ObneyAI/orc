## What to build

Overhaul `README.md`. The front door of ORC. Replace the flat component list with a concise opt-in layer table, a "pick your layer" orientation paragraph, and cross-references to the new docs created in Wave 1. Keep the Quick Start example (it is correct and valuable). Add the slim-ORC note. Add the "Coming soon" stability note.

## Read first

1. `docs/issues/doc-overhaul/DOC-01-component-map.md` — must be complete first
2. `docs/COMPONENT-MAP.md` — the output of DOC-01 (verified layer table)
3. `README.md` current state — identify what to keep vs replace
4. `/Users/darylroberts/Desktop/Code/orc-template/README.md` — the template consumer experience to mirror for the code example
5. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

No. Content is cross-references and prose; verified from DOC-01 output.

## TDD cycle

- **Red:** Current README has flat component list, no hierarchy, no "what do I need" guidance, no pointer to getting-started.
- **Green:** New opening section with layer table. Updated component list linking to COMPONENT-MAP. Updated links to GETTING-STARTED.md.
- **Refactor:** Confirm all links resolve. Confirm the code example runs end-to-end against orc-template. No claim about a component that contradicts COMPONENT-MAP.

## Acceptance criteria

- [ ] Opening section: "Pick your layer" table (condensed from COMPONENT-MAP) visible before the code example — at most 10 lines
- [ ] Existing Quick Start code example is verified to run against orc-template; output is noted inline as a comment
- [ ] Component section links to `docs/COMPONENT-MAP.md` for the full dep graph
- [ ] Link to `docs/GETTING-STARTED.md` as the onboarding path
- [ ] "Slim ORC" option surfaced: `components/orc-service` without heavy ML deps
- [ ] RLM recursive-default noted with terminal-deprecation caveat
- [ ] Alpha-stage callout for self-improving loop references
- [ ] No fabricated claims; no component described as a dep if not verified in DOC-01

## Do NOT touch

`docs/COMPONENT-MAP.md`, `docs/GETTING-STARTED.md`, any component source.

## Live QA the orchestrator runs

Run the embedded Quick Start example against orc-template. Confirm output matches the inline comment. Confirm all links resolve (`docs/COMPONENT-MAP.md`, `docs/GETTING-STARTED.md`).

## Blocked by

DOC-01 (COMPONENT-MAP.md must exist first).

## Handoff note

The Quick Start example must use the `orc-template` pattern exactly: `backend/start`, extract `ctx`, `build-workflow!`, `execute`. Do not invent a different startup pattern.