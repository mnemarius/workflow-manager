# AGENTS.md

Guidance for any agent (Claude or otherwise) working in this repository. **Read this file first.** It tells you *where to look* for the rules of each kind of task, so your output stays aligned with how this project is meant to be built.

The repository's source of truth for project intent lives in three documents:

| Doc                                       | Owns                                                                                                   |
|-------------------------------------------|--------------------------------------------------------------------------------------------------------|
| [docs/BUSINESS.md](docs/BUSINESS.md)         | What the project is and isn't, scope, milestones, definition of "done", demo story.                    |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Tech stack, system topology, data model, key design decisions, security, observability, deployment.   |
| [docs/STYLING.md](docs/STYLING.md)           | Code style (Java + TS), naming, package layout, comment policy, UI visual conventions, log/metric shape. |

---

## How to use these docs

Before doing meaningful work, **load the relevant doc(s) into context** based on the kind of task:

### Routing rules

| If the task is about…                                                              | Read first                                |
|------------------------------------------------------------------------------------|-------------------------------------------|
| Whether a feature is in scope, milestone planning, the demo, what "done" means    | `docs/BUSINESS.md`                        |
| Database schema, migrations, queries, locking, transactions                         | `docs/ARCHITECTURE.md` (Data model, Decisions A/D/G) |
| Worker protocol, retries, leases, timers, crash recovery                            | `docs/ARCHITECTURE.md` (Decisions C/D/E/F/G)         |
| Spring Boot configuration, gRPC service wiring, package layout                      | `docs/ARCHITECTURE.md` + `docs/STYLING.md` (layout)  |
| Writing or refactoring Java code (naming, records, sealed types, error handling)    | `docs/STYLING.md` (Java section)                     |
| Writing tests (unit vs integration, Testcontainers, assertion style)                | `docs/STYLING.md` (Testing) + `docs/ARCHITECTURE.md` (Testing) |
| Logs, metrics, traces, MDC fields                                                   | `docs/ARCHITECTURE.md` (Observability) + `docs/STYLING.md` (Logging) |
| Frontend code — components, hooks, TS, Tailwind, React Flow                         | `docs/STYLING.md` (Frontend + UI sections)           |
| UI look & feel — colors, status badges, layout, animations                          | `docs/STYLING.md` (UI / dashboard visual conventions) |
| Deployment, docker-compose, Fly.io / Railway                                        | `docs/ARCHITECTURE.md` (Deployment)                  |
| Security, API keys, payload handling                                                | `docs/ARCHITECTURE.md` (Security)                    |
| Writing an ADR or other documentation                                               | `docs/STYLING.md` (Documentation style) + `docs/BUSINESS.md` (intent) |

If a task spans categories (e.g. "build the dashboard's workflow detail page"), read **all** relevant rows. When the three docs disagree, the order of precedence is:

1. **BUSINESS** wins on *what* and *whether*.
2. **ARCHITECTURE** wins on *how the system fits together*.
3. **STYLING** wins on *how the code and UI look*.

---

## Working rules

1. **Stay inside scope.** If a request would expand v1's surface area, push back or write the idea into `FUTURE.md` instead of building it. See `docs/BUSINESS.md`.
2. **Postgres is the source of truth.** Any feature that depends on in-memory state to survive restarts is broken by definition.
3. **Workers are idempotent; the engine is at-least-once.** This is a product contract, not an implementation detail.
4. **No JPA / Hibernate.** Use jOOQ. See `docs/ARCHITECTURE.md` and `docs/STYLING.md`.
5. **`Instant` + UTC + injected `Clock`** for all time. No `Date`, no `LocalDateTime` for persisted timestamps.
6. **Structured JSON logs with `workflow_id` and `task_id` in MDC.** Always.
7. **No premature infrastructure.** No Kafka, Redis, Kubernetes, microservices, or multi-tenancy without a real, observed need.
8. **Default to no comments.** Write one only when the *why* is non-obvious.
9. **Document major decisions as ADRs** under `docs/adr/` — one file per decision, short and to the point.

---

## When in doubt

- If a question is about *what* to build → `docs/BUSINESS.md`.
- If a question is about *how* the system works → `docs/ARCHITECTURE.md`.
- If a question is about *how* the code or UI should look → `docs/STYLING.md`.
- If none of those answer it, ask the user before guessing.
