#!/usr/bin/env sh
# Waits until the demo data is fully analysed (#52): the blind-spot report's sample size reaches 20 (all
# 20 demo games DONE) and the 5 demo findings exist. Used by CI and for local runs (README).
# Usage: e2e/wait-for-demo.sh [base-url] [timeout-seconds]
set -eu
base="${1:-http://localhost:8081}"
timeout="${2:-900}"
start=$(date +%s)
while :; do
  patterns=$(curl -fsS "$base/api/patterns" 2>/dev/null || true)
  findings=$(curl -fsS "$base/api/findings?size=1" 2>/dev/null || true)
  case "$patterns" in
    *'"sampleSize":20'*)
      case "$findings" in
        *'"totalItems":5'*) echo "Demo data analysed: $patterns"; exit 0 ;;
      esac ;;
  esac
  if [ $(( $(date +%s) - start )) -ge "$timeout" ]; then
    echo "Timed out after ${timeout}s waiting for the demo analysis. Last report: ${patterns:-none}" >&2
    exit 1
  fi
  sleep 10
done
