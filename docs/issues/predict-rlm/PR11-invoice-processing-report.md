# PR11 — invoice_processing hand-authored comparison report

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

Hand-authored markdown report at `development/bench/predict-rlm-comparison/reports/03_invoice_processing.md` following the PRD skeleton, with focus on:

- **Workbook structure equivalence** — open our produced `.xlsx` and predict-rlm's sample `invoice_extraction.xlsx` side-by-side. Confirm equivalent sheets, columns, and summary structure. Document any deviations.
- **Extracted-data accuracy** — for each invoice, spot-check 5-7 facts (vendor name, invoice number, date, due-date, subtotal, tax, total, line-item count, ≥2 line-item descriptions/amounts) against the source PDFs by direct eyeballing.
- **Tree analysis** — did the model fan out vision calls per page? Did it use map-each + aggregate? Did it correctly invoke `build-invoice-workbook` at the end of its tree?
- **Fidelity caveat** — model-wrote-openpyxl (predict-rlm) vs deterministic-code-node-build (ours). Their tokens include workbook-building code generation; ours don't. Note this asymmetry and what it means for the token comparison.

## Acceptance criteria

- [ ] Report exists at `development/bench/predict-rlm-comparison/reports/03_invoice_processing.md`
- [ ] All PRD skeleton sections populated
- [ ] Workbook structure comparison table (sheets, column headers, row counts) vs predict-rlm sample
- [ ] Per-invoice spot-check table covering ≥5 fields per invoice with source-PDF citations
- [ ] Tree analysis names the map-each / aggregate / code-node usage by reference to `:generated-tree-raw`
- [ ] Sub-LLM walkthrough cites JSONL trace entries for vision per-page calls and the final synthesis
- [ ] Fidelity caveat: model-wrote-code vs deterministic-code-node-build clearly explained, with implications for the token comparison
- [ ] Reproducibility section names result files

## Blocked by

PR10
