# 0009 — DLQ as terminal FAILED status plus failure metadata and redrive; no separate table

- **Status:** Accepted
- **Date:** 2026-07-16

## Context

M2's last piece is the dead-letter queue: when a task exhausts its retry budget an operator
must be able to see *what* died, *why*, and push it back onto the queue after fixing the
cause. The classic shape is a dedicated `dead_letter_tasks` table the engine copies exhausted
tasks into. The alternative: treat the terminal `FAILED` status itself as the queue.

## Decision

**The DLQ is the set of `FAILED` rows in `task_instances` — terminal status plus failure
metadata plus a redrive API, no separate table.** Two columns
([V4](../../engine/src/main/resources/db/migration/V4__dead_letter_metadata.sql)):
`failure_reason` (`HANDLER_FAILED` | `LEASE_EXPIRED` | `TIMED_OUT`) and `last_error` JSONB,
stamped by the one shared failure path
([TaskFailureResolver.java](../../engine/src/main/java/com/workflowmanager/engine/application/TaskFailureResolver.java)).
`last_error` is also refreshed on every scheduled retry, so the newest error is visible
mid-retry, not only after death. Exhaustion appends a `TASK_DEAD_LETTERED` event after the
attempt-level `TASK_FAILED`.

Listing and inspection are plain queries over `FAILED` rows (partial index on
`finished_at desc where status = 'FAILED'`), exposed as `GET /dead-letters`. Redrive
(`POST /dead-letters/{taskId}/retry`,
[DeadLetterController.java](../../engine/src/main/java/com/workflowmanager/engine/api/DeadLetterController.java))
is one guarded UPDATE: `409` unless the task is `FAILED`; otherwise attempts reset to 0,
status back to `READY` with `scheduled_at = now`, metadata cleared, the workflow flipped
`FAILED → RUNNING`, and `TASK_REDRIVEN` appended — the normal claim path does the rest.

Not a `dead_letter_tasks` table, because Postgres is already the single source of truth
(working rule 2): a copy table duplicates rows that can drift from the originals, needs its
own lifecycle on redrive, and buys nothing — list/inspect/redrive are just queries plus one
UPDATE, and the `events` table already keeps the full failure history a copy would preserve.

## Consequences

- Zero data movement on dead-letter or redrive: a task's identity, DAG edges, and event
  history stay on one row.
- `FAILED` is terminal for the scheduler but not immutable: redrive is the one sanctioned
  `FAILED → READY` transition, and it also reopens the workflow (`FAILED → RUNNING`).
- A redriven task starts with a fresh attempt budget; the pre-redrive attempts remain
  visible only through `events`.
- The DLQ listing scans `task_instances`; if `FAILED` rows ever number millions the partial
  index keeps the list cheap, and archival (not a copy table) is the pressure valve.
