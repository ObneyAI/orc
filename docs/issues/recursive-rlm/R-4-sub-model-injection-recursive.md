# R-4: Sub-model injection must fire in recursive mode

## Parent

`docs/prd/rlm-recursive-emit-tree.md`. Follow-up to R-1, surfaced by the recursive-mode benchmark experiment in `development/bench/predict_rlm_comparison/reports/recursive-mode-experiment.md`.

## What to build

The `PR-Dual-Model` machinery (`inject-sub-model` walking the canonical emit-tree! tree and adding `:model <sub-model>` to every `(sheet/llm ...)` form lacking explicit `:model`) works in TERMINAL mode but silently fails to fire in RECURSIVE mode. As a result, Phase-2 `:llm` leaves fall through to litellm's `:openrouter` default model (`google/gemini-3-flash-preview`) instead of the task-declared `:sub-model` (`openai/gpt-5.1-chat` in the predict-rlm benchmarks).

The apples-to-apples comparison framing in the predict-rlm benchmark reports assumes gpt-5.1-chat is doing the per-leaf work. In recursive mode that's currently false.

End-to-end behavior:
- When the repl-researcher node has `:rlm {:recursive? true :sub-model "openai/gpt-5.1-chat"}` (or `:sub-model` on the node), every Phase-2 `:llm` leaf in the model-emitted tree that doesn't specify its own `:model` gets routed through `gpt-5.1-chat`.
- Verifiable by inspecting the run's `:generated-tree` canonical form (the post-injection canonical tree should contain `:model "openai/gpt-5.1-chat"` adjacent to each `(sheet/llm ...)`).
- Also verifiable by the AI execution events: `:model` field should report `openai/gpt-5.1-chat` for Phase-2 leaves, not `google/gemini-3-flash-preview`.

### Evidence pointer

`results/document-redaction-recursive_2026-05-22_121601.edn` — iter 1's `:result` (the canonical tree) has ZERO `:model` occurrences in the `(sheet/llm ...)` forms. The 12 AI execution events in the trace.edn all show `:model "google/gemini-3-flash-preview-20251217"`.

## Acceptance criteria

- [ ] Unit test: with `:rlm {:recursive? true :sub-model "X"}` on the node, the canonical generated tree (post-injection) has `:model "X"` on every `(sheet/llm ...)` form that didn't originally specify a model.
- [ ] Unit test: with no `:sub-model` set, the canonical tree is unchanged (no-op preserved).
- [ ] Integration: live document_redaction recursive run shows `:model "openai/gpt-5.1-chat"` on Phase-2 AI execution events, not `google/gemini-3-flash-preview`.
- [ ] No regression on existing tests covering `inject-sub-model` in terminal mode.

## Blocked by

- R-3 (sanitization fix — without it, the recursive run dies at 1m and we can't fully verify the model attribution across the full run).

## Root-cause hypothesis

In `executor.clj` execute-repl-researcher-rlm, the sub-model lookup is:

```clojure
sub-model (or (get rlm-config :sub-model) (:sub-model node))
```

where `rlm-config` is `(:rlm node)` when that's a map, else `{}`. The runner builds the rlm-config with `:sub-model` set, but somewhere between the runner and execute-repl-researcher-rlm the value drops to nil. Need to trace the read-model projection of repl-researcher node config — specifically whether `:rlm`'s `:sub-model` survives event-store round-trip and read-model materialization.

(Verification: in the EDN, the iter 1 canonical tree has no `:model` keys, consistent with `inject-sub-model` being called with `nil` sub-model — the no-op branch.)

## Notes

- Terminal mode is unaffected; this is recursive-mode-only.
- Once fixed, the predict-rlm comparison reports on main are accurate as-published (because terminal mode WAS using the right models — they describe the terminal-mode runs).
