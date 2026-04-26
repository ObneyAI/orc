# document_analysis

Port of [predict-rlm/examples/document_analysis](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/document_analysis).

**Original task** (verbatim from the predict-rlm signature docstring):

> Analyze documents and produce a structured report.
> 1. Read the report criteria to understand what to extract.
> 2. Survey the documents — file names, page counts, document types.
> 3. Gather information by rendering pages as images and using predict() to
>    extract relevant content.
> 4. Produce the report following the criteria.

**Inputs:** vector of PDF paths + a criteria string
**Outputs:** structured `DocumentAnalysis` (markdown report + key dates +
key entities) + a DOCX rendering of the report

## Skills used

- `pdf` — page-count, page-text, page-image-data-uri (for vision sub-LM)
- `docx` — write-markdown-as-docx

## Files

- `schemas.clj`  — Malli ports of `KeyDate`, `KeyEntity`, `DocumentAnalysis`
- `pipeline.clj` — Style A workflow (survey → analyze → synthesize → render-docx)
- `agentic.clj`  — Style B workflow (single repl-researcher with `:rlm true`)
- `service.clj`  — `(execute-pipeline ctx inputs)` / `(execute-agentic ctx inputs)`
- `run.clj`      — REPL-runnable entry points
- `sample/input/YYJ-2025-Parking-Management-RFP.pdf` — copied from predict-rlm
