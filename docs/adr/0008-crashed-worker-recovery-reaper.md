# 0008 — Crashed-worker recovery: periodic engine lease reaper

- **Status:** Accepted
- **Date:** 2026-07-15

## Context

A worker can die (or lose its network) holding a `RUNNING` task. Heartbeats (ADR 0007) make
the lease expiry a reliable death signal; something must then return the task to the queue.
Options: recover lazily in the claim path (fold "or lease expired" into the claim query), or
run a dedicated reaper that periodically resolves expired leases. Availability is M2's lens —
see decision G in [ARCHITECTURE.md](../ARCHITECTURE.md).

## Decision

**A periodic engine-side reaper**
([LeaseReaper.java](../../engine/src/main/java/com/workflowmanager/engine/application/LeaseReaper.java)),
running every `engine.reaper-interval` (default 5s) plus once at startup. Each pass locks
`RUNNING` tasks with `lease_expires_at <= now` using `FOR UPDATE SKIP LOCKED`, deletes the
lease, appends `TASK_LEASE_EXPIRED`, and hands the task to the **same failure path as a
worker-reported failure** ([TaskFailureResolver.java](../../engine/src/main/java/com/workflowmanager/engine/application/TaskFailureResolver.java)) —
with two twists:

- **Expiry consumes an attempt.** The attempt was consumed at claim; a crash after claim is
  an attempt that happened, not one to refund.
- **Retries without backoff** (`scheduled_at = now`). A crash signals worker failure, not
  task failure — backoff exists to spare a struggling task, and delaying failover here only
  hurts recovery time.

Not claim-path recovery, because: recovery must run even when no compatible worker is
polling (a lazy claim-path check recovers nothing while workers are gone — exactly the
failure scenario); the claim query is the hot path and stays lean; and a `SKIP LOCKED`
reaper is already multi-engine-safe for M7.

## Consequences

- Crash detection latency is bounded by `lease-duration + reaper-interval`, independent of
  worker traffic; startup pass covers leases that expired while the engine itself was down.
- The reaper and a live completion race safely: completion holds the task row lock, so the
  reaper skips it (`SKIP LOCKED`); a stale worker's later report is rejected by the lease
  gate (ADR 0004).
- A task whose every attempt dies by expiry dead-letters through the normal exhausted path —
  no special crash-only state.
- One more moving part (a scheduled job), accepted for availability's sake; ADR 0006's
  "no promotion sweeper" still holds — the reaper resolves failures, it does not promote
  due timers.
