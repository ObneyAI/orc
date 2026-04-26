#!/usr/bin/env bash
# Orc-only sweep: Style A then Style B for all 5 tasks at N=3.
# Designed to run in parallel with the predict-rlm half of sweep_all.sh.
# Style A and Style B can't run concurrently (LMDB cache lock), so they're
# sequential here.

set -u

LOG=/Users/justinobney/dev/orc/bench/sweep_orc.log
ORC=/Users/justinobney/dev/orc

set -a
source "$ORC/.env"
set +a

cd "$ORC"

TASKS=(image_analysis invoice_processing document_redaction contract_comparison document_analysis)
N=3
MAX_ITERS=15

note() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

note "=== orc Style A sweep (N=$N) ==="
for task in "${TASKS[@]}"; do
  note "--- orc-A/$task ---"
  clj -X:dev:bench run-orc/run \
      :task ":$task" :style :a :runs $N :max-iterations $MAX_ITERS \
      :model '"openai/gpt-5"' :sub-lm-model '"openai/gpt-5-mini"' \
      :timeout-ms 600000 \
      >>"$LOG" 2>&1
  note "--- orc-A/$task done (exit $?) ---"
done

note "=== orc Style B sweep (N=$N) ==="
for task in "${TASKS[@]}"; do
  note "--- orc-B/$task ---"
  clj -X:dev:bench run-orc/run \
      :task ":$task" :style :b :runs $N :max-iterations $MAX_ITERS \
      :model '"openai/gpt-5"' :sub-lm-model '"openai/gpt-5-mini"' \
      :timeout-ms 600000 \
      >>"$LOG" 2>&1
  note "--- orc-B/$task done (exit $?) ---"
done

note "=== ORC SWEEP COMPLETE ==="
