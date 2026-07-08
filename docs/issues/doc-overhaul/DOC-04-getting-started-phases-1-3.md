## What to build

Create the first half of `docs/GETTING-STARTED.md` covering Phases 1–3: (1) core behavior tree, (2) judges, (3) custom judges. This is the progressive narrative using contract analysis as the through-line domain. Each phase explicitly names what was added, what deps changed, and what the new concept is.

The entire doc must be executable. Every code example must be run against `orc-template`, output captured, and embedded inline.

## Read first

1. `docs/COMPONENT-MAP.md` (DOC-01 output — must exist)
2. `docs/JUDGE-ARCHITECTURE.md` (DOC-03 output — must exist)
3. `/Users/darylroberts/Desktop/Code/orc-template/src/orc/template/backend.clj`
4. `/Users/darylroberts/Desktop/Code/orc-template/src/orc/template/example_workflow.clj`
5. `/Users/darylroberts/Desktop/Code/orc-template/README.md`
6. `docs/ORC-SERVICE-GUIDE.md` — existing DSL reference
7. `components/evaluation/src/ai/obney/orc/evaluation/interface.clj`
8. `components/evaluation/src/ai/obney/orc/evaluation/core/scale.clj`
9. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

**Yes — before writing each phase.** Start `orc-template`. For each code example: (1) type it into the REPL verbatim, (2) capture the full output, (3) confirm it matches expectations, (4) embed the captured output as a code comment in the doc. Stop `orc-template` after each verification run (JVM hygiene).

Phase 1 examples: build-workflow! → execute on the contract analysis workflow. Capture the result map.

Phase 2 examples: attach a `:grounding` judge, execute, read back `:judge/score-emitted` event, capture `(eval/get-judge-scores ...)`.

Phase 3 examples: build `scale/discrete-scale` with custom bands, build a custom judge workflow, attach via `:type :custom`, confirm it scores.

## TDD cycle

- **Red:** No GETTING-STARTED.md exists. New users have no progressive entry point.
- **Green:** Write each phase section with verified code examples, exactly satisfying each acceptance criterion before moving to the next phase.
- **Refactor:** Re-run every example after writing. Cross-check every node type against ORC-PRINCIPLES.md Principle 1 (right node palette). Cross-check every blackboard schema against the "never use `:any`" rule.

## Acceptance criteria

**Phase 1 — Core tree:**
- [ ] Minimal working contract analysis workflow: `:sequence` → 5 `:llm` nodes (survey → diff → classify → impact → summarize)
- [ ] Blackboard shows Malli schemas with `{:description ...}` on every key
- [ ] `build-workflow!` call is idempotent — doc explains why (SHA-256 content hash)
- [ ] `execute` return shape shown with REAL captured output
- [ ] `print-tree` output shown (text-mode visual of the tree structure)
- [ ] "What you just added" callout: Layer 0 only, zero extra deps

**Phase 2 — Judges:**
- [ ] Two judges attached: `:grounding` + `:completeness`
- [ ] `set-node-judges` call shown (the two-step attach pattern)
- [ ] Structured feedback envelope shown from DOC-03 — real output from prototype
- [ ] `:judge/score-emitted` event queried from event store
- [ ] "What you just added" callout: `evaluation` component only, no Python, no ontology needed
- [ ] "Coming soon" note: pluggable scale descriptions on built-in judges

**Phase 3 — Custom judge:**
- [ ] `scale/discrete-scale` with contract-specific 1-5 bands (e.g., "Does this change affect indemnification clauses?")
- [ ] Two paths shown: (a) custom code judge, (b) custom LLM judge
- [ ] Both attached via `:type :custom` + `:sheet-id`
- [ ] "What you just added" callout: still `evaluation` only, no new deps
- [ ] Cross-reference to `docs/JUDGE-ARCHITECTURE.md` for the full north star

**All phases:**
- [ ] Every `:llm` node writes `:reasoning` first in `:writes`
- [ ] No `:map-each` example has a composite leaf
- [ ] No domain-hardcoded examples (contract domain via substitution, not vertical locking)
- [ ] Progressive dep table at the top of each phase shows what was added

## Do NOT touch

`docs/GETTING-STARTED.md` Phases 4–6 (DOC-05's scope). Any component source.

## Live QA the orchestrator runs

Start orc-template. Execute every Phase 1-3 example. Confirm output matches embedded comments. Confirm 0 orphan JVMs after each run. Read `components/evaluation/src/.../judges.clj` — confirm judge fn signature matches doc's description. Read `ORC-PRINCIPLES.md` Principle 1 — confirm every node type used matches the "right node for the work" guidance.

## Blocked by

DOC-01 (COMPONENT-MAP must exist), DOC-03 (JUDGE-ARCHITECTURE must exist).

## Handoff note

Start from the `orc-template` backend wiring exactly — `backend/start`, extract `ctx`. Do not invent an alternative setup pattern. Phase 1 example is the contract-analysis workflow (5 `:llm` nodes), not a toy "hello world." The getting-started guide should show something real and useful from Phase 1, not a trivial example that needs to be thrown away.