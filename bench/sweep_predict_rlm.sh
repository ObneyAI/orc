#!/usr/bin/env bash
# predict-rlm sweep: 5 tasks × N=3 against the same model pair as orc.
# Designed to run in parallel with sweep_orc_only.sh (different process,
# different repo, both share the OpenRouter key).

set -u

LOG=/Users/justinobney/dev/orc/bench/sweep_predict.log
PREDICT_RLM=/Users/justinobney/dev/predict-rlm

set -a
source /Users/justinobney/dev/orc/.env   # same OPENROUTER_API_KEY as orc
set +a

cd "$PREDICT_RLM"

TASKS=(image_analysis invoice_processing document_redaction contract_comparison document_analysis)
N=3
MAX_ITERS=15

note() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

note "=== predict-rlm sweep (N=$N, max-iter=$MAX_ITERS) ==="
for task in "${TASKS[@]}"; do
  note "--- predict-rlm/$task ---"
  uv run bench/run_predict.py "$task" --runs $N --max-iterations $MAX_ITERS \
      --model openrouter/openai/gpt-5 \
      --sub-lm-model openrouter/openai/gpt-5-mini \
      >>"$LOG" 2>&1
  note "--- predict-rlm/$task done (exit $?) ---"
done

note "=== PREDICT-RLM SWEEP COMPLETE ==="
