# US-FW-B — PR-Framework commit 2: schema-driven structured output + event sanitization (U8 + U11)

## Parent

`docs/prd/upstream-pr-plan.md` — Step 2 of the execution sequence, second of three PR-Framework commits. Same branch (`feature/rlm-framework-upgrades`) as US-FW-A.

## ⚠️ Scope updated after main re-sweep (2026-05-20)

Main has converged with our U2 (DSL accepts inline-fn `:code` `:fn`) and U3 (tree executor `:code` string/fn discriminator) via commit `6747759` (R-2: drill-down primitives + :code tree-node port). Both implementations are semantically identical to ours; only comment wording differs.

**Dropped from this slice (already on main):**
- ~~U2~~ — DSL `:code` inline-fn (R-2)
- ~~U3~~ — Tree executor `:code` string/fn discriminator (R-2)

**Remaining in this slice:**
- **U8** — Inline-fn event sanitization (Fressian-safe)
- **U11** — `extract-key-schemas` + schema-driven declare-key (structured `:llm` output)

At merge time during US-Sync, our local `:code` branches in `rlm_dsl.clj` and `rlm_tree_executor.clj/compile-tree-node` will conflict trivially with R-2's. Resolution: pick R-2's comment style (it's already-merged-to-main canonical) but keep our local tests if they differ.

## What to build

**Commit:** `feat(orc-service): schema-driven structured output + inline-fn event sanitization (U8 + U11)`

Contents:
- **U8** — `todo_processors.clj` `execute-repl-researcher-node` sanitizes inline-fn values out of stored events. The `:rlm/tree-generated` event body's `:raw-dsl` AND the repl-researcher's `:writes` (in `:sheet/complete-node-execution`) both walk the tree with `clojure.walk/postwalk` and replace `:fn` map-entries whose value is a function with the placeholder string `"<inline-fn>"`. Actual functions live in the ephemeral registry during Phase-2 execution; events stay Fressian-serializable.
- **U11** — `extract-key-schemas` helper added to `rlm_tree_executor.clj`. Walks the canonical tree and collects `{write-key → Malli-schema}` from `:llm` nodes that declared `:output-schemas`. `execute-tree`'s declare-keys loop prefers the schema from the collected map over the `:any` fallback. Downstream `build-module` in `executor.clj` already passes blackboard schemas to dscloj; dscloj's existing `complex-spec?` recognizes structured types and asks the LLM for valid JSON + parses the response. End-to-end: the model declares `:output-schemas` on `:llm` nodes; downstream `:code` consumers receive parsed Clojure data instead of JSON-text strings.

One new unit test (U8: tree-sanitization). U11's existing tests stay (`llm-node-preserves-output-schemas`, `extract-key-schemas-collects-from-llm-nodes`).

This is the capability bundle delta after R-2 converged on the `:code` DSL pieces. Existing consumers see no behavior change (these are opt-in additions to the DSL via `:output-schemas` declaration on `:llm` nodes).

## Acceptance criteria

- [ ] Second commit on `feature/rlm-framework-upgrades` with message starting `feat(orc-service): schema-driven structured output + inline-fn event sanitization (U8 + U11)` and Co-Authored-By trailer.
- [ ] U8 sanitization test (`sanitize-tree-replaces-fn-values-with-placeholder` or equivalent) GREEN — post-walk replacement on synthetic tree.
- [ ] U11 tests GREEN — schema round-trips through DSL + `extract-key-schemas` collects from nested trees.
- [ ] Live e2e proof point: an emit-tree! tree containing an inline `:code` node with destructuring `{:keys [inputs]}` executes end-to-end without Fressian serialization failures.
- [ ] Live e2e proof point: an emit-tree! tree with `:output-schemas` on an `:llm` node produces parsed Clojure data on the next `:code` consumer (not a JSON-text string).
- [ ] No regression on `rlm-mode-test`, `rlm-dsl-test`, `recursive_rlm_test` (NEW, from R-1/R-2), `recursive_rlm_drill_down_test` (NEW, from R-2), existing 5-benchmark suite.

## Blocked by

- US-FW-A (commit 1 must land first on the same branch)
- US-Sync (R-2's convergent `:code` branch resolution must happen during sync, before this slice starts)
