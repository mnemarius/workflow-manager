# 0011 — Cron schedules, task delays, and the missed-fire policy

- **Status:** Accepted
- **Date:** 2026-07-23

## Context

M4 gives the engine two things it did not have: a step can **wait** without a worker holding it, and
a workflow can start **without anyone submitting it**. ADR 0006 already established that a due time
is just a `scheduled_at` the claim query filters on, so the substrate exists; what M4 has to decide
is how a wait is expressed in a DAG, how schedules are stored and fired, and — the only genuinely
contentious question — what happens to fires that came due while the engine was down.

## Decision

**A wait is an attribute of a task, not a task of its own.** Any task may declare `delaySeconds`
([V6](../../engine/src/main/resources/db/migration/V6__task_delay.sql)); it is promoted to `READY`
like any other task, but its `scheduled_at` is offset so it is not *claimable* until the delay
elapses. This supersedes the earlier sketch of a sleep step as "a no-op task with a far-future
`scheduled_at`": that would have required a dedicated handler, a worker round-trip, and an extra
node in every DAG that merely wanted to pause before a real step. Readiness and claimability were
already distinct in the claim query — this decision just exposes that distinction to the DAG author.

**The delay is anchored at readiness, not at submit.** A root task's delay runs from submit (it is
`READY` immediately); a dependent task's delay runs from **promotion**, applied in the promotion
UPDATE itself. "Wait three days after the previous step finished" is what drip sequences and
follow-ups actually mean; anchoring at submit would make every delay in a chain relative to the same
instant and collapse the sequence. Retries are unaffected: a delayed task that fails reschedules on
its backoff, not its delay, because the delay describes the step's position in time, not its
attempts.

**Schedules live in `workflow_schedules`
([V7](../../engine/src/main/resources/db/migration/V7__workflow_schedules.sql)), swept like leases.**
One row per schedule holding the DAG, the cron expression, its timezone, and `next_fire_at`.
[ScheduleSweeper](../../engine/src/main/java/com/workflowmanager/engine/application/ScheduleSweeper.java)
claims due rows `FOR UPDATE SKIP LOCKED`, submits the workflow, and advances `next_fire_at` **in one
transaction** — a crash mid-sweep rolls the schedule back to due rather than losing or duplicating
the run. It mirrors `LeaseReaper` exactly, including the startup pass, so downtime resolves on boot.
No new dependency: cron parsing is Spring's `CronExpression`, already on the classpath, whose
six-field form (seconds first) becomes the engine's cron dialect.

**Expressions are evaluated in the schedule's own timezone.** A 09:00 daily schedule stays at 09:00
local across a DST boundary, which is what "every morning" means to the person who wrote it; the
resulting instants are still stored as UTC `timestamptz` like every other time in the system.

**Missed fires skip to the latest slot; they are not replayed.** A schedule that comes due after an
outage fires **once**, for the most recent missed slot, and resumes on the normal grid — recording
in `WORKFLOW_FIRED_BY_SCHEDULE` how many slots it dropped. An engine down six hours with a
ten-minute schedule submits one run, not thirty-six. Catch-up would convert an outage into a
thundering herd at precisely the moment the system is least healthy, and for the workloads a cron
schedule expresses — a digest, a drip send, a nightly sweep — thirty-six stale digests are worse
than one fresh one, not better. Pausing and resuming re-anchors `next_fire_at` for the same reason.

**A unique index on `(schedule_id, fired_for)` over `workflow_instances` is the durable guard.**
Each fire is tagged with the nominal slot it fired for, so a duplicate submission fails on the index
rather than starting a second run. The row lock alone would already be enough for one engine; the
index is what keeps this correct across restarts and, later, across the several engines M7
introduces — the sweeper needs no change to become multi-engine-safe.

## Consequences

- A "sleep 3 days" step costs zero worker involvement and zero extra DAG nodes: `delaySeconds` on
  the step that follows the wait.
- Timer precision is bounded by the sweep interval (`engine.schedule-sweep-interval`, default 5s)
  for schedules, and by worker poll latency for delayed tasks. Neither is a scheduling *guarantee* —
  a task is claimable *no earlier* than its due time, not *exactly* at it.
- Missed fires are visibly dropped rather than silently lost: the skipped count is on the event and
  in a WARN log, so a schedule that has been quietly falling behind is diagnosable.
- A schedule whose cron expression somehow becomes unevaluatable is paused rather than allowed to
  wedge the sweep for the other schedules in the batch.
- `workflow_instances` gains two nullable columns that are only ever set for scheduled runs.
  Hand-submitted runs are unchanged and untagged.

## Alternatives considered

- **A dedicated `sleep`/`timer` task type with a no-op handler.** Rejected: a worker round-trip and
  an extra DAG node to express "wait", when the claim query could express it for free. It would also
  have made the wait a separate thing to retry, time out, and dead-letter.
- **Catch-up replay of missed fires**, optionally bounded by a lookback window. Rejected as the
  default for the reasons above; if a workload genuinely needs every slot, that is a per-schedule
  flag over `CronPolicy`, which is pure and holds this decision in one place.
- **A separate scheduler process** or a library like Quartz. Rejected under working rule 7: the
  sweeper is thirty lines over a table the engine already owns, and Quartz would bring its own schema
  and clustering model to sit beside the one Postgres substrate.
- **Anchoring a dependent task's delay at workflow submit.** Rejected: it collapses a chain of waits
  into a single instant and cannot express "three days after the previous step finished".
