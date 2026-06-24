# workflow-manager

**Postgres-only, single-JAR durable workflows — sized for one person to deploy and run.**

A coordinator service that accepts workflow definitions (JSON DAGs over REST), schedules
their steps, hands work to worker processes over gRPC, tracks state in PostgreSQL, retries
failures, and exposes a dashboard to observe and control runs. No Kafka, no Redis, no
Kubernetes — Postgres is the only substrate.

> Status: **M0 — clean foundation.** Build, migrations, Docker Compose and CI are in place.
> Submit/execute, retries, DAGs, timers and the live dashboard arrive in later milestones
> (see [docs/BUSINESS.md](docs/BUSINESS.md)).

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

Brings up Postgres + engine + dashboard in one command:

```bash
docker compose up --build
```

- Engine API & actuator: <http://localhost:8080> (`/actuator/health`, `/actuator/prometheus`)
- Dashboard: <http://localhost:5173>
- Postgres: `localhost:5432` (`workflow` / `workflow`)

Flyway applies the schema (six tables) on engine startup. Tear down with
`docker compose down -v`.

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
- [ADRs](docs/adr/) — architecture decision records.
