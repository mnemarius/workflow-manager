# 0005 — Long-polling gRPC for the worker↔engine protocol

- **Status:** Accepted
- **Date:** 2026-06-24

## Context

Workers need to receive work with low latency and few wasted round-trips, while the engine
holds the source of truth. Options: HTTP short-polling (chatty, latency-vs-load tradeoff),
WebSockets (stateful, more complex), or gRPC long-polling. See decision E in
[ARCHITECTURE.md](../ARCHITECTURE.md).

## Decision

We will use **long-polling gRPC**. A worker calls `FetchTask(workerCapabilities)` and the
engine holds the call open (up to ~30s) until matching work appears or the deadline passes.
gRPC also gives typed contracts (protobuf) and a natural place to propagate trace context
engine↔worker. The Java worker SDK is the only client for v1.

## Consequences

- Near-instant dispatch without WebSocket connection-state machinery; far fewer round-trips
  than HTTP polling.
- Typed, versioned wire contracts via protobuf; streaming is available for long polls.
- A gRPC stack is heavier than plain REST and less trivial to call with `curl` — accepted,
  since workers use the generated SDK, not hand-rolled calls.
- Capability-based matching lets workers advertise what task types they can run.
