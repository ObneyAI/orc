## What to build

Restructure `docs/ORC-SERVICE-GUIDE.md` by opt-in tier. The current doc is a comprehensive reference but has no "start here" orientation and mixes Layer 0 content (DSL, execution, streaming) with Layer 7 content (ontology context injection) without signposting. Restructure so each major section is labeled with its Layer number and dependency callout.

## Read first

1. `docs/ORC-SERVICE-GUIDE.md` — full file
2. `docs/COMPONENT-MAP.md` — tier labels to apply
3. `docs/GETTING-STARTED.md` — what's already covered (don't duplicate intro material; cross-reference instead)
4. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

Yes — any code claim in the doc not already verified by GETTING-STARTED.md must be run against orc-template. At minimum: verify `map-each` parallel example, `delegate` call, `llm-call-budget` enforcement, and streaming quick-start.

## TDD cycle

- **Red:** Guide has no tier callouts. Layer 0 content is mixed with Layer 7 content. No navigation header pointing to GETTING-STARTED.
- **Green:** Add navigation header. Add Layer badge/callout to each major section. Verify all code examples.
- **Refactor:** Confirm cross-references resolve. Confirm no content duplicates GETTING-STARTED without adding depth.

## Acceptance criteria

- [ ] Navigation header: "For a progressive introduction see GETTING-STARTED.md. This doc is the complete DSL and execution reference."
- [ ] Each section has a dep callout: "**Layer N** — requires `component-name`. See COMPONENT-MAP.md."
- [ ] Judge section: Layer 1+ label, reference to JUDGE-ARCHITECTURE.md for the north star
- [ ] `map-each` parallel section references ORC-PRINCIPLES Principle 14 explicitly
- [ ] `delegate` section notes the map-parsing behavior (Principle 10) and the structured-Malli-schema requirement
- [ ] Streaming section: Layer 0 (core), labeled appropriately
- [ ] `repl-researcher` section: recursive-as-default stated, terminal-deprecation noted
- [ ] No code example that hasn't been run against orc-template
- [ ] No reference to Python DSpy/libpython-clj paths — DSCloj is the primary path

## Do NOT touch

`docs/GETTING-STARTED.md`. `docs/COMPONENT-MAP.md`. Any component source.

## Live QA the orchestrator runs

Run the `map-each` parallel example with `:parallel 3`. Confirm results are correctly collected. Run a `delegate` example — confirm child blackboard isolation. Confirm `llm-call-budget` throws correctly when exceeded. Confirm streaming quick-start delivers a `:stream-closed` event.

## Blocked by

Wave 1 complete.

## Handoff note

The ORC-SERVICE-GUIDE.md is a long doc (864 lines). Do not shorten it by removing content — the reference value is in completeness. Restructure section headers and add callouts. Remove or update references to the Python fallback DSPy path (the Clojure DSCloj is the primary path).