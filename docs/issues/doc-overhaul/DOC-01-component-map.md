## What to build

Create `docs/COMPONENT-MAP.md` — the single document that answers "which ORC components do I need?" It contains the verified opt-in layer table (Layers 0–8), a component dependency graph, Python requirement flags, and explicit "known issues" callouts for things newcomers will hit.

This is the navigational north star that every other doc in Wave 1 and Wave 2 will reference. It must be accurate at the source level.

## Read first

1. `README.md` in this repo (current flat component list — the thing to supersede)
2. `projects/orc/deps.edn` — which components are bundled in the umbrella project
3. Every component's `deps.edn` in `components/*/deps.edn` — actual dep relationships
4. `components/ontology/src/ai/obney/orc/ontology/core/embedding.clj` — DJL vs Python evidence
5. `components/colbert/src/ai/obney/orc/colbert/core/bridge.clj` — Python bridge evidence
6. `components/evaluation/src/ai/obney/orc/evaluation/core/judge_runtime.clj` — independence evidence
7. `components/gepa/deps.edn` — zero ontology dep evidence
8. `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` line 2176 — recursive default
9. `docs/prd/orc-documentation-overhaul.md` — the PRD that drives this issue

## Prototype required?

No. All claims verified by reading source files. No code execution needed.

## TDD cycle

- **Red:** No COMPONENT-MAP.md exists. The README has a flat component list with no hierarchy, no Python flags, no dep relationships, no known issues.
- **Green:** Write each section (layer table, dep graph, known issues) satisfying each acceptance criterion in order.
- **Refactor:** Verify every row in the layer table against the actual source (deps.edn, requires, bridge code). Fix any claim that doesn't hold.

## Acceptance criteria

- [ ] Layer table (Layers 0–8) present with: capability, component(s) needed, Python required (yes/no), one-line evidence citation (e.g., "`bridge.clj` spawns subprocess")
- [ ] Each "Python: No" claim verified from source (`embedding.clj` uses DJL, ColBERT resolved via `find-ns` — graceful nil on absence)
- [ ] Dependency graph section showing which components are independent vs chained
- [ ] "Known issues" section with at minimum: BFS scoping (`expand-concept-neighborhood` walks all ontology-id sections regardless of filter — HIGH priority, unfixed), pluggable judge scales gap (built-in LLM judges sealed; custom via `discrete-scale`), `tree-fingerprint` is structural (collapses `:instruction`)
- [ ] "Slim ORC" callout: depend on `components/orc-service` directly to drop DJL/Python deps
- [ ] MCP Sheet Builder correctly framed as standalone (Layer 8, no chain to self-improving loop)
- [ ] All cross-references to other docs use the new doc names (e.g., `GETTING-STARTED.md`)

## Do NOT touch

`README.md` — that is DOC-02's scope. `docs/prd/`. Any component source files.

## Live QA the orchestrator runs

Read `components/gepa/deps.edn` — confirm zero `ontology` dep. Read `components/evaluation/src/.../core/judge_runtime.clj` — find the ontology require and confirm it is ONLY used for `get-living-description-enabled?`. Read `components/colbert/core/bridge.clj` line 1–15 — confirm Python subprocess. Read `components/ontology/core/embedding.clj` — confirm DJL `Criteria/builder`. Cross-check all Layer table entries against these reads.

## Blocked by

None — can start immediately.

## Handoff note

Do not write any code examples in this doc — it is a reference map only. Do not pre-populate the "coming soon" items beyond what is verifiably designed: pluggable scales (verified: not in source, documented in EVALUATION-COMPONENT.md as intent), subbehavior specialists (verified: EB4-EB12 designed but not built in feature/ontology-architecture branch).