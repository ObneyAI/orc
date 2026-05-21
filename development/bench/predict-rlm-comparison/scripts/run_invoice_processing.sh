#!/usr/bin/env bash
# Run the invoice_processing benchmark — predict-rlm port.
#
# Expected runtime: ~25-40 seconds
# Expected cost:    ~$0.05-0.10 (gpt-5.4 main + gpt-5.1-chat sub via OpenRouter)
# Source task:      predict-rlm/examples/invoice_processing
# Reference output: references/predict-rlm/invoice_processing/sample/output/
# Clean report:     reports/03_invoice_processing.md
#
# Usage:
#   export OPENROUTER_API_KEY=sk-or-v1-...
#   ./scripts/run_invoice_processing.sh
#
# This benchmark produces an actual .xlsx workbook at
#   results/invoice_extraction.xlsx
# Compare it side-by-side with predict-rlm's published reference:
#   references/predict-rlm/invoice_processing/sample/output/invoice_extraction.xlsx
#
# Both workbooks have 3 sheets (Summary + per-vendor); same 7-column Summary
# layout; per-invoice line-item columns Description/Quantity/Unit Price/Amount.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
check_prerequisites
run_benchmark "predict-rlm-comparison.tasks.invoice-processing" "invoice_processing"

echo
echo "Output workbook: development/bench/predict-rlm-comparison/results/invoice_extraction.xlsx"
echo "Reference workbook (predict-rlm): development/bench/predict-rlm-comparison/references/predict-rlm/invoice_processing/sample/output/invoice_extraction.xlsx"
echo
echo "Open both side-by-side to compare:"
echo "  open development/bench/predict-rlm-comparison/results/invoice_extraction.xlsx"
echo "  open development/bench/predict-rlm-comparison/references/predict-rlm/invoice_processing/sample/output/invoice_extraction.xlsx"
