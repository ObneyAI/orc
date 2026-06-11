# PR03 — `:available-code-nodes` plumbing through `:rlm` config

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

Expose per-benchmark tool-catalog content to the RLM researcher's prompt without polluting the goal instruction. Tool documentation is task-specific; the goal instruction stays verbatim from predict-rlm's `signature.py` docstring.

The mechanism: add `:available-code-nodes` (optional string) to the existing `:rlm` map config on the repl-researcher node. `:rlm` is already `[:or :boolean :map]` (free-form map), so no schema/command change is needed.

Read path:

1. `execute-repl-researcher-rlm` in `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` reads it the same way `:debug?` is read today (lines ~1262-1263):
   ```
   rlm-config (let [rlm (:rlm node)] (if (map? rlm) rlm {}))
   available-code-nodes (get rlm-config :available-code-nodes)
   ```
2. The value is passed into `build-rlm-code-generation-module` (lines ~1099-1240) which produces the dscloj module.
3. `build-rlm-code-generation-module` adds an additional `:inputs` entry when the value is non-nil:
   ```
   {:name :available-code-nodes
    :spec :string
    :description "Available code-node functions for use in emit-tree! :code nodes"}
   ```
4. The module's `:instructions` references this input where the existing `## Available Primitives` section lives, so the model is told to read it.
5. At call time, `execute-repl-researcher-rlm`'s `inputs` map (currently `{:task ... :inputs-info ... :history ...}`) gains `:available-code-nodes` when set.

Runner-side: the catalog string is constructed by each per-benchmark task definition and supplied at sheet-creation time. No runner changes in this slice — that lands with PR05.

## Acceptance criteria

- [ ] A repl-researcher node configured with `:rlm {:debug? true :available-code-nodes "## Tools\n..."}` causes the dscloj module to include the new input field and pass the value at call time
- [ ] When `:available-code-nodes` is not set (or nil), the module behaves exactly as before — no extra input field, no prompt regression
- [ ] Unit test: call `build-rlm-code-generation-module` with a node carrying `:rlm {:available-code-nodes "X"}`; assert the returned module has the expected `:inputs` entry and the instructions reference it
- [ ] Unit test: call without `:available-code-nodes`; assert the module shape matches the existing one (no new input)
- [ ] `clj -M:poly test brick:orc-service` passes
- [ ] No regression — re-run existing `contract_comparison_validated` (which doesn't set `:available-code-nodes`); same family of emitted trees, same success criteria

## Blocked by

PR01
