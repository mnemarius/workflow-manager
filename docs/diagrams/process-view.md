# Process view

> **Keep in sync:** these diagrams describe the system as built. Whenever you change the architecture, modules, runtime flow, or deployment topology, update the affected view in the same change — do not let them drift.

Runtime behavior as of M3 (multi-step DAGs on top of the M2 machinery: submit → execute, retries with backoff, lease heartbeat, crashed-worker recovery, per-attempt timeouts, dead-lettering + redrive, and now dependency promotion + failure cascade).

## Submit → execute happy path

```mermaid
sequenceDiagram
    actor Client
    participant API as Engine REST API
    participant DB as PostgreSQL
    participant GRPC as Engine gRPC service
    participant Worker

    Client->>API: POST /workflows
    API->>DB: INSERT workflow_instances (PENDING)
    API->>DB: INSERT task_instances (READY if no deps, else PENDING) + task_dependencies edges
    API-->>Client: 201 Created (instance id)

    Worker->>GRPC: FetchTask()
    GRPC->>DB: SELECT ... FOR UPDATE SKIP LOCKED
    DB-->>GRPC: one READY task
    GRPC->>DB: INSERT task_leases row; task READY -> RUNNING
    GRPC-->>Worker: Task (with lease_expires_at)

    par handler on its own virtual thread
        Worker->>Worker: run handler
    and heartbeat every ~1/3 of remaining lease
        loop while handler runs
            Worker->>GRPC: Heartbeat(task_id, worker_id)
            GRPC->>DB: verify lease ownership; extend lease_expires_at
            GRPC-->>Worker: LeaseRenewed (or LeaseLost -> interrupt handler, no CompleteTask)
        end
    end
    Worker->>GRPC: CompleteTask(success)
    GRPC->>DB: verify lease ownership
    GRPC->>DB: task -> SUCCEEDED
    GRPC->>DB: instance -> SUCCEEDED
    GRPC->>DB: append events
    GRPC-->>Worker: ack

    Client->>API: GET /workflows/{id}
    API->>DB: SELECT instance
    API-->>Client: 200 OK (status: SUCCEEDED)
```

## Failure → retry loop

A failed attempt with retry budget left becomes a durable timer: the task flips to `RETRY_SCHEDULED` with `scheduled_at = now + backoff`, and the normal claim query picks it up directly once due — no promotion sweeper (ADR 0006).

```mermaid
sequenceDiagram
    participant Worker
    participant GRPC as Engine gRPC service
    participant DB as PostgreSQL

    Worker->>GRPC: CompleteTask(failure)
    GRPC->>DB: verify lease ownership
    alt attempts < maxAttempts
        GRPC->>DB: task -> RETRY_SCHEDULED, scheduled_at = now + backoff; delete lease
        GRPC->>DB: append TASK_RETRY_SCHEDULED event
        GRPC-->>Worker: ack
        Note over Worker,DB: backoff elapses (fixed or exponential, decision C)
        Worker->>GRPC: FetchTask()
        GRPC->>DB: claim due READY or RETRY_SCHEDULED task -> RUNNING, attempts+1
        GRPC-->>Worker: Task (next attempt)
    else retry budget exhausted
        GRPC->>DB: task -> FAILED with failure_reason + last_error; instance -> FAILED
        GRPC->>DB: append TASK_FAILED + TASK_DEAD_LETTERED + WORKFLOW_FAILED events
        GRPC-->>Worker: ack
    end
```

A dead-lettered task stays queryable and recoverable: `GET /dead-letters` lists `FAILED` rows with their failure metadata, and `POST /dead-letters/{taskId}/retry` resets the task to `READY` (attempts 0, metadata cleared), reopens the workflow, and appends `TASK_REDRIVEN` — the normal claim path re-executes it (ADR 0009).

## Worker crash → reap → re-dispatch

A dead worker stops heartbeating, so its lease expires. The engine's reaper (ADR 0008) runs every `engine.reaper-interval` (default 5s) plus once at startup, and resolves expired leases through the same failure path as a worker-reported failure — but **without backoff**: a crash says nothing bad about the task, so failover is prompt. The expired attempt stays consumed (it was taken at claim). The same pass enforces per-attempt timeouts (decision H): lease grants are capped at `attempt_start + timeout`, so an overrun lapses like a crash — the reaper classifies it `TASK_TIMED_OUT` and retries **with** backoff.

```mermaid
sequenceDiagram
    participant WA as Worker A
    participant GRPC as Engine gRPC service
    participant Reaper as Lease reaper (engine)
    participant DB as PostgreSQL
    participant WB as Worker B

    WA->>GRPC: FetchTask()
    GRPC-->>WA: Task (attempt 1)
    Note over WA: crashes mid-task - no heartbeats, no CompleteTask

    loop every reaper interval + once at startup
        Reaper->>DB: SELECT RUNNING tasks with lease_expires_at <= now FOR UPDATE SKIP LOCKED
        DB-->>Reaper: expired task
        Reaper->>DB: delete lease; append TASK_TIMED_OUT (past attempt deadline) or TASK_LEASE_EXPIRED
        alt attempts < maxAttempts
            Reaper->>DB: task -> RETRY_SCHEDULED (timed out: now + backoff; crashed: now)
        else budget exhausted
            Reaper->>DB: task -> FAILED; instance -> FAILED
        end
    end

    WB->>GRPC: FetchTask()
    GRPC->>DB: claim due task -> RUNNING, attempts+1
    GRPC-->>WB: Task (attempt 2)
    WB->>GRPC: CompleteTask(success)
```

