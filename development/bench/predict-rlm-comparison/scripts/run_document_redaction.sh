#!/usr/bin/env bash
# Run the document_redaction benchmark — predict-rlm port.
#
# Expected runtime: ~30-60 seconds
# Expected cost:    ~$0.10-0.15 (gpt-5.4 main + gpt-5.1-chat sub via OpenRouter)
# Source task:      predict-rlm/examples/document_redaction
# Reference output: references/predict-rlm/document_redaction/sample/output/output.md
# Clean report:     reports/02_document_redaction.md
#
# Usage:
#   export OPENROUTER_API_KEY=sk-or-v1-...
#   ./scripts/run_document_redaction.sh
#
# The result EDN is written to results/document-redaction_<timestamp>.edn.
# Compare:
#   - :total-redactions to predict-rlm's 89 (theirs) / 92 (ORC headline)
#   - :targets-applied to the 84 strict PII items in the headline run
# See also results/document_redaction_artifacts/before_after.md for a
# human-readable side-by-side of the original vs redacted pages from
# the committed headline run.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
check_prerequisites
run_benchmark "predict-rlm-comparison.tasks.document-redaction" "document_redaction"
