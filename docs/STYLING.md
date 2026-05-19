# Styling

Code style, project layout, and UI conventions. **Consult this file whenever a task touches how code is written, named, organized, or commented; how logs/metrics/errors are shaped; or how the dashboard looks and feels.**

---

## Java / backend code style

### Language and language features

- **Java 21.** Use language features deliberately: records for DTOs, sealed types for closed unions (e.g. workflow statuses, event types), pattern matching for switch, virtual threads for worker-facing I/O.
- **Never** `Date` or `LocalDateTime` for persisted times. **Always** `Instant`, **always** UTC. Inject `java.time.Clock` so tests can fast-forward.
- Prefer immutability. Records, `final` fields, `List.copyOf(...)`. Mutability is fine in tight inner loops where it pays.

### Persistence

- **jOOQ** (or JDBI) only. No JPA, no Hibernate, no Spring Data repositories that hide SQL. The queries this project needs (`FOR UPDATE SKIP LOCKED`, advisory locks, JSONB operators) belong written out.
- Migrations live in `engine/src/main/resources/db/migration/` and are owned by Flyway. Forward-only. No editing applied migrations.
- All persisted timestamps are `TIMESTAMPTZ` in Postgres and `Instant` in Java.

### Module / package layout

```
engine/
  src/main/java/.../api/         # REST controllers, DTOs
  src/main/java/.../grpc/        # gRPC service impls
  src/main/java/.../orchestrator/# state machine, retry, timers — pure logic
  src/main/java/.../persistence/ # jOOQ queries, repositories
  src/main/java/.../config/      # Spring config, bean wiring
worker-sdk/
  src/main/java/.../             # client lib + sample worker app
dashboard/
  src/                           # React + TS
```

The **orchestrator** package is the most important boundary: it must be testable without a database. Push DB calls to the persistence layer; pass facts in, get decisions out.

### Naming

- Classes / records: `PascalCase` and named for what they are (`TaskLease`, `RetryPolicy`), not for layering jargon (no `TaskLeaseDTOImpl`).
- Methods: `camelCase`, verb-first (`scheduleRetry`, `acquireLease`).
- Tables and columns: `snake_case`, plural tables (`task_instances`).
- gRPC services and methods: `PascalCase` (`FetchTask`, `CompleteTask`).

### Error handling

- Domain errors are sealed types (`sealed interface SchedulingResult { record Scheduled(...) ...; record NoCapacity(...) ...; }`) — not exceptions for control flow.
- Reserve exceptions for genuinely exceptional cases (DB unreachable, malformed payload at the boundary).
- Every error logged must carry `workflow_id` and `task_id` in MDC.

### Comments

- Default to **no comments**. Names carry the meaning.
- Write a comment only when the *why* is non-obvious: a hidden constraint, an invariant, a workaround for a specific bug. One short line.
- No multi-paragraph docstrings. No comments that restate the code. No "// added for X" tags.

### Testing style

- JUnit 5 + AssertJ. **No Hamcrest, no `assertEquals` walls** — AssertJ chaining reads better.
- Orchestrator unit tests: no Spring context, no DB, no mocks of `Clock` via Mockito — use a real `Clock.fixed(...)`.
- Integration tests use Testcontainers Postgres. The same migrations run as in production.
- Test names: `methodUnderTest_state_expectedOutcome`, e.g. `acquireLease_whenAlreadyHeld_returnsConflict`.

---

## Frontend code style (`dashboard/`)

- **React + TypeScript + Vite + Tailwind + React Flow.**
- Components in `PascalCase.tsx`, hooks in `useCamelCase.ts`, utilities in `camelCase.ts`.
- One component per file. Co-locate component-specific styles, tests, and types next to it.
- **TypeScript strict mode on.** No `any` unless commented with a justification.
- **Tailwind utility-first.** No CSS files except the global Tailwind entry. No CSS-in-JS.
- Server state via React Query (or SWR). Local UI state via `useState` / `useReducer`. **No Redux** for this project size.
- API types are generated from the OpenAPI / gRPC schemas; do not hand-write request/response types.

