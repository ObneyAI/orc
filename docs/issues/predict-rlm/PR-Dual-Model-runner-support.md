# PR-Dual-Model — Runner support for `:model` + `:sub-model` (apples-to-apples)

## Parent

`docs/prd/predict-rlm-benchmark-ports.md` (Dual-model support section under Implementation Decisions).

## What to build

predict-rlm's published numbers use `gpt-5.4` as main LM and `gpt-5.1` (= `openai/gpt-5.1-chat` on OpenRouter) as sub-LM. To match their setup for a true apples-to-apples comparison, the comparison runner needs dual-model support — different LMs for Phase-1 researcher (code-generation) vs Phase-2 sub-LLM calls (within emit-tree! trees).

Verified the OpenRouter availability: `openai/gpt-5.4`, `openai/gpt-5.1-chat`, `openai/gpt-5.1`, `openai/gpt-5` all return 200 with proper requests. The gpt-5 family requires `max_tokens >= 16` (which our defaults satisfy).

### Scope

Two changes:

1. **Runner config + task overrides.** Add an optional `:sub-model` field next to the existing `:model` field. Per-task definitions can override either or both. When `:sub-model` is unset, all sub-LLM calls inherit `:model` (today's behavior — single-model setup).

2. **Tree-walk injection at Phase-2 dispatch.** In `execute-repl-researcher-rlm` in `executor.clj`, between the model emitting the tree (Phase-1 → `:generated-tree` in sandbox-vars) and the call to `tree-executor/execute-tree` (Phase-2 entry), walk the canonical DSL with `clojure.walk/postwalk`. For each `(sheet/llm ...)` form whose keyword args lack `:model`, inject `:model sub-model` into the args list. The downstream Phase-2 leaf executor's `get-provider-with-model` already routes by `:model` if present, so no executor change required.

### Why tree-walk injection (not constructor-time)

- The model's emitted tree is a literal S-expr. It doesn't know about sub-models — its goal is goal-shaped, not config-shaped.
- The tree compiler in `rlm_tree_executor.clj` translates `:llm` → leaf with `:executor :ai`. If `:model` is set on the leaf, executor uses it; else it falls back to the parent's model.
- Today the "parent's model" is the repl-researcher's `:model`, which is the MAIN LM. So without sub-model injection, Phase-2 `:llm` calls inherit the main model — exactly what we want by default.
- The tree-walk injection ONLY fires when sub-model is configured. Backward compatible.

## Acceptance criteria

- [ ] `:sub-model` is a recognized key on the runner's `config` map, propagable via per-task `:sub-model` override.
- [ ] When `:sub-model` is unset, behaviour is unchanged (existing 5-benchmark suite still passes; existing image_analysis runs still produce the same shape of EDN).
- [ ] When `:sub-model` is set (e.g. `"openai/gpt-5.1-chat"`), the canonical tree passed to `tree-executor/execute-tree` has `:model "openai/gpt-5.1-chat"` on each `:llm` node that didn't already specify one.
- [ ] Unit test: a synthetic canonical tree containing `(sheet/llm ...)` nodes gets walked; each `:llm` form gets `:model` inserted; `:llm` forms with pre-existing `:model` are not overwritten.
- [ ] Live run: image_analysis with `:model "openai/gpt-5.4"` + `:sub-model "openai/gpt-5.1-chat"` completes successfully. EDN shows both models' usage (verifiable via `:by-node` per-leaf usage when leaf has a `:model` setting).
- [ ] Cost is reconciled against the OpenRouter dashboard for the new timestamp (since `gpt-5.4` rates differ substantially from `gemini-3-flash-preview` rates).
- [ ] PR07 report updated with a side-by-side row for the gpt-5.4/gpt-5.1-chat run alongside the original gemini-3-flash-preview run and predict-rlm's published numbers.

## Blocked by

None — can start immediately.

## Notes

- This issue is a comparison-quality enhancement, not a framework-soundness fix. The framework works correctly today with a single model. Dual-model is for matching predict-rlm exactly.
- `litellm` requires registering each `<provider>/<model>` combination; the runner already does this for the main `:model` via `litellm-router/register!`. Same pattern applies for `:sub-model` — register it at `start!` time so the leaf executor can resolve it.
- Pre-task model selection (e.g. one main model, two different sub-models for vision vs text) is out of scope here. If needed later, the same tree-walk pattern can be extended to inspect node-shape (image input → vision model; text input → text model) and inject accordingly. Today: one main + one sub.
