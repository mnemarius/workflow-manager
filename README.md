# workflow-manager

**Postgres-only, single-JAR durable workflows — sized for one person to deploy and run.**

A coordinator service that accepts workflow definitions (JSON DAGs over REST), schedules
their steps, hands work to worker processes over gRPC, tracks state in PostgreSQL, retries
failures, and exposes a dashboard to observe and control runs. No Kafka, no Redis, no
Kubernetes — Postgres is the only substrate.

> Status: **M4 — durable timers and cron schedules.** Everything from M2 and M3 still holds — kill
> a worker mid-task and another picks it up (leases, heartbeats, a reaper, retries with backoff,
> per-attempt timeouts, a dead-letter queue with redrive), over workflows that are DAGs with
> `dependsOn` edges, fan-out/fan-in and failure cascade. M4 adds time: any task can declare
> `delaySeconds` and wait without a worker holding it, and a workflow can start on a cron
> expression instead of being submitted. The live dashboard arrives next (see
> [docs/BUSINESS.md](docs/BUSINESS.md)).

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

The `worker` service runs the sample worker (`echo` and `sleep` handlers, the M3 order-fulfillment
handlers `validate` / `charge-payment` / `reserve-inventory` / `ship` / `notify`, and the M4
drip-email handlers `send-welcome` / `send-tips` / `send-offer`)
and long-polls the engine over gRPC; scale it with `--scale worker=N` — each container's hostname
becomes its worker id. Flyway applies the schema on engine startup. Tear down with
`docker compose down -v`.

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

## M3 demo: order-fulfillment DAG

The M3 promise: multi-step workflows with dependencies. The reference demo is an order-fulfillment
**diamond** — `validate` fans out to `charge-payment` and `reserve-inventory`, both fan back into
`ship`, and `ship` feeds the sole sink `notify`:

```text
              ┌─► charge-payment ────┐
   validate ──┤                      ├─► ship ─► notify
              └─► reserve-inventory ─┘
```

`charge-payment` is a **legitimately flaky** step: the sample worker fails it ~40% of the time, so
its `retryPolicy` (5 attempts, fixed 1s backoff) is exercised on most runs and the demo shows a real
retry story while still reaching `SUCCEEDED`. Bring the stack up and submit the DAG:

```bash
docker compose up -d --build

curl -s localhost:8080/workflows -H 'content-type: application/json' -d '{
  "name": "order-fulfillment", "version": 1,
  "dag": { "tasks": [
    { "key": "validate", "type": "validate" },
    { "key": "charge-payment", "type": "charge-payment", "dependsOn": ["validate"],
      "retryPolicy": { "maxAttempts": 5, "backoffStrategy": "fixed", "initialDelaySeconds": 1 } },
    { "key": "reserve-inventory", "type": "reserve-inventory", "dependsOn": ["validate"] },
    { "key": "ship", "type": "ship", "dependsOn": ["charge-payment", "reserve-inventory"] },
    { "key": "notify", "type": "notify", "dependsOn": ["ship"] }
  ] }
}'
# -> {"instanceId":"..."}
```

Watch it flow — `charge-payment` climbs through attempts while `reserve-inventory` proceeds in
parallel, `ship` waits for both, and the workflow output is the keyed aggregate of the sink:

```bash
curl -s localhost:8080/workflows/<instanceId>
# tasks progress PENDING -> READY -> RUNNING -> SUCCEEDED; charge-payment shows "attempts">1 on
# flaky runs. Final: "status":"SUCCEEDED" with "output":{"notify":{"notified":true}}
```

The automated version is [scripts/order-demo.sh](scripts/order-demo.sh): it submits the diamond,
polls the workflow, prints the per-task status progression (so the fan-out/fan-in and the payment
retries are visible), and prints PASS once the workflow is `SUCCEEDED` with the `notify` sink key in
its output — or FAIL on timeout:

```bash
scripts/order-demo.sh
```

## M4 demo: drip email on a schedule

The M4 promise: a step can wait without a worker holding it, and a workflow can start on its own.
The reference demo is a three-send drip sequence whose steps are separated by `delaySeconds` rather
than by work, registered as a cron schedule instead of submitted:

```bash
curl -X POST localhost:8080/schedules -H 'content-type: application/json' -d '{
  "name": "drip-email", "workflowName": "drip-email", "workflowVersion": 1,
  "cronExpression": "0 0 9 * * *", "timezone": "Europe/Oslo",
  "dag": { "tasks": [
    { "key": "welcome", "type": "send-welcome" },
    { "key": "tips",  "type": "send-tips",  "dependsOn": ["welcome"], "delaySeconds": 259200 },
    { "key": "offer", "type": "send-offer", "dependsOn": ["tips"],    "delaySeconds": 259200 }
  ] }
}'
```

Cron expressions are Spring's six-field form (seconds first) and are evaluated in the schedule's own
timezone, so `0 0 9 * * *` stays at 09:00 local across DST. `delaySeconds` is measured from when the
step became ready — three days *after the previous send*, not after submit — and the wait lives in
Postgres, so nothing is lost if the engine restarts mid-drip. A schedule that comes due after an
outage fires once for the latest missed slot rather than replaying the backlog (ADR 0011).

Inspect and control schedules with `GET /schedules`, `GET /schedules/{id}/runs`,
`PATCH /schedules/{id}?paused=true` and `DELETE /schedules/{id}`.

The automated version is [scripts/drip-demo.sh](scripts/drip-demo.sh): it registers a schedule,
waits for the engine to fire it with nothing submitted by hand, then watches the sends unfold. It
compresses the day-scale waits to seconds so the run is watchable:

```bash
scripts/drip-demo.sh
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
