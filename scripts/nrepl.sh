#!/usr/bin/env bash

# Start nREPL with dev and test aliases and common middleware
# Usage: ./scripts/nrepl.sh [port]

set -euo pipefail

PORT="${1:-7888}"

cd "$(dirname "$0")/.."

LOG_DIR="./.nrepl-logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/nrepl-$PORT.log"

echo "Starting nREPL on port $PORT with :dev and :test aliases..."
echo "Logging to: $LOG_FILE"

# Tee output to both terminal and log file so the user sees it live
# while Claude can tail the log file. exec keeps signals (Ctrl+C) clean.
exec > >(tee "$LOG_FILE") 2>&1

clojure \
  -J--add-opens=java.base/java.nio=ALL-UNNAMED \
  -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -A:dev:test \
  -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}
                  cider/cider-nrepl {:mvn/version "0.50.2"}
                  refactor-nrepl/refactor-nrepl {:mvn/version "3.10.0"}}}' \
  -M -m nrepl.cmdline \
  --port "$PORT" \
  --middleware '[cider.nrepl/cider-middleware refactor-nrepl.middleware/wrap-refactor]'
