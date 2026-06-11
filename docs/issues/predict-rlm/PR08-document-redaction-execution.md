# PR08 — `predict-rlm-redaction-tools` + document_redaction execution

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

Three pieces:

1. New Polylith brick `components/predict-rlm-redaction-tools/`:
   - `deps.edn` — depends on `predict-rlm-pdf` (local), no other JVM deps
   - `src/ai/obney/orc/predict_rlm_redaction_tools/interface.clj` exporting:
     - `(apply-redactions {:keys [inputs]})` — *deep, pure transformation* code-node function. Takes `:page-texts` (vector of strings) and `:targets` (vector of vectors of `{:text :category :reason}` maps, outer index = page). Returns `{:redacted-text-vector ... :total-redactions N :page-summaries [...]}`. Substring replacement per page; no I/O.
     - `(task-definition)` — task map with verbatim instruction from `examples/document_redaction/signature.py`, output schema mirroring their `RedactionResult`, and the `:available-code-nodes` catalog string advertising `apply-redactions`
   - `test/ai/obney/orc/predict_rlm_redaction_tools/interface_test.clj` — unit tests for `apply-redactions`

2. Sample data + ground truth:
   - `references/predict-rlm/document_redaction/signature.py.txt` — verbatim
   - `references/predict-rlm/document_redaction/sample/input/<pdf>` — copied employment agreement PDF
   - `references/predict-rlm/document_redaction/sample/output/output.md` — copied for reference
   - `ground-truth/document_redaction.edn` — parsed-out structured form of the 89 targets predict-rlm published in their `output.md`. Shape: `[{:page N :text "..." :category "..."} ...]`. Used by the report writer to compute target overlap.

3. Task wiring under `development/bench/predict-rlm-comparison/tasks/document_redaction.clj`:
   - Pre-renders pages via `predict-rlm-pdf/render-pages-as-data-uris` → `:document-pages` blackboard key with schema `[:vector {:field-type :image} :string]`
   - Pre-extracts per-page text via `predict-rlm-pdf/extract-pages-as-text` → `:document-page-texts` blackboard key with schema `[:vector :string]`
   - Loads the criteria string (from predict-rlm's run.py) into `:criteria` blackboard key
   - Declares `:writes [:total-redactions :page-summaries :targets :redacted-text-vector]`

The expected emitted-tree shape (model designs it; documenting expectation for review):

```
[:sequence
 [:map-each {:from :document-pages :as :page :into :per-page-targets :max-concurrency 3}
  [:llm {:reads [:page :criteria]
         :instruction "..."
         :writes [:targets]}]]
 [:aggregate {:from :per-page-targets :writes [:all-targets]}]
 [:code {:fn "ai.obney.orc.predict-rlm-redaction-tools.interface/apply-redactions"
         :reads [:document-page-texts :all-targets]
         :writes [:redacted-text-vector :total-redactions :page-summaries]}]
 [:final {:keys [:total-redactions :page-summaries :targets :redacted-text-vector]}]]
```

The model is **never told** to emit this shape. It designs whatever it wants from the goal + `:available-code-nodes` catalog. The expected shape above is for the reviewer's sanity check during PR09.

## Acceptance criteria

- [ ] Component `predict-rlm-redaction-tools` exists with declared opt-in dep on `predict-rlm-pdf`
- [ ] `apply-redactions` unit tests pass:
  - Synthetic 3-page text + 3-vector of targets → expected redacted output
  - `:total-redactions` equals sum of per-page counts
  - Every target's `:text` is NOT a substring of the corresponding redacted page text
  - `:page-summaries` reflects accurate counts and category lists per page
  - Idempotence: applying twice doesn't break
  - Empty targets yields untouched text and zero count
- [ ] Sample PDF + LICENSE + signature.py.txt + sample/output committed under `references/`
- [ ] Ground-truth EDN committed; parsed from predict-rlm's `output.md`; structure validates against a small Malli schema (or visual inspection if you prefer)
- [ ] Task file at `development/bench/predict-rlm-comparison/tasks/document_redaction.clj`
- [ ] `(runner/run! document-redaction/task)` completes with `:status :success`
- [ ] Result EDN contains:
  - Non-empty `:generated-tree-raw` containing at least one `:code` node referencing `apply-redactions` (validates PR02 end-to-end)
  - `:outputs` includes `:total-redactions` (integer ≥1), `:page-summaries` (vector), `:targets` (vector), `:redacted-text-vector` (vector matching page count)
  - Non-empty `:iterations`, `:by-node`, `:node-trace`
- [ ] Sanity check: no target's `:text` appears un-redacted in the corresponding `:redacted-text-vector` entry
- [ ] Trace JSONL produced; vision content blocks confirmed in the LLM-call entries (vision happened for real)

## Blocked by

PR02, PR03, PR04, PR05
