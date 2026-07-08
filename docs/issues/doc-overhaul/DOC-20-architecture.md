## What to build

Add a "Component boundaries" section to `docs/ARCHITECTURE.md` that shows what each of the 13 components exposes (its public interface namespace), what it depends on, and what it emits/receives from the event store. Currently ARCHITECTURE.md covers execution mechanics but has no component-boundary perspective. This section bridges it to COMPONENT-MAP.md.

## Read first

1. `docs/ARCHITECTURE.md` — full file
2. `docs/COMPONENT-MAP.md` — DOC-01 output (to avoid duplication; cross-reference the layer table)
3. Every component's `deps.edn` in `components/*/deps.edn` — for accurate dep relationships
4. Every component's `interface.clj` — for public namespace
5. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

No — structural addition only.

## TDD cycle

- **Red:** ARCHITECTURE.md covers execution mechanics (dispatch, blackboard, DSCloj) but has no component-boundary section. A developer changing Component A cannot see what depends on it.
- **Green:** Add "Component boundaries" section after the existing content. Never modify the existing execution-mechanics sections.
- **Refactor:** Cross-check every dep relationship against actual `deps.edn` files.

## Acceptance criteria

- [ ] Navigation header added: "For the dependency decision table see COMPONENT-MAP.md. This doc covers internal execution mechanics and component boundary details."
- [ ] "Component boundaries" section with a table: each component, its public interface namespace, its direct compile-time dependencies (from `deps.edn`), and what Grain events it emits/subscribes to
- [ ] Component boundary section is clearly labeled as distinct from the execution-mechanics sections (which remain unchanged)
- [ ] All 13 components covered (orc-service, gepa, evaluation, colbert, ontology, mcp-sheet-builder, langfuse, grain-test-utils, predict-rlm-*)
- [ ] Dep claims verified from actual `components/*/deps.edn` — no invented relationships

## Do NOT touch

Existing execution-mechanics content (any content present before this issue). Any component source.

## Live QA the orchestrator runs

Read `components/gepa/deps.edn` — confirm the boundary table dep for gepa matches. Read `components/evaluation/deps.edn` — same. Read `components/colbert/deps.edn` — confirm Python bridge dep noted.

## Blocked by

Wave 1 complete (DOC-01 COMPONENT-MAP must exist as the reference).

## Handoff note

The execution-mechanics content (Blackboard Data Flow, Code Executor Pattern, Node Execution Behaviors, DSCloj Internals) is correct and valuable — do not modify it. This is an additive section only.