#!/usr/bin/env bash
# Chaos test (ARCHITECTURE.md, Testing): submit sleep-task workflows while killing a
# random worker container every ~10s. The compose restart policy brings workers back;
# every submitted workflow must still reach SUCCEEDED and the DLQ must stay empty.
#
# Usage: scripts/chaos.sh [duration-seconds]     (default 60)
# Prereq: stack up with >=2 workers, short lease knobs recommended:
#   ENGINE_LEASE_DURATION=10s ENGINE_REAPER_INTERVAL=2s \
#     docker compose up -d --build --scale worker=2
set -euo pipefail

DURATION="${1:-60}"
API="${API:-http://localhost:8080}"
SUBMIT_INTERVAL="${SUBMIT_INTERVAL:-5}"
KILL_INTERVAL="${KILL_INTERVAL:-10}"
SETTLE_TIMEOUT="${SETTLE_TIMEOUT:-180}"

instances=()

submit_workflow() {
  local id
  id=$(curl -sf "$API/workflows" -H 'content-type: application/json' -d '{
    "name": "chaos-sleep", "version": 1,
    "dag": { "tasks": [ {
      "key": "nap", "type": "sleep", "input": { "seconds": 8 },
      "retryPolicy": { "maxAttempts": 5, "backoffStrategy": "fixed", "initialDelaySeconds": 1 }
    } ] }
  }' | grep -o '"instanceId":"[^"]*"' | cut -d'"' -f4)
  instances+=("$id")
  echo "submitted $id"
}

kill_random_worker() {
  local victim
  victim=$(docker ps -q --filter "label=com.docker.compose.service=worker" --filter status=running | shuf -n 1)
  if [[ -z "$victim" ]]; then
    echo "no running worker to kill"
    return
  fi
  docker kill "$victim" > /dev/null
  echo "killed worker $victim"
}

workflow_status() {
  curl -s "$API/workflows/$1" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4
}

echo "chaos run: ${DURATION}s, submit every ${SUBMIT_INTERVAL}s, kill a worker every ~${KILL_INTERVAL}s"
start=$(date +%s)
next_kill=$KILL_INTERVAL
while (( $(date +%s) - start < DURATION )); do
  submit_workflow
  elapsed=$(( $(date +%s) - start ))
  if (( elapsed >= next_kill )); then
    kill_random_worker
    next_kill=$(( elapsed + KILL_INTERVAL ))
  fi
  sleep "$SUBMIT_INTERVAL"
done

echo "chaos over — waiting for ${#instances[@]} workflow(s) to settle (timeout ${SETTLE_TIMEOUT}s)"
deadline=$(( $(date +%s) + SETTLE_TIMEOUT ))
pending=("${instances[@]}")
while (( ${#pending[@]} > 0 )) && (( $(date +%s) < deadline )); do
  sleep 5
  still_pending=()
  for id in "${pending[@]}"; do
    if [[ "$(workflow_status "$id")" != "SUCCEEDED" ]]; then
      still_pending+=("$id")
    fi
  done
  pending=("${still_pending[@]+"${still_pending[@]}"}")
  echo "  ${#pending[@]} still running"
done

dead_letters=$(curl -s "$API/dead-letters")

echo
echo "=== chaos summary ==="
echo "submitted: ${#instances[@]}, succeeded: $(( ${#instances[@]} - ${#pending[@]} ))"
if (( ${#pending[@]} > 0 )); then
  echo "FAIL: ${#pending[@]} workflow(s) never reached SUCCEEDED: ${pending[*]}"
  exit 1
fi
if [[ "$dead_letters" != "[]" ]]; then
  echo "FAIL: dead-letter queue is not empty: $dead_letters"
  exit 1
fi
echo "PASS: every workflow SUCCEEDED and the dead-letter queue is empty"
