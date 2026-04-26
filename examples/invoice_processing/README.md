# invoice_processing

Port of [predict-rlm/examples/invoice_processing](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/invoice_processing).

**Original task** (from the predict-rlm signature docstring):

> Extract structured data from PDF invoices into an Excel spreadsheet.
> 1. Survey the invoices — file names, page counts, vendor names.
> 2. Render each page as an image and use predict() to extract vendor info,
>    line items, totals, and dates. Process pages in parallel with
>    asyncio.gather().
> 3. Build the Excel workbook using openpyxl with one sheet per invoice
>    + a Summary sheet.
> 4. Save the workbook and produce the result.

**This is the canonical demo of the RLM primitive** — predict-all over
pages mirrors `await asyncio.gather(*[predict(p) for p in pages])` 1:1.

**Inputs:** vector of PDF invoice paths
**Outputs:** structured `InvoiceExtractionResult` + an .xlsx workbook

## Skills used

- `pdf` — page-count, page-text, page-image-data-uri
- `xlsx` — write-workbook