### Code references in docs

When referencing files or code locations in **markdown documentation** (READMEs, ADRs, etc.), use markdown links so they are clickable:

- File: `[filename.ts](src/filename.ts)`
- Line: `[filename.ts:42](src/filename.ts#L42)`
- Range: `[filename.ts:42-51](src/filename.ts#L42-L51)`

Do not use backticks or HTML for file references in docs.

---

## UI / dashboard visual conventions

The dashboard is the public face of the project. It should look **calm, technical, and trustworthy** — not flashy.

### Layout

- Persistent left nav: Workflows, Definitions, Workers, Settings.
- Top bar: environment label, search, account.
- Content area is a single column at most ~1280 px wide, centered. Detail views can use a two-column split (graph on the left, side panel on the right).

### DAG view

- React Flow with subtle grid background.
- Node shapes:
  - **Rounded rectangle** for normal tasks.
  - **Hexagon** for human-input / signal tasks (when M10 lands).
  - **Diamond** for conditional branches (if/when introduced).
- Node color is driven by **status**, not by type — status is what the operator cares about.

### Status colors (single source of truth)

These colors are used everywhere a status appears (DAG nodes, table rows, badges, charts):

| Status              | Color                | Tailwind token  |
|---------------------|----------------------|-----------------|
| `PENDING`           | Slate / neutral grey | `slate-400`     |
| `READY`             | Blue                 | `sky-500`       |
| `RUNNING`           | Indigo, pulsing      | `indigo-500`    |
| `SUCCEEDED`         | Green                | `emerald-500`   |
| `FAILED`            | Red                  | `rose-500`      |
| `RETRY_SCHEDULED`   | Amber                | `amber-500`     |
| `CANCELLED`         | Slate, struck-through| `slate-500`     |

Always pair color with an icon or text label — never rely on color alone (accessibility).

### Typography

- UI font: system font stack (`ui-sans-serif`).
- Code / IDs / payloads: `ui-monospace` (`font-mono`).
- One scale, Tailwind defaults: `text-xs` for table meta, `text-sm` for body, `text-base` for headings inside cards, `text-2xl` for page titles.

### Components

- Buttons: primary (filled indigo), secondary (outline), destructive (filled rose). No more than one primary button per view.
- Tables: zebra striping off; subtle row hover; sticky header on long lists.
- Empty states: icon + one-sentence explanation + one action. Never a blank screen.

### Live updates

- SSE-driven status changes animate softly (200 ms color crossfade). No flashing, no toast spam.
- A workflow page that is "live" shows a small pulsing dot in the header. Static (historical) views do not.

### Iconography

- One icon set across the app — **Lucide**. Do not mix icon families.

---

## Logging and observability conventions

- **Structured JSON logs** via Logback. One event per line.
- Every log line emitted from request- or task-handling code carries `workflow_id` and `task_id` in MDC. The orchestrator sets these; do not log without them.
- Metric names: `workflow_<noun>_<verb>` (e.g. `workflow_tasks_dispatched_total`, `workflow_lease_expired_total`). Lowercase, snake_case, Prometheus-style.
- Trace span names match method intent, not class names: `orchestrator.schedule_next_step`, not `OrchestratorService.scheduleNextStep`.

---

## Documentation style

- ADRs in `docs/adr/`, one decision per file, named `NNNN-short-title.md`. Keep them short: context, decision, consequences.
- Headings in docs are sentence case (`## Key design decisions`), not Title Case.
- Prefer tables for comparisons and lists of options; prose for reasoning.

---

## Related docs

- [BUSINESS](BUSINESS.md) — scope, milestones, demo story.
- [ARCHITECTURE](ARCHITECTURE.md) — system design and tech stack.
