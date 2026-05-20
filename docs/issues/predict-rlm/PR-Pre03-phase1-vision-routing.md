# PR-Pre03 — Fix Phase-1 sub-LLM image routing (`:field-type :image` not propagated)

## Parent

`docs/prd/predict-rlm-benchmark-ports.md` (Solution item #3 in revised problem statement; documented in detail under "Pending (PR-Pre03)" in Implementation Decisions).

## What to build

Bug: when the Phase-1 RLM sandbox's `(llm "name" :reads [:image-key] ...)` primitive runs, it builds a dscloj module whose `:inputs` carry only `{:name k :spec :any :description ...}` — **the `:field-type :image` on the blackboard schema is not propagated.** Result: dscloj's `build-message-content` finds zero `:type :image` inputs, falls through to non-multimodal, and embeds the base64 data URI in the prompt as text. OpenRouter then character-counts that into ~480K tokens (real image-tile billing for the same image: ~1K tokens) and the model receives the image as a giant base64 string instead of as a real image_url content block.

Diagnostic evidence captured during PR07:
- Direct OpenRouter call with the screenshot + `image_url` content block: **1,126 prompt tokens, $0.000578.**
- Direct litellm router (skipping dscloj's primitive wrapper) with the same content block: **1,120 prompt tokens.**
- Through dscloj/predict via the Phase-1 sandbox `(llm ...)` primitive: **477,010 prompt tokens.**

The fix is in `execute-llm-primitive` in `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_sandbox.clj`. For each `:reads` key, look up the blackboard entry's Malli schema, extract `:field-type` via `(m/properties schema)` (mirroring what `executor.clj` `schema-field-type` already does for Phase-2 leaf nodes), and propagate it as `:type` on the dscloj module's input field. Phase-2 `:llm` nodes already route correctly via `executor.clj/build-module`; this fix only touches the Phase-1 sandbox path.

## Acceptance criteria

- [ ] `execute-llm-primitive` looks up each `:reads` key's blackboard schema (or sandbox-vars schema) and extracts `:field-type` if present.
- [ ] When `:field-type :image` is present, the corresponding dscloj module input field gets `:type :image`.
- [ ] Unit test: with a synthetic blackboard entry carrying schema `[:string {:field-type :image}]` and a stub dscloj/predict (`with-redefs`), assert the module's `:inputs` includes the image-typed field and the request content sent to litellm uses `image_url` content-block format (or that `build-message-content` upstream observes the image input).
- [ ] Re-run image_analysis through the comparison runner; verify reported `:usage` for the vision sub-LLM call is on the order of 1K-5K tokens (not 480K).
- [ ] No regression on `contract_comparison_validated` (text-only path unaffected).
- [ ] `rlm_dsl_test` + `rlm_mode_test` + comparison `runner-test` all GREEN in isolation.

## Blocked by

None — can start immediately.

## Why this is critical

- Token-cost claims in any predict-rlm comparison report are ~3 orders of magnitude wrong until this lands.
- Vision quality is degraded because Gemini sees a base64 string instead of an actual image. The PR06 image_analysis run extracted 211 letters where predict-rlm extracted 1,343 — most of that gap is likely attributable to this bug, not to methodology choice.
- Affects ALL image-using benchmarks (PR06, PR10) and any future benchmark that uses Phase-1 direct execution with image inputs.
