# Architecture diagrams

> **Keep in sync:** these diagrams describe the system as built. Whenever you change the architecture, modules, runtime flow, or deployment topology, update the affected view in the same change — do not let them drift.

Three views, each answering a different question about the same system (a lightweight take on the classic 4+1 view model — we skip the scenario/use-case view since [BUSINESS.md](../BUSINESS.md) already covers that).

| View                                       | Question it answers                                              |
|---------------------------------------------|--------------------------------------------------------------------|
| [Logical view](logical-view.md)              | What are the modules/components, and how do they depend on each other? Includes the data model. |
| [Process view](process-view.md)              | What happens at runtime — submit → execute, the gRPC long-poll?  |
| [Physical view](physical-view.md)            | What actually runs where — containers, ports, startup order?     |

## Diagram conventions

- Diagrams are **Mermaid**, rendered inline by GitHub and most markdown viewers — no images to regenerate.
- No hardcoded colors; default Mermaid theming so diagrams stay readable in light and dark mode.
- Each view is scoped to the current milestone ([BUSINESS.md](../BUSINESS.md), M3: multi-step DAGs — dependency promotion and failure cascade — on top of the M2 retries, timeouts, leases, and DLQ). Durable timers and multi-engine scaling arrive in later milestones and should be added here when they land.
- Full prose detail (tech stack, decisions A–H, tables) lives in [ARCHITECTURE.md](../ARCHITECTURE.md); these diagrams are a visual index into it, not a replacement.
