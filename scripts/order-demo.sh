#!/usr/bin/env bash
# M3 reference demo: the order-fulfillment diamond DAG
#
#     validate ─┬─► charge-payment ───┐
#               └─► reserve-inventory ─┴─► ship ─► notify
#
# charge-payment and reserve-inventory both depend on validate (fan-out); ship joins on BOTH
# (fan-in); notify is the sole sink. charge-payment is a legitimately flaky step (SampleWorker
# fails it ~40% of the time), so its retryPolicy exercises real retries — the demo must still
# reach SUCCEEDED. Submits the DAG, polls the workflow, prints task-status progression so the
# fan-out/fan-in and the payment retries are visible, and asserts the final output carries the
# `notify` sink key.
#
# Usage: scripts/order-demo.sh
# Prereq: stack up (engine + at least one worker running the sample worker):
#   docker compose up -d --build
set -euo pipefail

API="${API:-http://localhost:8080}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"
TIMEOUT="${TIMEOUT:-120}"

submit() {
  curl -sf "$API/workflows" -H 'content-type: application/json' -d '{
    "name": "order-fulfillment", "version": 1,
    "dag": { "tasks": [
      { "key": "validate", "type": "validate" },
      {
        "key": "charge-payment", "type": "charge-payment", "dependsOn": ["validate"],
        "retryPolicy": { "maxAttempts": 5, "backoffStrategy": "fixed", "initialDelaySeconds": 1 }
      },
      { "key": "reserve-inventory", "type": "reserve-inventory", "dependsOn": ["validate"] },
      { "key": "ship", "type": "ship", "dependsOn": ["charge-payment", "reserve-inventory"] },
      { "key": "notify", "type": "notify", "dependsOn": ["ship"] }
    ] }
  }' | grep -o '"instanceId":"[^"]*"' | cut -d'"' -f4
}

workflow_json() {
  curl -s "$API/workflows/$1"
}

workflow_status() {
  echo "$1" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4
}

# The workflow-level output object (keyed aggregate of sink outputs), sliced out of the response
# between "output": and ,"tasks": — nested braces and all. "null" until the workflow succeeds.
workflow_output() {
  echo "$1" | sed -E 's/.*"output":(.*),"tasks":.*/\1/'
}

# Compact per-task view: "key=STATUS(aN)" joined on one line, so retries on charge-payment show up
# as a rising attempt count and PENDING->READY->RUNNING->SUCCEEDED transitions are visible. The DTO
# serializes the task key as "taskKey" (WorkflowController / TaskStatusResponse).
task_line() {
  echo "$1" \
    | grep -o '"taskKey":"[^"]*","type":"[^"]*","status":"[^"]*","attempts":[0-9]*' \
    | sed -E 's/"taskKey":"([^"]*)","type":"[^"]*","status":"([^"]*)","attempts":([0-9]*)/\1=\2(a\3)/' \
    | paste -sd' ' -
}

echo "submitting order-fulfillment DAG to $API ..."
id=$(submit)
if [[ -z "$id" ]]; then
  echo "FAIL: submit did not return an instanceId (is the engine up at $API?)"
  exit 1
fi
echo "submitted $id"
echo

start=$(date +%s)
deadline=$(( start + TIMEOUT ))
last=""
while (( $(date +%s) < deadline )); do
  json=$(workflow_json "$id")
  status=$(workflow_status "$json")
  line=$(task_line "$json")
  if [[ "$line" != "$last" ]]; then
    printf '[%3ss] %-9s | %s\n' "$(( $(date +%s) - start ))" "$status" "$line"
    last="$line"
  fi
  case "$status" in
    SUCCEEDED)
      echo
      output=$(workflow_output "$json")
      echo "workflow output: $output"
      if echo "$output" | grep -q '"notify"'; then
        echo "PASS: order-fulfillment SUCCEEDED and the output carries the notify sink key"
        exit 0
      fi
      echo "FAIL: SUCCEEDED but the workflow output is missing the notify sink key"
      exit 1
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
echo "FAIL: workflow $id did not reach SUCCEEDED within ${TIMEOUT}s (last: ${last:-<no tasks>})"
exit 1
