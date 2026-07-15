# 0006 — Retries as durable timers claimed directly when due

- **Status:** Accepted
- **Date:** 2026-07-15

## Context

M2 makes worker-reported failures retryable with backoff (decision C in
[ARCHITECTURE.md](../ARCHITECTURE.md)). The retry must survive an engine restart —
availability is M2's lens and Postgres is the source of truth, so an in-memory timer wheel
is out. The remaining options: a sweeper job that periodically promotes due
`RETRY_SCHEDULED` rows back to `READY`, or letting the claim query pick up due
`RETRY_SCHEDULED` rows directly.

## Decision

**Retries are durable timers claimed directly when due — no promotion sweeper.** On a
failure with retry budget left, the task flips to `RETRY_SCHEDULED` with
`scheduled_at = now + backoff` and its lease row is deleted. The claim query (ADR 0001)
becomes `status IN ('READY', 'RETRY_SCHEDULED') AND scheduled_at <= now()`, so a due retry
transitions `RETRY_SCHEDULED → RUNNING` on claim, exactly like a `READY` task. When the
budget is exhausted the existing failure path runs: task `FAILED`, run `FAILED`.

## Consequences

- A pending retry is one row in Postgres — it survives engine crashes and restarts with no
  recovery step beyond reading the table.
- No background sweeper: one fewer moving part, no promotion latency, no extra write per
  retry. Dispatch latency for a due retry is bounded by the workers' long-poll interval.
- `READY` is no longer the only claimable status; anything reasoning about the queue must
  use the "due" condition (status + `scheduled_at`), not status alone.
- The `RETRY_SCHEDULED → READY` flip never happens; the status table in ARCHITECTURE.md
  documents the `RETRY_SCHEDULED → RUNNING` transition instead.
