# PR09 — document_redaction hand-authored comparison report

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

Hand-authored markdown report at `development/bench/predict-rlm-comparison/reports/02_document_redaction.md` following the PRD's skeleton, with a particular focus on:

- **Target-overlap analysis** against `ground-truth/document_redaction.edn` (the 89 published predict-rlm targets). Compute precision/recall by exact-string match per category. Record true positives, false positives, false negatives.
- **The `:code` node walkthrough** — this is the first benchmark to actually exercise the `:code` DSL extension end-to-end. Quote the `apply-redactions` invocation from `:node-trace`, show inputs (excerpt) and outputs (excerpt), confirm the deterministic transformation matches what we'd expect by hand.
- **Vision IO inspection** — for each per-page LLM call in the trace, confirm a vision content block was sent; spot-check the model's per-page target list against the actual page content.
- **Fidelity caveat** — text-mode redaction vs PDF-native (`page.apply_redactions()`). Explain why the comparison is still apples-to-apples on the RLM ability, not on the output medium.

## Acceptance criteria

- [ ] Report exists at `development/bench/predict-rlm-comparison/reports/02_document_redaction.md`
- [ ] All PRD skeleton sections populated
- [ ] Target-overlap analysis: precision/recall numbers, breakdown by category, list of false positives and false negatives
- [ ] Emitted tree shows the `:code` node referencing `apply-redactions`; report confirms the model chose to compose deterministic redaction into its tree
- [ ] Sub-LLM walkthrough cites JSONL trace entries for each per-page vision call
- [ ] Hand-authored quality assessment includes ≥3 spot-check claims with source references
- [ ] Fidelity caveat: text-mode vs PDF-native redaction explicitly called out
- [ ] Reproducibility section names result files

## Blocked by

PR08
