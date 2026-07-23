# Business

The product vision, scope boundaries, milestone targets, and demo intent. **Consult this file whenever a task touches what the system should or shouldn't do, what counts as "done", scope decisions, milestone planning, or the demo story.** Keep the milestone/progress status current: update it when a milestone advances — see [AGENTS.md](../AGENTS.md) rule 10.

---

## What this project IS

**Postgres-only, single-JAR durable workflows — sized for one person to deploy and run.**

A coordinator service that accepts workflow definitions (JSON DAGs over REST), schedules their steps, hands work to worker processes over gRPC, tracks state in Postgres, retries failures, and exposes a dashboard to observe and control runs. The engine is one Spring Boot JAR; the substrate is one Postgres database. No Kafka, no Redis, no Zookeeper, no K8s assumed. M7 (multi-engine leader election) scales the engine horizontally *without* leaving that substrate.

"Fits on a single small VM" is a quality bar, not just a nice-to-have. Every feature is judged against it.

## What this project IS NOT

- Not a Temporal clone. No SDKs for six languages. No multi-region replication. No billing system.
- Not polyglot at first. **Java SDK only** for workers. Other languages may come later if there's a real reason.
- Not a "fancy distributed system" for its own sake. **A working narrow system beats a broken broad one.**

Anyone proposing a feature should first justify it against this scope. When tempted to add something new, write it into `FUTURE.md` and keep moving.

## Definition of "done" (v1)

A user can:

1. Submit a DAG of tasks via REST.
2. Watch them run in the dashboard.
3. Kill the engine mid-execution.
4. Restart the engine.
5. See the workflow **resume correctly**.

That is the bar. If a change does not move the project toward this bar (or one of the milestones below), it is out of scope for v1.

---

## Milestones

Each milestone is independently shippable and demoable. Do not skip ahead.

**Current status: M4 complete.** M0 (foundation), M1 (single-task submit + execute), M2 (retries, timeouts, leases, DLQ — kill a worker mid-task, another picks it up), M3 (DAG support — multi-step workflows with `dependsOn` edges, dependency promotion, and downstream cancellation on failure) and M4 (durable timers + cron schedules — `delaySeconds` on any task, plus workflows that start on a cron expression without being submitted) have shipped; **M5 — dashboard v1** is next.

| #  | Milestone                                                       | Demo proves…                                                 | Est. effort |
|----|-----------------------------------------------------------------|--------------------------------------------------------------|-------------|
| 0  | Project skeleton, CI, Docker Compose, Flyway migrations         | "I have a clean foundation."                                 | 1 week      |
| 1  | Single-task submit + execute (engine + 1 worker, no retries)    | A submitted task runs end-to-end.                            | 1–2 weeks   |
| 2  | Retries, timeouts, leases, DLQ                                  | Kill a worker mid-task → another picks it up.                | 1–2 weeks   |
| 3  | DAG support (multi-step workflows with dependencies)            | Order-fulfillment diamond: fan-out/fan-in + payment retries. | 2 weeks     |
| 4  | Durable timers + scheduled workflows (cron)                     | "Drip email" demo runs across days.                          | 1 week      |
| 5  | Dashboard v1: list workflows, drill into DAG, see logs          | Showable to other engineers.                                 | 2 weeks     |
| 6  | Live updates via SSE; cancel / retry / replay buttons           | The dashboard feels alive.                                   | 1 week      |
| 7  | Horizontal scaling: multi-engine instance (leader election)     | Run 2 engines, kill one, no work lost.                       | 1–2 weeks   |
| 8  | Observability: metrics dashboard, distributed tracing           | OpenTelemetry traces span engine → worker.                   | 1 week      |
| 9  | **(Stretch)** Sagas — compensation steps on failure             | Order-cancel demo undoes prior steps.                        | 2 weeks     |
| 10 | **(Stretch)** Signals — external events resume paused workflows | "Approval required" workflow waits for a human.              | 1–2 weeks   |

- **M0–M6** is the portfolio-worthy core.
- **M7–M10** is a senior-level stretch.
- Total realistic effort for M0–M6 is ~3–4 months of evenings/weekends for one person.

## Demo customer story

**Order fulfillment (chosen for M3).** An order moves through a **diamond DAG**:

```text
              ┌─► charge-payment ────┐
   validate ──┤                      ├─► ship ─► notify
              └─► reserve-inventory ─┘
```

`validate` fans out to `charge-payment` and `reserve-inventory` (which run in parallel); `ship` fans back in on **both**; `notify` is the sole sink. `charge-payment` is the step that *can* legitimately fail — the sample worker declines it ~40% of the time — so its retry policy (5 attempts, fixed 1s backoff) is exercised on most runs and the workflow still reaches `SUCCEEDED`. The runnable version is [scripts/order-demo.sh](../scripts/order-demo.sh); the handlers live in the sample worker (`worker-sdk`).

This meets the four requirements for the M3 demo:

- **Visually clear — not a single linear chain.** A diamond with a genuine fan-out and fan-in is a DAG worth looking at in the dashboard (M5).
- **Exercises every M3 primitive:** multi-step DAG with `dependsOn` edges, retries (flaky payment), and observable progress (task-status progression as it flows).
- **Java-native.** All five handlers are plain Java in the sample worker — no Python escape hatch.
- **A step that can legitimately fail** and benefit from retries: `charge-payment`, so the retry/chaos story isn't contrived.

**Drip email (added for M4).** A three-send sequence — `welcome`, then `tips`, then `offer` — where the steps are separated by `delaySeconds` rather than by work, and the whole chain is started by a cron schedule rather than submitted by hand. It proves the two things M4 adds that the order diamond cannot show: a step can wait days without a worker holding a lease, and a workflow can start on its own. The runnable version is [scripts/drip-demo.sh](../scripts/drip-demo.sh), which compresses production's day-scale waits to seconds so the mechanism is watchable; the handlers live in the sample worker.

New features should be validated by showing how they improve or extend these demos. Until M3, M0–M2 used toy tasks (`sleep(1s); echo "did thing"`); those handlers remain for the M2 failover demo and `scripts/chaos.sh`.

## Audience and intent

This is built primarily as a **portfolio piece**, aimed at distributed-systems and backend-infrastructure engineers (the kind of people who will read your leader election design, idempotency contract, and DLQ semantics, and probe them in interviews).

"Real product" is a **quality bar**, not a go-to-market goal: the system must actually work end-to-end, be deployable, and survive crashes — not be a toy. But there is no marketing, no pricing, no positioning against Temporal / Inngest / Hatchet. Adoption by other users is a bonus, not a goal.

That has three consequences:

- Stick to mainstream picks where reasonable (Spring Boot, Postgres, React) so the work is legible to recruiters and other engineers.
- Keep the systems-depth milestones (M7 multi-engine leader election, M8 observability) in scope — they are the most differentiated parts of the project for the target audience.
- Document major decisions as ADRs in `docs/adr/`. The decisions matter as much as the code.

## Scope discipline rules

- If you find yourself thinking "I should also add X" — write it down in `FUTURE.md` and keep going.
- No Kafka, no Redis, no Kubernetes, no microservices, no multi-tenancy until a real need appears. "Postgres until proven otherwise."
- Idempotency is a **product contract**, not an implementation detail. The engine guarantees at-least-once delivery; workers must be idempotent. This shows up in docs, SDK, and onboarding.

---

## Related docs

- [ARCHITECTURE](ARCHITECTURE.md) — how the system is built.
- [STYLING](STYLING.md) — code and UI conventions.
