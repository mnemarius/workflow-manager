# workflow-manager

**Postgres-only, single-JAR durable workflows — sized for one person to deploy and run.**

A coordinator service that accepts workflow definitions (JSON DAGs over REST), schedules
their steps, hands work to worker processes over gRPC, tracks state in PostgreSQL, retries
failures, and exposes a dashboard to observe and control runs. No Kafka, no Redis, no
Kubernetes — Postgres is the only substrate.

> Status: **M2 — retries, timeouts, leases, DLQ.** Kill a worker mid-task and another picks it
> up: task leases with heartbeats, a reaper that recovers expired leases, retries with backoff,
> per-attempt timeouts, and a dead-letter queue with redrive. DAGs, timers and the live
> dashboard arrive in later milestones (see [docs/BUSINESS.md](docs/BUSINESS.md)).

## Topology

```text
                                  ┌──────────────────────────┐
                                  │       Dashboard          │
                                  │  (React + React Flow)    │
                                  └─────────────┬────────────┘
                                                │ REST + SSE
                                                ▼
  ┌──────────────────┐ submit ┌──────────────────────────────────┐
  │  Client SDK /    │───────►│        Engine (Spring Boot)      │
  │  curl            │        │   REST API · gRPC · Orchestrator │
  └──────────────────┘        └─────────────────┬────────────────┘
                                                 │
                                                 ▼
                                         ┌───────────────┐
                                         │  PostgreSQL   │
                                         │  (the truth)  │
                                         └───────────────┘
                                                 │ gRPC long-poll
                                ┌────────────────┼────────────────┐
                                ▼                ▼                ▼
                          ┌──────────┐     ┌──────────┐     ┌──────────┐
                          │ Worker 1 │     │ Worker 2 │     │ Worker N │
                          └──────────┘     └──────────┘     └──────────┘
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design.

## Modules

| Module       | What it is                                                          |
| ------------ | ------------------------------------------------------------------ |
| `engine`     | Spring Boot service: REST API + gRPC service + orchestrator core.   |
| `worker-sdk` | Java library for writing workers (and, later, a sample worker app). |
| `dashboard`  | React + TypeScript + Vite SPA.                                      |

## Quickstart (Docker Compose)

Brings up Postgres + engine + worker + dashboard in one command:

```bash
docker compose up --build
```

- Engine API & actuator: <http://localhost:8080> (`/actuator/health`, `/actuator/prometheus`)
- Engine gRPC (worker protocol): `localhost:9090`
- Dashboard: <http://localhost:5173>
- Postgres: `localhost:5432` (`workflow` / `workflow`)

The `worker` service runs the sample worker (`echo` and `sleep` handlers) and long-polls the
engine over gRPC; scale it with `--scale worker=N` — each container's hostname becomes its
worker id. Flyway applies the schema on engine startup. Tear down with `docker compose down -v`.

Submit a single-task workflow and watch it succeed:

```bash
curl -s localhost:8080/workflows -H 'content-type: application/json' -d '{
  "name": "demo", "version": 1,
  "dag": { "tasks": [ { "key": "step1", "type": "echo", "input": { "msg": "hi" } } ] }
}'
# -> {"instanceId":"..."}; then GET /workflows/{instanceId} until "status":"SUCCEEDED"
```

## M2 demo: kill a worker mid-task

The M2 promise: kill a worker mid-task and another picks it up. Bring the stack up with two
workers and short lease knobs so recovery is quick to watch:

```bash
ENGINE_LEASE_DURATION=10s ENGINE_REAPER_INTERVAL=2s \
  docker compose up -d --build --scale worker=2
```

Submit a workflow whose single task sleeps for 60 seconds:

```bash
curl -s localhost:8080/workflows -H 'content-type: application/json' -d '{
  "name": "failover-demo", "version": 1,
  "dag": { "tasks": [ { "key": "nap", "type": "sleep", "input": { "seconds": 60 } } ] }
}'
# -> {"instanceId":"..."}
```

Find the worker holding the task's lease and kill it — the worker id defaults to the
container hostname, so it doubles as a container id:

```bash
docker compose exec postgres psql -U workflow -d workflow -tAc 'select worker_id from task_leases'
docker kill <worker-id-from-above>
```

The dead worker stops heartbeating, its lease expires, the reaper reschedules the task, and
the surviving worker claims attempt 2 (the killed container itself comes back via
`restart: unless-stopped`). Watch it recover:

```bash
curl -s localhost:8080/workflows/<instanceId>
# -> "status":"SUCCEEDED" with the task at "attempts":2

docker compose exec postgres psql -U workflow -d workflow -c 'select type from events order by created_at'
# -> WORKFLOW_SUBMITTED, TASK_DISPATCHED, TASK_LEASE_EXPIRED, TASK_DISPATCHED,
#    TASK_SUCCEEDED, WORKFLOW_SUCCEEDED
```

The automated version is [scripts/chaos.sh](scripts/chaos.sh): a submit loop feeds sleep
workflows while a random worker is killed every ~10s; at the end every workflow must have
`SUCCEEDED` with an empty dead-letter queue, and the script prints PASS or FAIL:

```bash
scripts/chaos.sh 60
```

## Build & test

```bash
./gradlew build
```

- **JDK:** the build targets **Java 21** via a Gradle toolchain (auto-provisioned if you
  don't have a JDK 21 installed). The Gradle **wrapper is 9.6.0**, which also runs on newer
  JDKs (e.g. 25).
- **Docker required for tests:** the engine integration test uses
  [Testcontainers](https://testcontainers.com/) to spin up a real Postgres, so a running
  Docker daemon is needed for `./gradlew build`.

Frontend:

```bash
cd dashboard
npm ci
npm run dev     # local dev server
npm run lint
npm run build
```

## Documentation

- [BUSINESS](docs/BUSINESS.md) — scope, milestones, demo intent.
- [ARCHITECTURE](docs/ARCHITECTURE.md) — tech stack, data model, key decisions.
- [STYLING](docs/STYLING.md) — code and UI conventions.
- [Diagrams](docs/diagrams/) — logical, process, and physical views.
- [ADRs](docs/adr/) — architecture decision records.
- [FUTURE](FUTURE.md) — deferred ideas and out-of-scope parking lot.
