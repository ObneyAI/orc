#!/usr/bin/env bash
# Run all 5 predict-rlm comparison benchmarks in sequence.
#
# Expected total runtime: ~6-10 minutes
# Expected total cost:    ~$1.30-2.10
#
# Order is fastest-first (good for early-failure feedback):
#   1. image_analysis        (~25-35s, ~$0.05)
#   2. document_redaction    (~30-60s, ~$0.10-0.15)
#   3. invoice_processing    (~25-40s, ~$0.05-0.10)
#   4. contract_comparison   (~50-90s, ~$0.15-0.25)
#   5. document_analysis     (~3-5min, ~$1.00-1.50  — the expensive one)
#
# Usage:
#   export OPENROUTER_API_KEY=sk-or-v1-...
#   ./scripts/run_all.sh

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_common.sh"
check_prerequisites

start_time=$(date +%s)

echo "${BOLD}${BLUE}Running all 5 predict-rlm comparison benchmarks${RESET}"
echo "Expected total runtime: 6-10 minutes / total cost ~\$1.30-2.10"
echo

"$SCRIPT_DIR/run_image_analysis.sh"
"$SCRIPT_DIR/run_document_redaction.sh"
"$SCRIPT_DIR/run_invoice_processing.sh"
"$SCRIPT_DIR/run_contract_comparison.sh"
"$SCRIPT_DIR/run_document_analysis.sh"

end_time=$(date +%s)
duration=$((end_time - start_time))

echo
echo "${BOLD}${GREEN}All 5 benchmarks complete.${RESET}"
echo "Total wall clock: $((duration / 60))m $((duration % 60))s"
echo
echo "Result EDNs land under:"
echo "  development/bench/predict-rlm-comparison/results/"
echo
echo "Clean comparison reports (committed):"
echo "  development/bench/predict-rlm-comparison/reports/00_index.md  (cross-benchmark synthesis)"
echo "  development/bench/predict-rlm-comparison/reports/01_image_analysis.md"
echo "  development/bench/predict-rlm-comparison/reports/02_document_redaction.md"
echo "  development/bench/predict-rlm-comparison/reports/03_invoice_processing.md"
echo "  development/bench/predict-rlm-comparison/reports/04_document_analysis.md"
echo "  development/bench/predict-rlm-comparison/reports/05_contract_comparison.md"
