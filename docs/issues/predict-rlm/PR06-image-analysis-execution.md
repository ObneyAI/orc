# PR06 — `predict-rlm-image-tools` + image_analysis benchmark execution

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

Two pieces:

1. New Polylith brick `components/predict-rlm-image-tools/`:
   - `deps.edn` — no external deps beyond Clojure
   - `src/ai/obney/orc/predict_rlm_image_tools/interface.clj` exporting:
     - `(image->data-uri path)` — reads bytes, base64-encodes, prepends MIME prefix; returns `"data:image/<mime>;base64,..."`
     - `(task-definition)` — returns the task map used by the runner (instruction verbatim from predict-rlm `examples/image_analysis/signature.py` docstring, query from their `run.py`, blackboard schema declarations, evaluation criteria placeholder)
   - Detects MIME from file extension (png/jpg/webp); throws clearly on unknown

2. Sample data + task wiring under `development/bench/predict-rlm-comparison/`:
   - `references/predict-rlm/LICENSE` — verbatim from `/tmp/predict-rlm-read/predict-rlm/LICENSE`
   - `references/predict-rlm/image_analysis/signature.py.txt` — verbatim signature source
   - `references/predict-rlm/image_analysis/sample/input/<image>` — copied screenshot
   - `references/predict-rlm/image_analysis/sample/output/output.md` — copied for cross-reference
   - `tasks/image_analysis.clj` — declares the blackboard key with schema `[:string {:field-type :image}]` for the image data URI and `[:string]` for the query string; declares `:writes [:answer]`; loads the image via `image->data-uri` from the references path

The model gets:
- A goal: the verbatim signature docstring
- Two inputs in the blackboard: `:images` (single-image vector with `:field-type :image`) and `:query` (string)
- A small `:available-code-nodes` catalog (just `image->data-uri` if model wants to re-encode — likely unused since runner pre-encodes)

This benchmark uses **no `:code` nodes** in the emitted tree. The model is expected to emit a simple `[:llm {:reads [:images :query] :writes [:answer]}]` or compose multiple llm calls if it wants cross-image reasoning.

Run via the new comparison runner. Produces `.edn` + `.trace.jsonl` under `results/`.

## Acceptance criteria

- [ ] Component `predict-rlm-image-tools` exists with declared opt-in deps
- [ ] `(image->data-uri "test.png")` returns a valid data URI; MIME detected from extension
- [ ] Sample image + predict-rlm LICENSE + signature.py.txt + sample/output committed under `references/`
- [ ] Task file at `development/bench/predict-rlm-comparison/tasks/image_analysis.clj`
- [ ] From REPL: `(runner/start!)` then `(runner/run! image-analysis/task)` completes with `:status :success`
- [ ] Result EDN contains:
  - Non-empty `:generated-tree-raw`
  - `:outputs` with a non-empty `:answer` string
  - Non-empty `:iterations`, `:by-node`, `:node-trace`
- [ ] Trace JSONL is produced and parseable
- [ ] Inspect the trace: the model's LLM call to OpenRouter included an `{:type "image_url" :image_url {:url "data:image/..."}}` content part (i.e., vision happened for real)
- [ ] `:answer` content is sane on manual eyeball — references something visible in the input screenshot

## Blocked by

PR03, PR05
