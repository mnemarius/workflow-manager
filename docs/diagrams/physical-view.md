# Physical view

> **Keep in sync:** these diagrams describe the system as built. Whenever you change the architecture, modules, runtime flow, or deployment topology, update the affected view in the same change — do not let them drift.

Deployment topology as expressed in [docker-compose.yml](../../docker-compose.yml): everything comes up with one `docker compose up`. Dotted arrows are `depends_on` startup order; thick arrows are runtime traffic.

```mermaid
flowchart TB
    PG[("postgres:16-alpine\n5432, pgdata volume")]
    ENGINE["engine\nSpring Boot JAR container\nREST 8080 . gRPC 9090\n/actuator/health, /actuator/prometheus"]
    WORKER["worker x N (--scale worker=N)\nJava SDK sample app\nrestart: unless-stopped"]
    DASH["dashboard\nnginx serving React\n5173 -> 80"]

    ENGINE -. "depends_on: service_healthy" .-> PG
    WORKER -. depends_on .-> ENGINE
    DASH -. depends_on .-> ENGINE

    ENGINE == SQL ==> PG
    WORKER == "gRPC 9090 (FetchTask/CompleteTask)" ==> ENGINE
    DASH == "REST 8080 + SSE" ==> ENGINE
```

## Services

| Service     | Image / build                          | Ports        | Purpose                                             |
|-------------|------------------------------------------|--------------|--------------------------------------------------------|
| `postgres`  | `postgres:16-alpine`                     | `5432:5432`  | Sole state store; `pgdata` volume for durability.       |
| `engine`    | build from `engine/Dockerfile`           | `8080` REST, `9090` gRPC | Spring Boot JAR: REST API, gRPC service, orchestrator, migrations. |
| `worker`    | Java SDK sample app                      | none exposed | Long-polls the engine over gRPC, runs task handlers. Scales with `--scale worker=N` (worker id defaults to the container hostname); `restart: unless-stopped` resurrects killed workers. |
| `dashboard` | build from `dashboard/` (nginx + Vite build) | `5173:80`    | Serves the React SPA; talks to the engine over REST/SSE. |

Startup order: `postgres` must report healthy before `engine` starts (Flyway migrations run on engine boot); `worker` and `dashboard` wait for `engine`.

The engine's timing knobs are env-overridable on the compose service (`ENGINE_LEASE_DURATION`, default 30s; `ENGINE_REAPER_INTERVAL`, default 5s; `ENGINE_SCHEDULE_SWEEP_INTERVAL`, default 5s) — the failover demo and [scripts/chaos.sh](../../scripts/chaos.sh) shorten the lease ones to make recovery visible.

"Fits on a single small VM" is the quality bar — no Kafka, no Redis, no Kubernetes. One `docker compose up` brings up the whole stack; `docker compose down -v` tears it down cleanly.
