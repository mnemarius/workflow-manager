# 0001 — Postgres-only substrate; queue via FOR UPDATE SKIP LOCKED

- **Status:** Accepted
- **Date:** 2026-06-24

## Context

The system needs a durable work queue that survives crashes and lets many workers compete
for ready tasks without double-dispatch. The product constraint (see
[BUSINESS.md](../BUSINESS.md)) is "fits on a single small VM": no Kafka, no Redis, no
Zookeeper. Postgres already has to be present as the source of truth for workflow state.

## Decision

We will use **PostgreSQL as the only substrate** and implement the queue with
`SELECT … FOR UPDATE SKIP LOCKED` over `task_instances`, filtered by `status = 'READY' AND
scheduled_at <= now()`. Durable timers reuse the same `scheduled_at` column. We will **not**
introduce Redis or Kafka until Postgres is *provably* the bottleneck.

## Consequences

- One piece of infrastructure to deploy, back up, and reason about. Crash recovery is "read
  the truth back from Postgres."
- `SKIP LOCKED` gives concurrent, contention-free dequeue without an external broker.
- Throughput is bounded by a single Postgres instance. Acceptable for the target scale;
  M7 (multi-engine leader election) scales the engine without leaving this substrate.
- Rejected: a dedicated message broker — more moving parts than the scope justifies.
