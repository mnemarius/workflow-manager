
# Architecture

Tech stack, system topology, data model, key design decisions, and cross-cutting concerns. **Consult this file whenever a task touches code structure, data flow, persistence, protocols, scaling, observability, security, or deployment.** Keep it current: when code changes what this file describes, update it (and the [diagrams](diagrams/)) in the same change — see [AGENTS.md](../AGENTS.md) rule 10.

---

## Tech stack

| Layer                    | Pick                                                    | Why                                                                  |
|--------------------------|---------------------------------------------------------|----------------------------------------------------------------------|
| JDK                      | **Java 21 LTS**                                         | Virtual threads change the math on worker concurrency entirely.       |
| Build                    | **Gradle (Kotlin DSL)**                                 | Faster than Maven, modern.                                            |
| HTTP framework           | **Spring Boot 3.x**                                     | Industry default; what you'll see in jobs.                            |
| DB                       | **PostgreSQL 16+**                                      | Needs `SELECT FOR UPDATE SKIP LOCKED`, advisory locks, JSONB.         |
| DB access                | **jOOQ** or **JDBI** — *not* JPA / Hibernate            | Hibernate fights the lock-hint / advisory-lock / JSONB queries.       |
| Migrations               | **Flyway**                                              | Simple, runs on app startup.                                          |
| Worker / engine RPC      | **gRPC + protobuf**                                     | Streaming for long polls, typed contracts.                            |
| Payload serialization    | **Jackson (JSON)**                                      | Standard; opaque-blob is fine.                                        |
| Frontend API             | **REST + Server-Sent Events**                           | Simpler than WebSockets for one-way live updates.                     |
| Observability            | **Micrometer + OpenTelemetry + Logback JSON**           | Production-grade from day 1.                                          |
| Testing                  | **JUnit 5 + Testcontainers + AssertJ**                  | Testcontainers spins up real Postgres in tests — non-negotiable.      |
| Frontend                 | **React + TypeScript + Vite + Tailwind + React Flow**   | React Flow for the DAG view.                                          |
| Container                | **Docker + docker-compose** for local dev               | Engine + Postgres + sample worker + dashboard in one command.         |

**Avoid early:** Kafka, Redis, Kubernetes, microservices, multi-tenancy. Add only when a real need appears.

---

## Deployable artifacts

The repository contains three artifacts:

1. **`engine`** — the Spring Boot service (REST API + gRPC service + orchestrator core).
2. **`worker-sdk`** — Java library + a sample worker app.
3. **`dashboard`** — React SPA.

## System topology

```
                                  ┌──────────────────────────┐
                                  │       Dashboard          │
                                  │  (React + React Flow)    │
                                  └─────────────┬────────────┘
                                                │ REST + SSE
                                                ▼
  ┌──────────────────┐ submit ┌──────────────────────────────────┐
  │  Client SDK /    │───────►│        Engine (Spring Boot)      │
  │  curl            │        │  ┌─────────┐  ┌──────────────┐   │
  └──────────────────┘        │  │ REST API│  │ gRPC service │   │
                              │  └────┬────┘  └──────┬───────┘   │
                              │       │              │           │
                              │       ▼              ▼           │
                              │  ┌─────────────────────────┐     │
                              │  │   Orchestrator (core)   │     │
                              │  │  - DAG resolution       │     │
                              │  │  - State transitions    │     │
                              │  │  - Retry policy         │     │
                              │  │  - Timer wheel          │     │
                              │  └────────────┬────────────┘     │
                              │               │                  │
                              │               ▼                  │
                              │       ┌───────────────┐          │
                              │       │  PostgreSQL   │          │
                              │       │  (the truth)  │          │
                              │       └───────────────┘          │
                              └─────────────────┬────────────────┘
                                                │ gRPC long-poll
                                ┌───────────────┼───────────────┐
                                ▼               ▼               ▼
                          ┌──────────┐   ┌──────────┐   ┌──────────┐
                          │ Worker 1 │   │ Worker 2 │   │ Worker N │
                          │ (Java    │   │ (Java    │   │ (any     │
                          │  SDK)    │   │  SDK)    │   │  lang)   │
                          └──────────┘   └──────────┘   └──────────┘
```

