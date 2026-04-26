# document_redaction

Port of [predict-rlm/examples/document_redaction](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/document_redaction).

**Inputs:** vector of PDF paths + a redaction-criteria string
**Outputs:** vector of redacted PDF paths + structured `RedactionResult`

## Skills used

- `pdf` — page-text, page-image-data-uri, search-text, redact-rects
- The agentic style additionally bundles in the `redaction.md` skill prose
  (port of predict-rlm's `redaction_skill`)
