#!/usr/bin/env bash
# Run the image_analysis benchmark — predict-rlm port.
#
# Expected runtime: ~25-35 seconds
# Expected cost:    ~$0.05 (gpt-5.4 main + gpt-5.1-chat sub via OpenRouter)
# Source task:      predict-rlm/examples/image_analysis
# Reference output: references/predict-rlm/image_analysis/sample/output/output.md
# Clean report:     reports/01_image_analysis.md
#
# Usage:
#   export OPENROUTER_API_KEY=sk-or-v1-...
#   ./scripts/run_image_analysis.sh
#
# The result EDN is written to results/image-analysis_<timestamp>.edn.
# Compare your output's letter counts to predict-rlm's published table in
# references/predict-rlm/image_analysis/sample/output/output.md.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
check_prerequisites
run_benchmark "predict-rlm-comparison.tasks.image-analysis" "image_analysis"