---

## Non-functional requirements

The project's guiding quality attributes:

- **Scalability**
- **Modifiability**
- **Deployability**
- **Availability**

These are lenses, not a checklist. For every task, interpret what each attribute means in that task's context and let the relevant ones shape the solution.

---

## Data model

Six tables. Get these right and the rest follows.

| Table                  | Purpose                                                                                              |
|------------------------|------------------------------------------------------------------------------------------------------|
| `workflow_definitions` | Versioned DAG templates. `(id, name, version, dag JSONB, created_at)`.                               |
| `workflow_instances`   | One row per submitted workflow run. `(id, definition_id, status, input JSONB, output JSONB, …)`.     |
| `task_instances`       | One row per step in a workflow run. Status, attempts, scheduled/started/finished timestamps.         |
| `task_leases`          | Currently-held tasks. `(task_id, worker_id, lease_expires_at)` — drives crashed-worker recovery.     |
| `events`               | Append-only log of every state transition. Audit trail, debug log, replay source.                    |
| `outbox`               | Pending side effects (task enqueue, external notifications). Drained transactionally.                |

**Task statuses:**

| Status            | Meaning                                                                                              |
|-------------------|------------------------------------------------------------------------------------------------------|
| `PENDING`         | Waiting on DAG dependencies. Has at least one upstream task not yet in a terminal state.             |
| `READY`           | All dependencies satisfied. Eligible to be leased by a worker on the next poll.                      |
| `RUNNING`         | Currently leased by a worker. A `task_leases` row exists with a `lease_expires_at` in the future.    |
| `SUCCEEDED`       | Worker reported success. Terminal. Triggers downstream `PENDING → READY` promotion.                  |
| `FAILED`          | Worker reported failure and retry budget is exhausted, or task was pushed to DLQ. Terminal.          |
| `RETRY_SCHEDULED` | Worker failed; retry scheduled. Claimable when due: `RETRY_SCHEDULED → RUNNING` on claim (ADR 0006). |
| `CANCELLED`       | Externally cancelled (via API or because the parent workflow was cancelled). Terminal.               |

**Promotion is event-driven, not query-driven.** When a task transitions to `SUCCEEDED`, the orchestrator inspects its DAG children in the same transaction; any child whose parents are all now `SUCCEEDED` is flipped `PENDING → READY`. No periodic "scan for promotable tasks" job exists. The `outbox` carries any side effects of the promotion.

---

## Key design decisions

### A. Queue mechanism

`SELECT … FOR UPDATE SKIP LOCKED` on `task_instances`. Workers compete for ready tasks via this query. **Do not introduce Redis or Kafka** until Postgres is provably the bottleneck.

### B. Workflow definition language

**Declarative JSON DAGs**, submitted as data over REST. Validated against a JSON Schema at the API boundary, stored as JSONB in `workflow_definitions.dag`. One wire format, one parser, one set of errors. YAML is *not* accepted at the API — clients that want to author in YAML can convert client-side. Code-as-workflow (Temporal style) is *explicitly out of scope* for v1: no code-loading, no deterministic replay.

### C. Retry policy

Per-task config: a `retryPolicy` object on the task node in the DAG document, persisted as JSONB in `task_instances.retry_policy`. All fields optional; defaults apply per field:

| Field                 | Type / values                            | Default |
|-----------------------|------------------------------------------|---------|
| `maxAttempts`         | integer ≥ 1                              | 3       |
| `backoffStrategy`     | `fixed` \| `exponential`                 | `fixed` |
| `initialDelaySeconds` | integer ≥ 0                              | 5       |
| `maxDelaySeconds`     | integer ≥ 1 (cap for exponential growth) | 300     |

