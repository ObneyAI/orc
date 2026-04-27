#!/usr/bin/env bash
# Driver-recovery EXPERIMENTAL sweep:
#   - max-turns 6 (was 3)
#   - cumulative prior-attempts memory across runs (bench/.driver-cache/<task>.edn)
# Run AFTER the baseline round-6 sweep so we can ablate.
# Same 3 tasks × N=3 as the baseline.
set -u
LOG=/Users/justinobney/dev/orc/bench/sweep_driver_seeded.log
ORC=/Users/justinobney/dev/orc
set -a; source "$ORC/.env"; set +a
cd "$ORC"
# Wipe cache so each task starts fresh (cumulative learning happens
# across the 3 runs of THIS sweep, not bleeding from prior experiments).
rm -rf "$ORC/bench/.driver-cache"
TASKS=(invoice_processing document_redaction)
# document_analysis omitted: 65,536-max_tokens propose call exceeds remaining
# OpenRouter weekly-budget headroom on every retry. Re-add when budget fix
# (driver max_tokens override) lands.
N=3
note() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
note "=== orc Driver-recovery SEEDED (max-turns=6, cumulative memory, N=$N) ==="
for task in "${TASKS[@]}"; do
  note "--- driver-seeded/$task ---"
  clj -X:dev:bench run-orc/run \
      :task ":$task" :style :driver :runs $N :max-iterations 15 \
      :model '"openai/gpt-5"' :sub-lm-model '"openai/gpt-5-mini"' \
      :timeout-ms 600000 \
      >>"$LOG" 2>&1
  note "--- driver-seeded/$task done (exit $?) ---"
done
note "=== SEEDED SWEEP COMPLETE ==="
