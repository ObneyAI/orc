#!/usr/bin/env bash
# Run the contract_comparison benchmark — predict-rlm port.
#
# Expected runtime: ~50-90 seconds
# Expected cost:    ~$0.15-0.25 (gpt-5.4 main + gpt-5.1-chat sub via OpenRouter)
# Source task:      predict-rlm/examples/contract_comparison
# Reference output: references/predict-rlm/contract_comparison/sample/output/comparison-report.md
# Clean report:     reports/05_contract_comparison.md
#
# Usage:
#   export OPENROUTER_API_KEY=sk-or-v1-...
#   ./scripts/run_contract_comparison.sh
#
# Compares microFIT v2.0 vs v3.1.1 (Ontario Power Authority feed-in tariff
# contracts). The model typically designs a tree with parallel per-document
# structural surveys + adversarial verification + deterministic :code
# synthesis — running in roughly 6× less wall clock than predict-rlm
# published while identifying the same headline change (Domestic Content
# requirements removed) PLUS additional material findings (OPA discretion
# expansion, emergency data sharing provision).
#
# The result EDN's :outputs :report field contains an 11K-char markdown
# comparison report; :section-diffs and :key-differences are structured.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
check_prerequisites
run_benchmark "predict-rlm-comparison.tasks.contract-comparison" "contract_comparison"