### D. Durable timers

A `scheduled_at` column on `task_instances`. The orchestrator's poll is essentially:

```sql
SELECT * FROM task_instances
WHERE status IN ('READY', 'RETRY_SCHEDULED') AND scheduled_at <= now()
FOR UPDATE SKIP LOCKED
LIMIT N;
```

A "sleep 3 days" step is just a task with `scheduled_at = now() + 3 days` and a no-op handler. Retries reuse the same mechanism: a due `RETRY_SCHEDULED` row is claimed directly, no promotion sweeper (ADR 0006). Cron / scheduled workflows will reuse it too.

### E. Worker → engine protocol

**Long-polling gRPC.** Worker calls `FetchTask(workerCapabilities)`; engine holds the call open up to 30 s waiting for matching work. Fewer round-trips than HTTP polling, no WebSocket complexity. While running a task the worker renews its lease with a unary `Heartbeat(task_id, worker_id)` every ~⅓ of the remaining lease; the engine answers `LeaseRenewed` or `LeaseLost`, and on `LeaseLost` the worker interrupts the handler and does not report completion (ADR 0007).

### F. Idempotency contract

The engine guarantees **at-least-once** delivery. Workers must be idempotent. This is documented loudly in the SDK and onboarding — it's the load-bearing assumption of the system. **"Exactly once" does not exist**; do not promise it.

### G. Crash recovery

Postgres is the source of truth. On engine startup:

1. Tasks with `RUNNING` status whose lease expired → reset to `READY`.
2. Scheduled tasks whose `scheduled_at` has passed → re-enqueue.
3. Any unsent `outbox` rows → process.

No in-memory state matters. This is the discipline.

### H. Concurrency model

The orchestrator's per-workflow logic is **single-threaded**. Parallelism comes from running many workflows concurrently, not from parallelizing the scheduling of one workflow's steps. Much simpler, good enough.

---

## Cross-cutting concerns

### Testing

- **Unit tests** for the orchestrator state machine — no DB, pure logic. Most bugs live here; test ruthlessly.
- **Integration tests** with Testcontainers (real Postgres). Cover lease expiry recovery, retry backoff, DAG resolution, crash recovery.
- **Chaos test** (manual is fine): a script that kills a random worker every 10 s while a workflow runs. The workflow must still complete.

### Observability (build in from M0, do not bolt on)

- **Logs:** structured JSON. Every log carries `workflow_id` and `task_id` in MDC.
- **Metrics:** task throughput, queue depth, retry rate, p50/p99 task duration, worker count.
- **Traces:** one trace per workflow, spans for each task. Trace context propagated engine ↔ worker over gRPC.

### Security

- API auth: API keys per "tenant" (even if there is only one). Do not ship an open endpoint.
- Worker auth: same mechanism.
- Payloads are user-supplied JSON — never `eval`. Store as opaque BYTEA / JSONB.

### Deployment

- **Local:** `docker compose up` brings up engine + Postgres + sample worker + dashboard.
- **Cloud (optional, stretch):** Fly.io or Railway — cheap, free Postgres, looks impressive in a README.

### Documentation

- **ADRs** in `docs/adr/` — one per major decision in this file.
- **README** with the topology diagram, a quickstart, and a GIF of the dashboard.

---

## Common pitfalls to avoid

- **JPA/Hibernate will hurt you** on this project's queries. Use jOOQ.
- **Time is the enemy.** Always store UTC, always use `Instant`, never `Date`/`LocalDateTime` for persisted times. Inject `Clock` so tests can fast-forward.
- **Don't over-index.** Add indexes when slow queries appear, not preemptively.
- **Document the worker contract loudly.** "Your handler must be idempotent" is the load-bearing assumption of the whole system.

---

## Related docs

- [BUSINESS](BUSINESS.md) — scope, milestones, demo story.
- [STYLING](STYLING.md) — code and UI conventions.
