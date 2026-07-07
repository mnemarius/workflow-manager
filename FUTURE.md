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

## Beyond v1 (see milestones M2+)

- Lease heartbeat / renewal RPC and expired-lease recovery (M2).
- Retry backoff strategies and a dead-letter queue (M2).
- Multi-step DAG dependencies and richer capability matching (M3).
- API-key auth on the REST and gRPC surfaces (ARCHITECTURE §Security).
- Trace-context propagation engine ↔ worker over gRPC metadata (M8).
