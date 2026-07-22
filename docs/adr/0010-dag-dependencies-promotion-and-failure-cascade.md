# 0010 — DAG dependencies: edge table, event-driven promotion, and failure cascade

- **Status:** Accepted
- **Date:** 2026-07-22

## Context

M3 turns single-task workflows into multi-step DAGs: a task may declare
`dependsOn: [taskKey, …]`, and must not run until every upstream task has succeeded. That raises
four questions, each with a cheap-but-wrong option and a correct one: how to **store** the edges,
where to **validate** the graph, how to **promote** a task once its dependencies finish, and what to
do to **downstream** tasks when an upstream one dies. The guiding constraints are unchanged — Postgres
is the only substrate and the source of truth (working rule 2), promotion must survive an engine
restart, and the fan-in join must never be orphaned.

## Decision

**A normalized `task_dependencies(task_id, depends_on_task_id)` edge table
([V5](../../engine/src/main/resources/db/migration/V5__task_dependencies.sql)),** one row per edge
(`task_id` depends on `depends_on_task_id`), PK `(task_id, depends_on_task_id)` plus an index on
`depends_on_task_id`. Not a JSONB `dependsOn` array on the task row: the hot promotion path is a clean
`NOT EXISTS` join over indexable columns ("are all my upstreams SUCCEEDED?"), and the reverse lookup
("who depends on the task that just finished/failed?") is a plain index scan — a JSONB array would
force a containment query and a GIN index for the same thing, with worse ergonomics in the recursive
cascade below.

**Graph validation at the API boundary, before anything persists.** Shape (task objects, `dependsOn`
is an array of strings) is enforced by JSON Schema (ADR 0003); the semantic rules JSON Schema cannot
express — unique task keys, every `dependsOn` ref resolves to a real key, no self-dependency, and
**acyclicity** — are enforced by
[DagStructureValidator](../../engine/src/main/java/com/workflowmanager/engine/orchestrator/DagStructureValidator.java),
pure logic that detects cycles with Kahn's topological sort. An invalid DAG is a `400`; a persisted
DAG is always runnable.

**READY-vs-PENDING seeding on submit.** A task with no dependencies is inserted `READY` (claimable
immediately); a task with dependencies is inserted `PENDING`. `PENDING` is deliberately **not**
claimable — the claim query only ever selects due `READY`/`RETRY_SCHEDULED` rows — so a task cannot
run before its upstreams.

**Event-driven promotion in the completion transaction — no background sweeper.** When a task
transitions to `SUCCEEDED`,
[`promoteReadyDependents`](../../engine/src/main/java/com/workflowmanager/engine/persistence/WorkflowRepository.java)
flips every `PENDING` task in that workflow whose dependencies are now **all** `SUCCEEDED`
(`NOT EXISTS` an unfinished upstream) to `READY`, in the same transaction, appending `TASK_READY`.
This mirrors ADR 0006's no-sweeper stance: no periodic "scan for promotable tasks" job exists, so
there is no promotion latency and no extra moving part.

The subtle part is a **fan-in race**. Two sibling completions (e.g. `charge-payment` and
`reserve-inventory` both finishing, each a parent of `ship`) run in independent gRPC `CompleteTask`
transactions. Under `READ COMMITTED` a bare conditional `UPDATE … WHERE NOT EXISTS(unfinished dep)`
lets **both** miss the join: neither transaction sees the other's not-yet-committed `SUCCEEDED`, so
each concludes `ship` still has an unfinished upstream, and `ship` is orphaned in `PENDING` forever.
We close this by taking a **blocking `FOR UPDATE`** on the workflow's `PENDING` rows, **ordered by
id**, at the top of promotion: the second completer blocks until the first commits, then re-evaluates
on a fresh snapshot and promotes `ship`. Ordering the lock by id makes it deadlock-free; it is
deliberately **not** `SKIP LOCKED` — skipping the locked rows would reintroduce exactly the orphan we
are preventing. The lock is scoped to one workflow instance, so completions in *other* workflows are
unaffected. The cost is accepted and bounded: per-workflow completion serialization, consistent with
the single-threaded per-workflow orchestration model (ARCHITECTURE decision I).

**Failure cascade on dead-letter; restore on redrive; one shared failure path.** When a task
exhausts its retry budget and dead-letters, its **transitive** dependents (a recursive CTE walking the
edges in reverse) are cancelled `PENDING → CANCELLED` (event `TASK_CANCELLED`) and the workflow fails
— a dependent of a just-failed task can only be `PENDING`, since it was never dispatchable. DLQ
redrive restores them `CANCELLED → PENDING` (`restoreCancelledDependents`) — back to `PENDING`, not
`READY`, because they must re-block until their own upstreams succeed. Both live in the single shared
failure path
([TaskFailureResolver](../../engine/src/main/java/com/workflowmanager/engine/application/TaskFailureResolver.java)),
so worker-reported failures and reaper-driven recoveries (crash, timeout) cascade identically.

**Workflow output = keyed aggregate of sink-task outputs.** A *sink* is a task whose id never appears
as any edge's `depends_on_task_id` (nothing depends on it). On success the workflow output is
`{ "<sinkKey>": <output>, … }` over the SUCCEEDED sinks, ordered by key. This is applied **uniformly**,
which intentionally breaks the pre-M3 single-task "bare output" contract: a one-task workflow now
yields `{"<key>": <output>}` rather than that task's raw output. One rule for every DAG shape beats a
special case that only the degenerate single-node graph would ever hit.

## Consequences

- The promotion hot path and the cascade are both plain SQL over an indexed edge table; no graph
  is reconstructed in application memory at runtime (the in-memory graph exists only in the validator,
  pre-persist).
- `PENDING` and `CANCELLED`, previously reserved lifecycle states, are now live: `PENDING` for
  un-met dependencies, `CANCELLED` for a cascade-cancelled downstream.
- Per-workflow completions serialize on the `PENDING`-set lock. This is a scoped throughput cost, not
  a global one — it is the price of never orphaning a join, and it fits the single-threaded
  per-workflow model.
- **Partial-redrive edge case:** redriving one dead-lettered task in a workflow that had multiple
  failures restores that task's dependents to `PENDING`, but a restored task can still be effectively
  blocked by a *different* upstream that is itself still `FAILED`. This is correct — promotion only
  fires when **all** dependencies are `SUCCEEDED` — but such a task presents as `PENDING` (waiting)
  rather than `CANCELLED`, which can read as "stuck". It unblocks when the other failure is redriven
  too.
- Clients that relied on the pre-M3 bare single-task output must read `output["<key>"]`. Called out
  loudly because it is a wire-visible break.

## Alternatives considered

- **JSONB `dependsOn` array on the task row.** Rejected: the promotion `NOT EXISTS` join and the
  reverse dependents lookup both want indexed scalar columns; a JSONB array needs containment queries
  and a GIN index to approximate the same access, and the recursive cascade is far cleaner over an
  edge table.
- **A background promotion sweeper** that periodically scans for `PENDING` tasks whose deps are all
  done. Rejected for the same reasons ADR 0006 rejected a retry sweeper: an extra moving part, added
  latency, and no correctness benefit over doing the promotion transactionally at completion.
  (Notably a naive sweeper would *also* have to solve the fan-in visibility problem.)
- **Special-casing single-task output** to preserve the bare-output contract. Rejected: a branch in
  the output path that exists solely for the one-node graph, versus one uniform sink-aggregate rule.
  The uniform rule won even though it is a visible break.
