# 0002 — jOOQ for persistence, not JPA/Hibernate

- **Status:** Accepted
- **Date:** 2026-06-24

## Context

The engine's load-bearing queries are not ORM-shaped: `FOR UPDATE SKIP LOCKED` lock hints,
Postgres advisory locks, and JSONB operators on opaque payloads. JPA/Hibernate fights all
three — hidden SQL, lock-mode leakage, and awkward JSONB mapping. See the "DB access" row
and "Common pitfalls" in [ARCHITECTURE.md](../ARCHITECTURE.md).

## Decision

We will use **jOOQ** for database access and write the load-bearing SQL explicitly. No JPA,
no Hibernate, no Spring Data repositories that hide SQL. Flyway owns the schema; migrations
are forward-only in `engine/src/main/resources/db/migration/`.

## Consequences

- The queries that matter are visible, reviewable, and tuned by hand.
- jOOQ's typed DSL keeps SQL checked at compile time without an ORM's abstraction leaks.
- More boilerplate for trivial CRUD than an ORM would need — accepted; this project is
  query-heavy, not CRUD-heavy.
- All persisted timestamps are `TIMESTAMPTZ` (Postgres) / `Instant` (Java), UTC only.
