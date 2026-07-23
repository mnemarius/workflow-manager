# Logical view

> **Keep in sync:** these diagrams describe the system as built. Whenever you change the architecture, modules, runtime flow, or deployment topology, update the affected view in the same change — do not let them drift.

Components and their dependencies. The `orchestrator` package is DB-free pure logic (state machine, retry policy, lease math, cron arithmetic); `persistence` is the only package that talks jOOQ/SQL. `protocol` is the gRPC contract shared by the engine and the worker SDK, so engine and workers can never drift on the wire format.

```mermaid
flowchart TB
    Dashboard["dashboard\n(React + React Flow)"]

    subgraph Engine["engine (Spring Boot)"]
        Api["api\n(REST controllers, DTOs)"]
        Grpc["grpc\n(gRPC service impls)"]
        Orchestrator["orchestrator\n(state machine, retry, cron — pure, DB-free)"]
        Persistence["persistence\n(jOOQ queries, repositories)"]
        Config["config\n(Spring wiring)"]

        Api --> Orchestrator
        Grpc --> Orchestrator
        Orchestrator --> Persistence
    end

    Protocol["protocol\n(gRPC contract: FetchTask, CompleteTask)"]

    subgraph WorkerSdk["worker-sdk"]
        Sdk["worker-sdk lib"]
        SampleWorker["sample worker app"]
        Sdk --> SampleWorker
    end

    Postgres[("PostgreSQL\n(the truth)")]

    Dashboard -->|REST + SSE| Api
    Grpc -.->|implements| Protocol
    Sdk -.->|implements| Protocol
    SampleWorker -->|FetchTask / CompleteTask over gRPC| Grpc
    Persistence --> Postgres
```

## Module responsibilities

| Module                | Responsibility                                                                        |
|-----------------------|---------------------------------------------------------------------------------------|
| `dashboard`           | React SPA; observes and controls runs via REST + SSE.                                 |
| `engine/api`          | REST controllers and DTOs — submit/query, dead-letter list/redrive, cron schedules.   |
| `engine/grpc`         | gRPC service implementations — the worker-facing fetch/complete surface.              |
| `engine/orchestrator` | DAG resolution, state transitions, retry policy, cron arithmetic. Pure logic, no DB.  |
| `engine/persistence`  | jOOQ queries and repositories. The only package that knows SQL.                       |
| `engine/config`       | Spring Boot wiring — datasource, gRPC server, beans.                                  |
| `protocol`            | Shared gRPC/protobuf contract between `engine/grpc` and `worker-sdk`.                 |
| `worker-sdk`          | Java client library for writing workers, plus a sample worker app.                    |
| PostgreSQL            | Single source of truth for all workflow and task state.                               |

## Data model

Eight tables (see [ARCHITECTURE.md](../ARCHITECTURE.md#data-model) for full column detail and status semantics). Overview only — PKs/FKs and a couple of key fields per table. `task_dependencies` is the M3 DAG edge table: one row per `dependsOn` edge, both columns FK into `task_instances` (a task waits on another task of the same workflow). `workflow_schedules` is the M4 cron table: a schedule fires runs, and each run it starts points back at it.

```mermaid
erDiagram
    WORKFLOW_DEFINITIONS ||--o{ WORKFLOW_INSTANCES : instantiates
    WORKFLOW_SCHEDULES ||--o{ WORKFLOW_INSTANCES : fires
    WORKFLOW_INSTANCES ||--o{ TASK_INSTANCES : contains
    TASK_INSTANCES ||--o| TASK_LEASES : "leased as"
    TASK_INSTANCES ||--o{ TASK_DEPENDENCIES : "waits on"
    TASK_INSTANCES ||--o{ TASK_DEPENDENCIES : "blocks"
    WORKFLOW_INSTANCES ||--o{ EVENTS : emits
    TASK_INSTANCES ||--o{ EVENTS : emits
    TASK_INSTANCES ||--o{ OUTBOX : enqueues

    WORKFLOW_DEFINITIONS {
        uuid id PK
        string name
        int version
        jsonb dag
    }
    WORKFLOW_SCHEDULES {
        uuid id PK
        string cron_expression
        string timezone
        timestamptz next_fire_at
        bool paused
    }
    WORKFLOW_INSTANCES {
        uuid id PK
        uuid definition_id FK
        uuid schedule_id FK "null unless cron-fired"
        timestamptz fired_for "unique with schedule_id"
        string status
        jsonb input
    }
    TASK_INSTANCES {
        uuid id PK
        uuid workflow_instance_id FK
        string status
        int attempts
        int delay_seconds "offsets scheduled_at at readiness"
    }
    TASK_DEPENDENCIES {
        uuid task_id PK "FK to task_instances"
        uuid depends_on_task_id PK "FK to task_instances"
    }
    TASK_LEASES {
        uuid task_id PK "also FK to task_instances"
        string worker_id
        timestamptz lease_expires_at
    }
    EVENTS {
        uuid id PK
        uuid workflow_instance_id FK
        string type
        timestamptz created_at
    }
    OUTBOX {
        uuid id PK
        uuid task_id FK
        string payload_type
        bool processed
    }
```
