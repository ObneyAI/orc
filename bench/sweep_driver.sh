#!/usr/bin/env bash
# Driver-recovery sweep: 3 tasks (those with degradations defined) × N=3.
# Each driver run is multi-turn so this is the slowest sweep — ~8 min/run
# best case, can hit 15+ if the driver iterates the full max-turns budget.
set -u
LOG=/Users/justinobney/dev/orc/bench/sweep_driver.log
ORC=/Users/justinobney/dev/orc
set -a; source "$ORC/.env"; set +a
cd "$ORC"
TASKS=(invoice_processing document_redaction document_analysis)
N=3
note() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
note "=== orc Driver-recovery sweep (N=$N) ==="
for task in "${TASKS[@]}"; do
  note "--- driver/$task ---"
  clj -X:dev:bench run-orc/run \
      :task ":$task" :style :driver :runs $N :max-iterations 15 \
      :model '"openai/gpt-5"' :sub-lm-model '"openai/gpt-5-mini"' \
      :timeout-ms 600000 \
      >>"$LOG" 2>&1
  note "--- driver/$task done (exit $?) ---"
done
note "=== DRIVER SWEEP COMPLETE ==="
