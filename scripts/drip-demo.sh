#!/usr/bin/env bash
# M4 reference demo: drip email — durable timers + a cron schedule.
#
#     send-welcome ──(wait)──► send-tips ──(wait)──► send-offer
#
# The waits are `delaySeconds` on the dependent tasks, so the engine holds each step in Postgres
# until it is due; no worker sleeps on a lease and nothing is lost if the engine restarts mid-wait.
# The whole chain is registered as a *cron schedule* rather than submitted directly, so the demo
# proves the M4 story end to end: a schedule fires on its own, and the run it starts then unfolds
# across time.
#
# Production drip sequences wait days. The demo compresses that to seconds (DELAY) so it is
# watchable — the mechanism is identical, only the constant differs.
#
# Usage: scripts/drip-demo.sh
# Prereq: stack up (engine + at least one worker running the sample worker):
#   docker compose up -d --build
set -euo pipefail

API="${API:-http://localhost:8080}"
DELAY="${DELAY:-10}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"
TIMEOUT="${TIMEOUT:-180}"
# Every minute, on the minute. The demo waits for the next tick rather than submitting anything.
CRON="${CRON:-0 * * * * *}"

SCHEDULE_NAME="drip-demo-$$"

cleanup() {
  if [[ -n "${schedule_id:-}" ]]; then
    curl -sf -X DELETE "$API/schedules/$schedule_id" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

create_schedule() {
  curl -sf "$API/schedules" -H 'content-type: application/json' -d '{
    "name": "'"$SCHEDULE_NAME"'",
    "workflowName": "drip-email", "workflowVersion": 1,
    "cronExpression": "'"$CRON"'", "timezone": "UTC",
    "dag": { "tasks": [
      { "key": "welcome", "type": "send-welcome" },
      { "key": "tips",  "type": "send-tips",  "dependsOn": ["welcome"], "delaySeconds": '"$DELAY"' },
      { "key": "offer", "type": "send-offer", "dependsOn": ["tips"],    "delaySeconds": '"$DELAY"' }
    ] }
  }' | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
}

schedule_json() {
  curl -s "$API/schedules/$1"
}

json_field() {
  echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

workflow_status() {
  echo "$1" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4
}

task_line() {
  echo "$1" \
    | grep -o '"taskKey":"[^"]*","type":"[^"]*","status":"[^"]*","attempts":[0-9]*' \
    | sed -E 's/"taskKey":"([^"]*)","type":"[^"]*","status":"([^"]*)","attempts":([0-9]*)/\1=\2(a\3)/' \
    | paste -sd' ' -
}

echo "registering drip-email schedule ($CRON, delay ${DELAY}s between sends) at $API ..."
schedule_id=$(create_schedule)
if [[ -z "$schedule_id" ]]; then
  echo "FAIL: could not create the schedule (is the engine up at $API?)"
  exit 1
fi
next=$(json_field "$(schedule_json "$schedule_id")" nextFireAt)
echo "registered $schedule_id — next fire at $next"
echo "waiting for the schedule to fire on its own (no submit) ..."
echo

start=$(date +%s)
deadline=$(( start + TIMEOUT ))

# Phase 1: wait for the sweeper to fire the schedule. lastFiredAt flipping from null is the signal.
while (( $(date +%s) < deadline )); do
  fired=$(json_field "$(schedule_json "$schedule_id")" lastFiredAt)
  if [[ -n "$fired" ]]; then
    echo "[$(( $(date +%s) - start ))s] schedule fired for $fired"
    break
  fi
  sleep "$POLL_INTERVAL"
done

if [[ -z "${fired:-}" ]]; then
  echo "FAIL: schedule did not fire within ${TIMEOUT}s"
  exit 1
fi

# Phase 2: watch the run it started unfold across the delays. The engine assigns the instance id,
# so ask the schedule which run it started.
instance_id=$(curl -s "$API/schedules/$schedule_id/runs?limit=1" \
  | grep -o '"workflowInstanceId":"[^"]*"' | head -1 | cut -d'"' -f4)
if [[ -z "$instance_id" ]]; then
  echo "FAIL: schedule reports a fire but no run is recorded against it"
  exit 1
fi

echo "watching run $instance_id"
echo
last=""
while (( $(date +%s) < deadline )); do
  json=$(curl -s "$API/workflows/$instance_id")
  status=$(workflow_status "$json")
  line=$(task_line "$json")
  if [[ "$line" != "$last" ]]; then
    printf '[%3ss] %-9s | %s\n' "$(( $(date +%s) - start ))" "$status" "$line"
    last="$line"
  fi
  case "$status" in
    SUCCEEDED)
      echo
      echo "PASS: cron fired the drip sequence and all three sends completed across their delays"
      exit 0
      ;;
    FAILED|CANCELLED)
      echo
      echo "FAIL: workflow reached terminal $status"
      echo "$json"
      exit 1
      ;;
  esac
  sleep "$POLL_INTERVAL"
done

echo
echo "FAIL: drip run did not finish within ${TIMEOUT}s (last: ${last:-<no tasks>})"
exit 1
