#!/usr/bin/env sh
# Waits until the demo data is ready for the end-to-end tests (#52, #97):
#   1. fully analysed: the blind-spot report's sample size reaches 20 (all 20 demo games DONE) and the 5 demo
#      findings exist;
#   2. its drills prepared: lesson 1 of the journey needs no more positions (positionsNeeded 0 in
#      GET /api/journey). The drills are prepared in the background after the analysis, about a minute each,
#      so until all 5 exist the map truthfully says lesson 1 still needs positions.
# Used by CI and for local runs (README). One timeout covers both waits.
# Usage: e2e/wait-for-demo.sh [base-url] [timeout-seconds]
set -eu
base="${1:-http://localhost:8081}"
timeout="${2:-900}"
start=$(date +%s)

timed_out() {
  [ $(( $(date +%s) - start )) -ge "$timeout" ]
}

while :; do
  patterns=$(curl -fsS "$base/api/patterns" 2>/dev/null || true)
  findings=$(curl -fsS "$base/api/findings?size=1" 2>/dev/null || true)
  case "$patterns" in
    *'"sampleSize":20'*)
      case "$findings" in
        *'"totalItems":5'*) echo "Demo data analysed after $(( $(date +%s) - start ))s: $patterns"; break ;;
      esac ;;
  esac
  if timed_out; then
    echo "Timed out after ${timeout}s waiting for the demo analysis. Last report: ${patterns:-none}" >&2
    exit 1
  fi
  sleep 10
done

while :; do
  journey=$(curl -fsS "$base/api/journey" 2>/dev/null || true)
  # The first lesson's summary is a flat JSON object: read its positionsNeeded.
  needed=$(printf '%s' "$journey" | sed -n 's/.*"lessons":\[{[^}]*"positionsNeeded":\([0-9]*\).*/\1/p')
  if [ "$needed" = "0" ]; then
    echo "Demo drills prepared after $(( $(date +%s) - start ))s: lesson 1 needs no more positions."
    exit 0
  fi
  if timed_out; then
    echo "Timed out after ${timeout}s waiting for the demo drills. Lesson 1 still needs: ${needed:-unknown}" >&2
    exit 1
  fi
  sleep 10
done
