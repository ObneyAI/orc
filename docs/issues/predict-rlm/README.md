# predict-rlm Benchmark Ports — Issue Tracker

Local issues tracking the work described in `docs/prd/predict-rlm-benchmark-ports.md`.

## Issues

| ID | Title | Type | Blocked by |
|---|---|---|---|
| [PR01](PR01-worktree-and-baseline.md) | Worktree from main + Phase 0 baseline re-run | AFK | none |
| [PR02](PR02-code-node-dsl-extension.md) | `:code` node support in `emit-tree!` DSL + tests | AFK | PR01 |
| [PR03](PR03-available-code-nodes-plumbing.md) | `:available-code-nodes` plumbing through `:rlm` config | AFK | PR01 |
| [PR04](PR04-predict-rlm-pdf-component.md) | `predict-rlm-pdf` component (render + extract, PDFBox) + tests | AFK | PR01 |
| [PR05](PR05-comparison-runner-with-capture.md) | New comparison runner under `development/bench/predict-rlm-comparison/` with capture extensions | AFK | PR01 |
| [PR06](PR06-image-analysis-execution.md) | `predict-rlm-image-tools` + image_analysis benchmark execution | AFK | PR03, PR05 |
| [PR07](PR07-image-analysis-report.md) | image_analysis hand-authored comparison report | HITL | PR06 |
| [PR08](PR08-document-redaction-execution.md) | `predict-rlm-redaction-tools` + document_redaction execution | AFK | PR02, PR03, PR04, PR05 |
| [PR09](PR09-document-redaction-report.md) | document_redaction hand-authored comparison report | HITL | PR08 |
| [PR10](PR10-invoice-processing-execution.md) | `predict-rlm-invoice-tools` + invoice_processing execution | AFK | PR02, PR03, PR04, PR05 |
| [PR11](PR11-invoice-processing-report.md) | invoice_processing hand-authored comparison report | HITL | PR10 |
| [PR12](PR12-aggregate-index-report.md) | Aggregate index report `00_index.md` | HITL | PR07, PR09, PR11 |

## Methodology

- **AFK** issues can be implemented and merged without human interaction (subject to TDD verification).
- **HITL** issues require hand-authored content based on inspection of real benchmark run data.
- Per the PRD, all four benchmark executions and tool tests must run live against real LLMs before any slice is considered complete. No assumptions, no fallbacks: bugs are traced to root cause in the proper grain/orc manner.

## Dependency graph

```
PR01 (worktree + baseline)
 ├─ PR02 (:code DSL)
 │   ├─ PR08 (document_redaction execution)
 │   │   └─ PR09 (report)
 │   └─ PR10 (invoice_processing execution)
 │       └─ PR11 (report)
 ├─ PR03 (:available-code-nodes)
 │   ├─ PR06 (image_analysis execution)
 │   │   └─ PR07 (report)
 │   ├─ PR08 ─┐
 │   └─ PR10 ─┤
 ├─ PR04 (predict-rlm-pdf)
 │   ├─ PR08 ─┤
 │   └─ PR10 ─┤
 └─ PR05 (comparison runner)
     ├─ PR06 ─┤
     ├─ PR08 ─┤
     └─ PR10 ─┘

PR07, PR09, PR11 → PR12 (aggregate index)
```
