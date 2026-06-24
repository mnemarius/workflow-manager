# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

This repo does **not** use a `CONTEXT.md` glossary. Domain knowledge is split across three docs, routed by topic via the table in [AGENTS.md](../../AGENTS.md):

- **[docs/BUSINESS.md](../BUSINESS.md)** — what the project is and isn't, scope, milestones, definition of "done".
- **[docs/ARCHITECTURE.md](../ARCHITECTURE.md)** — tech stack, system topology, data model, key design decisions, observability, deployment.
- **[docs/STYLING.md](../STYLING.md)** — code style (Java + TS), naming, package layout, comment policy, UI conventions, log/metric shape.
- **`docs/adr/`** — Architectural Decision Records. May not exist yet; ADRs are added lazily per AGENTS.md working rule #9.

Use the AGENTS.md routing rules table to pick which doc(s) to load before doing meaningful work. Precedence when they disagree: BUSINESS > ARCHITECTURE > STYLING.

If `docs/adr/` doesn't exist yet, proceed silently — don't flag its absence. ADRs are created when decisions actually crystallise.

## Use the project's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in the relevant doc above. Don't drift to synonyms.

If the concept you need isn't documented yet, that's a signal — either you're inventing language the project doesn't use (reconsider), or there's a real gap worth surfacing.

## Flag ADR conflicts

If your output contradicts an existing ADR under `docs/adr/`, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders) — but worth reopening because…_