## DAG: promotion on success, cascade on failure

M3 makes a workflow a DAG. Tasks with `dependsOn` edges start `PENDING`; when a task succeeds, the completion transaction promotes every `PENDING` task in the workflow whose dependencies are now **all** `SUCCEEDED` to `READY` (event `TASK_READY`) — no sweeper (ADR 0010). Sibling completions that share a fan-in child are serialized by a blocking, id-ordered `FOR UPDATE` on the workflow's `PENDING` set (not `SKIP LOCKED`), so the join task is never orphaned. When a task instead **dead-letters**, its transitive dependents (recursive CTE) are cancelled `PENDING → CANCELLED` and the workflow fails; DLQ redrive restores them `CANCELLED → PENDING`. On workflow success the output is the keyed aggregate of the **sink** tasks (those nothing depends on): `{ "<taskKey>": <output>, … }`.

```mermaid
sequenceDiagram
    participant Worker
    participant GRPC as Engine gRPC service
    participant DB as PostgreSQL

    Worker->>GRPC: CompleteTask(success) for task T
    GRPC->>DB: verify lease ownership; T -> SUCCEEDED
    GRPC->>DB: SELECT this workflow's PENDING tasks FOR UPDATE (ordered by id, blocking)
    GRPC->>DB: promote PENDING tasks whose deps are ALL SUCCEEDED -> READY; append TASK_READY
    alt open tasks remain
        GRPC-->>Worker: ack (workflow still RUNNING; promoted tasks now claimable)
    else all tasks terminal, none FAILED
        GRPC->>DB: instance -> SUCCEEDED, output = { sinkKey: output, ... }
        GRPC-->>Worker: ack
    end

    Note over Worker,DB: on the failure path instead: when T dead-letters (retries exhausted)
    Worker->>GRPC: CompleteTask(failure), budget spent
    GRPC->>DB: T -> FAILED; cancel transitive dependents PENDING -> CANCELLED (recursive CTE)
    GRPC->>DB: append TASK_CANCELLED per dependent; instance -> FAILED
    Note over Worker,DB: POST /dead-letters/{T}/retry later restores dependents CANCELLED -> PENDING (ADR 0009/0010)
```

## The gRPC long-poll mechanic

`FetchTask` is a long-lived call: the engine parks it on a virtual thread and polls the queue instead of returning immediately empty-handed.

```mermaid
sequenceDiagram
    participant Worker
    participant GRPC as Engine gRPC service (virtual thread per call)
    participant DB as PostgreSQL

    Worker->>GRPC: FetchTask()
    activate GRPC
    loop poll every ~500ms, up to ~25s deadline
        GRPC->>DB: SELECT READY task FOR UPDATE SKIP LOCKED
        break task found
            DB-->>GRPC: task row
            GRPC-->>Worker: Task
        end
    end
    GRPC-->>Worker: NoTask (deadline reached)
    deactivate GRPC
    Worker->>GRPC: FetchTask() (immediate re-poll)
```

## Concurrency notes

- **Per-workflow orchestration is single-threaded** (decision I). Parallelism comes from many workflows running concurrently, not from parallelizing one workflow's own scheduling.
- **At-least-once delivery** (decision F): a lease can expire and be re-handed to another worker, or a worker can crash after finishing but before acking. Handlers **must be idempotent** — this is a load-bearing product contract, not an implementation detail.

## Task status lifecycle

`PENDING → READY → RUNNING → SUCCEEDED` is the happy path. M2 adds the failure loop `RUNNING → RETRY_SCHEDULED → RUNNING`, the terminal `FAILED` when the retry budget runs out, and redrive as the one sanctioned `FAILED → READY` transition (ADR 0009). M3 makes `PENDING` and `CANCELLED` live: a task with `dependsOn` edges starts `PENDING` and is promoted `PENDING → READY` when its deps all succeed; a dead-lettered task cascades its dependents `PENDING → CANCELLED`, and redrive restores them `CANCELLED → PENDING` (ADR 0010).

| Status            | When it appears                                                                          |
|-------------------|------------------------------------------------------------------------------------------|
| `PENDING`         | Waiting on DAG dependencies (M3). Not claimable; promoted to `READY` when all deps `SUCCEEDED`. |
| `READY`           | Eligible to be leased by a worker on the next `FetchTask` poll.                          |
| `RUNNING`         | Leased by a worker; a `task_leases` row holds the lease.                                 |
| `SUCCEEDED`       | Worker reported success. Terminal. Promotes dependents whose deps are now all met.        |
| `FAILED`          | Retries exhausted — the dead-letter queue; redrive resets it to `READY` (ADR 0009).      |
| `RETRY_SCHEDULED` | Retry pending — worker failure or timeout (backoff), lease expiry (no backoff); ADRs 0006–0008. |
| `CANCELLED`       | Terminal. Cascade-cancelled dependent of a dead-lettered task (M3); redrive restores it to `PENDING` (ADR 0010). External cancellation later. |
