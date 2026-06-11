# PR10 — `predict-rlm-invoice-tools` + invoice_processing execution

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

Three pieces:

1. New Polylith brick `components/predict-rlm-invoice-tools/`:
   - `deps.edn` — depends on `predict-rlm-pdf` (local) and declares `dk.ative/docjure` (Apache POI transitive)
   - `src/ai/obney/orc/predict_rlm_invoice_tools/interface.clj` exporting:
     - `(build-invoice-workbook {:keys [inputs context]})` — code-node function. Takes `:invoices` (vector of Invoice maps) + `:output-path` (string). Produces an `.xlsx` workbook with:
       - One sheet per invoice with columns: Description, Quantity, Unit Price, Amount
       - One Summary sheet with one row per invoice: Vendor, Invoice #, Date, Due Date, Subtotal, Tax, Total
       - Auto-sized columns
     - Returns `{:workbook-path "<absolute path>"}`
     - `(task-definition)` — task map with verbatim instruction from `examples/invoice_processing/signature.py`, output schema mirroring their `InvoiceExtractionResult` Pydantic shape, `:available-code-nodes` catalog string advertising `build-invoice-workbook`
   - `test/ai/obney/orc/predict_rlm_invoice_tools/interface_test.clj` — unit tests for `build-invoice-workbook`

2. Sample data:
   - `references/predict-rlm/invoice_processing/signature.py.txt` — verbatim
   - `references/predict-rlm/invoice_processing/sample/input/<pdfs>` — both invoice PDFs (acme + globaltech)
   - `references/predict-rlm/invoice_processing/sample/output/invoice_extraction.xlsx` — copied for structural cross-reference

3. Task wiring under `development/bench/predict-rlm-comparison/tasks/invoice_processing.clj`:
   - For each invoice PDF, pre-render pages via `predict-rlm-pdf/render-pages-as-data-uris` → blackboard keys like `:acme-pages`, `:globaltech-pages` with schema `[:vector {:field-type :image} :string]`
   - Compute output workbook path under `results/`
   - Declares `:writes [:invoices :total-amount :summary :workbook-path]`

Expected emitted-tree shape (for reviewer reference, NOT a hint to the model):

```
[:sequence
 [:map-each {:from :acme-pages :as :page :into :acme-page-data :max-concurrency 3}
  [:llm {:reads [:page] :instruction "..." :writes [:line-items :vendor-info]}]]
 [:aggregate {:from :acme-page-data :writes [:acme-extracted]}]
 [:llm {:reads [:acme-extracted] :instruction "synthesize invoice ..." :writes [:acme-invoice]}]
 ;; symmetric for globaltech
 [:llm {:reads [:acme-invoice :globaltech-invoice] :instruction "..."
        :writes [:invoices :total-amount :summary]}]
 [:code {:fn "ai.obney.orc.predict-rlm-invoice-tools.interface/build-invoice-workbook"
         :reads [:invoices :output-path]
         :writes [:workbook-path]}]
 [:final {:keys [:invoices :total-amount :summary :workbook-path]}]]
```

The model designs whatever it actually emits.

## Acceptance criteria

- [ ] Component `predict-rlm-invoice-tools` exists with declared opt-in deps (docjure + transitively predict-rlm-pdf)
- [ ] `build-invoice-workbook` unit tests pass:
  - Synthetic 2-invoice input → workbook file at expected path
  - Re-opening via docjure shows N+1 sheets (1 summary + N invoices)
  - Summary sheet contains expected vendor/total values
  - Each invoice sheet contains its declared line-item rows
- [ ] Sample PDFs + LICENSE + signature.py.txt + sample/output committed under `references/`
- [ ] Task file at `development/bench/predict-rlm-comparison/tasks/invoice_processing.clj`
- [ ] `(runner/run! invoice-processing/task)` completes with `:status :success`
- [ ] Result EDN contains:
  - Non-empty `:generated-tree-raw` with at least one `:code` node referencing `build-invoice-workbook`
  - `:outputs` includes structured `:invoices` validating against the Malli schema (each invoice has vendor-name, invoice-number, ISO date, ISO due-date, subtotal/tax/total floats, line-items)
  - `:workbook-path` points to a real file that exists on disk
  - Non-empty `:iterations`, `:by-node`, `:node-trace`
- [ ] Sanity check: open the produced workbook; sheets, columns, totals look right
- [ ] Trace JSONL produced; vision content blocks confirmed in the per-page LLM calls

## Blocked by

PR02, PR03, PR04, PR05
