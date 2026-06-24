# 0004 — At-least-once delivery; idempotency is a product contract

- **Status:** Accepted
- **Date:** 2026-06-24

## Context

Distributed task dispatch with crash recovery cannot deliver "exactly once" — a worker can
finish a task and crash before acknowledging, forcing a redelivery. Pretending otherwise
builds a lie into the foundation. See decision F and the crash-recovery section in
[ARCHITECTURE.md](../ARCHITECTURE.md), and the scope-discipline rules in
[BUSINESS.md](../BUSINESS.md).

## Decision

The engine guarantees **at-least-once** delivery. **Workers must be idempotent** — this is a
documented product contract, surfaced loudly in the SDK and onboarding, not an
implementation detail. We will **not** promise exactly-once anywhere.

## Consequences

- Recovery is simple and correct: on restart, reset expired `RUNNING` leases to `READY`,
  re-enqueue due scheduled tasks, and drain the `outbox`. Redelivery is safe by contract.
- Workers carry the deduplication burden (e.g. keyed on task id / attempt). This must be
  taught explicitly, or users will write subtly broken handlers.
- Side effects are routed through the transactional `outbox` so they are not lost or
  double-sent within the engine's control.
