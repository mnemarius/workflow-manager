# Future

Parking lot for ideas that are **out of scope now** but worth remembering. Adding something
here is the sanctioned alternative to expanding v1's surface area (see [docs/BUSINESS.md](docs/BUSINESS.md)
scope rules). Nothing here is a commitment.

## Deferred from M1

- **jOOQ code generation from the schema.** We use jOOQ's typed DSL over explicit SQL with a
  hand-written schema-constants file ([Schema.java](engine/src/main/java/com/workflowmanager/engine/persistence/Schema.java)).
  DDL-based codegen (DDLDatabase) fails on the Postgres `text`-column indexes because it runs the
  DDL through H2 (jOOQ #9336), and the codegen plugins can't reach jOOQ's native interpreter
  dialect. Revisit with Testcontainers-based codegen (generate against a real Postgres at build
  time) if generated tables become worth the extra build dependency.
- **`LISTEN`/`NOTIFY` for dispatch.** FetchTask currently polls the queue every ~500ms while a
  call is parked. Have the submit/promotion transaction `NOTIFY` so parked long-polls wake
  immediately instead of polling.

## Contributor onboarding (local development)

- **Clone-and-run local dev setup.** Later, a fresh contributor should be able to clone the repo,
  stand up **their own local Postgres**, and start developing without any access to a shared or
  personal database. Flyway migrations already define the schema, so onboarding is really about:
  a `docker compose up` that provisions an empty local Postgres, a `.env.example` (never a real
  `.env`) documenting the connection string, and a quickstart in the README.
- **My own database stays private.** No real connection string, credentials, dump, or host for the
  maintainer's database ever lands in the repo, docs, or compose files. Contributors run against
  *their own* throwaway local DB; the maintainer's data is never shared or reachable. Keep secrets
  in a git-ignored `.env`; commit only `.env.example` with placeholder values.

## Beyond v1 (see milestones M2+)

- Lease heartbeat / renewal RPC and expired-lease recovery (M2).
- Retry backoff strategies and a dead-letter queue (M2).
- Multi-step DAG dependencies and richer capability matching (M3).
- API-key auth on the REST and gRPC surfaces (ARCHITECTURE §Security).
- Trace-context propagation engine ↔ worker over gRPC metadata (M8).
- **Database connection pooling tuning.** Spring Boot already ships HikariCP as the default pool
  behind the `spring.datasource` config ([application.yml](engine/src/main/resources/application.yml)),
  so the pool exists but runs on defaults. Look into sizing it deliberately — `maximum-pool-size`,
  `minimum-idle`, connection/idle timeouts — against the engine's concurrency (long-poll dispatch,
  gRPC workers) and Postgres' `max_connections`, and expose the Hikari pool metrics via
  `/actuator/prometheus` so pool saturation is visible on the future Grafana dashboards.
- **Grafana dashboards over Prometheus.** The engine already exposes
  `/actuator/prometheus` ([application.yml](engine/src/main/resources/application.yml)). Add a
  Grafana service to the compose stack, wire it to scrape Prometheus, and ship starter dashboards
  (queue depth, task throughput, lease expiries, RPC latencies) so the metrics are actually
  visualized rather than just scraped.
