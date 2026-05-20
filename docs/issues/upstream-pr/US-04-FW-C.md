# US-FW-C — PR-Framework commit 3: prompt updates + observability (U9 + U10 + U12) + RLM-GUIDE doc updates

## Parent

`docs/prd/upstream-pr-plan.md` — Step 2 of the execution sequence, third (final) PR-Framework commit. Same branch (`feature/rlm-framework-upgrades`) as US-FW-A and US-FW-B.

## ⚠️ Scope updated after main re-sweep (2026-05-20)

Main has PARTIALLY converged on U9 via commit `6747759` (R-2). What's already on main:
- `:code` added to the tree-DSL node-type list in the framework prompt (shape example included)
- 5 drill-down primitives documented in the recursive-mode prompt section with "use only when summary is insufficient" framing

Our U9 still adds **substantial unique content**:
- emit-tree! framed as the DEFAULT for non-trivial work (anti-pattern: 2+ sequential `(llm ...)` calls)
- `:code` documented as supporting BOTH `:fn "ns/sym"` AND inline `(fn [{:keys [inputs]}] ...)` (we may need to delete duplicate content if R-2's `:code` doc already covers this; verify at write time)
- `:output-schemas` on `:llm` nodes documented with concrete example (UNIQUE to us; only ships with U11)
- `:available-code-nodes` mechanism (unique to us)
- `:field-type :image` blackboard schemas (unique to us)
- Anti-pattern: LLM-counting vs deterministic `:code` for transforms (unique)

`docs/RLM-GUIDE.md` now exists on main (363 lines). Our changes should ADD sections, not replace. Verify the existing doc structure before writing additions to avoid duplication.

## What to build

**Commit:** `feat(orc-service): RLM prompt updates + observability (U9 + U10 + U12)`

Contents:

**U9 — `build-rlm-code-generation-module` in `executor.clj` framework prompt updates:**
- emit-tree! framed as the DEFAULT for non-trivial work
- "2+ sequential `(llm ...)` calls in Phase 1" called out as an anti-pattern
- `:code` documented as supporting both `:fn "ns/sym"` and inline `(fn [{:keys [inputs]}] ...)` — coordinate with R-2's already-shipped `:code` doc; AVOID duplication
- `:output-schemas` on `:llm` nodes documented with a concrete example
- `:code` for deterministic transforms preferred over LLM-counting (hallucination-prone)
- Cross-reference to `:available-code-nodes` when the catalog is provided
- `:field-type :image` blackboard schemas for vision inputs

**U10 — `:rlm/researcher-iterations` event:**
- Emitted from `execute-repl-researcher-node` in `todo_processors.clj` whenever iterations exist, regardless of execution mode (direct vs emit-tree!)
- Schema registered in `interface/schemas.clj`
- Provides a uniform iteration-capture surface for downstream observability tooling
- Verify against R-2's new event-store query primitives (`tree-detail`, `tree-trajectory`, etc.) — our event may already be readable via those primitives or may need separate access path

**U12 — `preview-vector` in `rlm_sandbox.clj` recursive previewing:**
- Recursively previews large-string sample elements
- Vectors of data-URI strings (or other large strings) get head/tail-truncated previews per element
- Primitive sample elements (numbers, booleans, short strings) stay raw

One new unit test (U12: `preview-vector-truncates-large-string-elements`). U9 doesn't get a brittle string-presence test — the live image_analysis benchmark's tree-emit behavior IS the test (with U9 in place, the model writes `emit-tree!` and uses `:output-schemas` / inline `:code`; without it, the model defaults to chained sequential `(llm ...)`). U10 is observability-only and validated by the comparison runner reading the events.

**`docs/RLM-GUIDE.md` updates (in the same commit OR as a separate `docs:` commit — author's choice; slight preference for same-commit so the capability + docs ship atomically):**

Additions to existing sections (existing 363 lines, ADD don't replace):
- "Sandbox primitives" sub-section: inline-fn `:code` syntax with a concrete letter-counting example (verify what R-2 already documented; AVOID duplication)
- "Basic usage" sub-section: `:output-schemas` on `:llm` nodes — purpose, syntax, downstream consumer interaction
- "Sandbox primitives" sub-note: `:field-type :image` blackboard schemas for vision inputs
- New section "Pre-built code-node catalog": `:available-code-nodes` on the repl-researcher node
- Cross-references between terminal and recursive emit-tree! modes (R-1's recursive mode interacts naturally with our new capabilities)

**Doc accuracy rigor:** every claim in the RLM-GUIDE.md additions is verified against a test or live behavior before submission. Pattern matches the existing `development/src/rlm_guide_examples_verify.clj` precedent (committed to main in `aa04483`).

## Acceptance criteria

- [ ] Third commit on `feature/rlm-framework-upgrades` with message starting `feat(orc-service): RLM prompt updates + observability (U9 + U10 + U12)` and Co-Authored-By trailer.
- [ ] U12 test (`preview-vector-truncates-large-string-elements` or equivalent) GREEN — large-string vector elements get previewed; primitive elements untouched.
- [ ] U10 event registered in `interface/schemas.clj` events map; new event-type schema validates a sample event body without error.
- [ ] U9 framework prompt has new content (verified by reading `(build-rlm-code-generation-module ...)`'s output for a synthetic node; OR by the downstream e2e behavior — the image_analysis dream-scenario depends on U9's emit-tree-as-default policy).
- [ ] `docs/RLM-GUIDE.md` extended with new sections for `:output-schemas`, `:field-type :image`, `:available-code-nodes` (and inline-fn `:code` ONLY if R-2 hasn't covered it).
- [ ] Doc examples verified — every claim grounded in a test result or live-run observation; pattern follows `development/src/rlm_guide_examples_verify.clj`.
- [ ] No regression on `rlm-mode-test`, `rlm-dsl-test`, `recursive_rlm_test`, `recursive_rlm_drill_down_test`, existing 5-benchmark suite.
- [ ] Final branch state: 3 logical commits + 3 new unit tests (down from 4 — U2/U3 tests are already on main via R-2's `rlm_dsl_test` additions) + RLM-GUIDE updates, all GREEN in `clj -M:poly test brick:orc-service`.

## Blocked by

- US-FW-B (commit 2 must land first on the same branch)
- US-Sync (main's R-2 + RLM-GUIDE additions must be merged before this slice can avoid duplication)
