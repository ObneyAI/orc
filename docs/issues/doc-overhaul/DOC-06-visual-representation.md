## What to build

Integrate the chosen visual tree representation approach into all Wave 1 docs. This is a HITL issue: a prototype subagent has already been dispatched to produce three prototype files. The orchestrator reviews the prototypes and selects one direction. Then this issue integrates that direction into GETTING-STARTED.md and establishes the visual pattern for all future doc work.

## Read first

1. `/var/folders/zr/3hjhr8kn74j3cq9392k4_cyc0000gn/T/opencode/prototype-a-mermaid.md`
2. `/var/folders/zr/3hjhr8kn74j3cq9392k4_cyc0000gn/T/opencode/prototype-b-print-tree.md`
3. `/var/folders/zr/3hjhr8kn74j3cq9392k4_cyc0000gn/T/opencode/prototype-c-svg.md`
4. `docs/GETTING-STARTED.md` (DOC-04 + DOC-05 output — must exist)
5. `components/orc-service/src/ai/obney/orc/orc_service/core/dsl.clj` — `print-tree` fn

## Prototype required?

This issue IS the prototype integration. The orchestrator selects a direction from the prototype outputs before this issue begins.

## TDD cycle

- **Red:** GETTING-STARTED.md has code-only tree representations.
- **Green:** Add the selected visual format to each phase's tree introduction; establish conventions (when to show visual, when to show print-tree, always show raw DSL).
- **Refactor:** Confirm every visual matches the raw DSL exactly — no invented node types, no incorrect `:reads`/`:writes`, no syntax that doesn't compile.

## Acceptance criteria

- [ ] All three representations present for the Phase 1 workflow: visual (chosen format), `print-tree` output, raw DSL — in that order
- [ ] Simple visual for Phase 2 (judges attached), Phase 3 (custom judge)
- [ ] Node-type color/shape conventions established in a legend
- [ ] No visual contains information that contradicts the raw DSL
- [ ] Every visual is generated from or verified against the actual workflow definition (not hand-drawn and inconsistent)
- [ ] Conventions doc added to GETTING-STARTED.md header: "All tree diagrams follow [format]. The raw DSL is always the source of truth."

## Do NOT touch

Any component source. The Mermaid/SVG rendering approach is chosen by the orchestrator, not this agent.

## Live QA the orchestrator runs

For every visual in the doc: compare it node-by-node against the raw DSL in the same section. Confirm no discrepancy in node types, `:reads`, `:writes`, or control flow.

## Blocked by

HITL — prototype output reviewed and direction selected by orchestrator. Then DOC-04 and DOC-05 complete.

## Handoff note

The three prototype files are at the temp path above. The orchestrator will confirm which format to use before this agent begins. Agent must not choose the visual format themselves.