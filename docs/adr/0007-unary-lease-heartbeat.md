# 0007 — Unary lease heartbeat; renewals capped by the attempt deadline

- **Status:** Accepted
- **Date:** 2026-07-15

## Context

M2 introduces crashed-worker recovery: a `RUNNING` task whose lease expires is reclaimed by
the engine. That makes the lease duration a liveness deadline, so a healthy worker running a
long task must renew its lease or lose the task mid-flight. The worker protocol is unary
long-polling gRPC (ADR 0005), and each worker runs one task at a time.

## Decision

Workers renew leases with a **unary `Heartbeat(task_id, worker_id)` RPC**, sent roughly every
third of the remaining lease. The engine validates ownership with the **same stale-worker
check as `CompleteTask`** and answers `LeaseRenewed(lease_expires_at)` or
`LeaseLost(reason)`. On `LeaseLost` — or when heartbeats fail hard past the known expiry —
the SDK interrupts the handler and suppresses `CompleteTask`; the engine's reaper hands the
task to another worker.

Renewals are not unlimited: they are **capped by the attempt deadline** (per-task timeout,
M2 step 5). A heartbeat past that deadline is answered `LeaseLost`, so a hung-but-heartbeating
worker cannot hold a task forever. The contract is stated here; the cap itself ships with the
timeout work.

A unary RPC (not a bidirectional stream) fits because a worker holds exactly one task at a
time — one cheap call every few seconds, no stream lifecycle to manage, and the same
interceptor/auth path as the other RPCs. A streaming variant is parked in
[FUTURE.md](../../FUTURE.md) for multi-task workers.

## Consequences

- Long tasks survive: lease duration ([EngineProperties.java](../../engine/src/main/java/com/workflowmanager/engine/config/EngineProperties.java),
  default 30s) bounds crash *detection*, not task length.
- Completion and renewal share one ownership gate
  ([CompletionPolicy.checkLease](../../engine/src/main/java/com/workflowmanager/engine/orchestrator/CompletionPolicy.java)) —
  the stale-worker rules cannot drift apart.
- Handlers must honor interruption, or a revoked task keeps burning worker capacity —
  documented on `TaskHandler`.
- A missed heartbeat window (GC pause, network blip) loses the lease even though the worker
  is alive; at-least-once (ADR 0004) makes the resulting re-run safe.
