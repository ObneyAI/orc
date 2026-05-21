#!/usr/bin/env bash
# Shared bootstrap for predict-rlm comparison benchmark runners.
# Each per-benchmark script sources this file.
#
# Sets up:
# - Repo-root cwd (so :local/root deps resolve)
# - OPENROUTER_API_KEY presence check
# - clj presence check
# - Color codes for pretty output
# - run_benchmark() helper

set -euo pipefail

# --- find repo root (this script lives at <repo>/development/bench/predict-rlm-comparison/scripts/) ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
cd "$REPO_ROOT"

# --- color codes ---
if [[ -t 1 ]]; then
  RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BLUE=$'\033[34m'
  BOLD=$'\033[1m'; RESET=$'\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; BLUE=''; BOLD=''; RESET=''
fi

# --- prerequisite checks ---
check_prerequisites() {
  if ! command -v clj >/dev/null 2>&1; then
    echo "${RED}ERROR${RESET}: clj (Clojure CLI) not found on PATH."
    echo "Install: https://clojure.org/guides/install_clojure"
    exit 1
  fi

  if [[ -z "${OPENROUTER_API_KEY:-}" ]]; then
    echo "${RED}ERROR${RESET}: OPENROUTER_API_KEY environment variable not set."
    echo "Get a key at https://openrouter.ai and export it:"
    echo "  export OPENROUTER_API_KEY=sk-or-v1-..."
    exit 1
  fi
}

# --- run a benchmark task ---
# Args:
#   $1 — Clojure namespace (e.g. "predict-rlm-comparison.tasks.image-analysis")
#   $2 — Friendly benchmark name (e.g. "image_analysis")
run_benchmark() {
  local ns="$1"
  local friendly="$2"

  echo
  echo "${BOLD}${BLUE}=== predict-rlm comparison: ${friendly} ===${RESET}"
  echo "Namespace:       ${ns}"
  echo "Working dir:     $(pwd)"
  echo "OPENROUTER_API_KEY: ${OPENROUTER_API_KEY:0:18}..."
  echo

  clj -M:dev:test -e "
(require '[${ns} :as t])
(require '[predict-rlm-comparison.runner :as r])
(r/start!)
(let [result (r/run! t/task)]
  (println)
  (println \"========================================\")
  (println \"STATUS:\"  (:status result))
  (println \"DURATION:\" (:duration-ms result) \"ms\")
  (when-let [tok (get-in result [:usage :total-tokens])]
    (println \"TOTAL TOKENS:\" tok))
  (println \"========================================\"))
(r/stop!)"

  local exit_code=$?
  echo
  if [[ $exit_code -eq 0 ]]; then
    echo "${GREEN}Run complete.${RESET} Look under development/bench/predict-rlm-comparison/results/"
    echo "for the freshly-written ${friendly}_<timestamp>.edn and .trace.edn files."
  else
    echo "${RED}Run failed with exit code ${exit_code}.${RESET}"
    exit $exit_code
  fi
}
