# 0003 — Declarative JSON DAGs as the workflow definition language

- **Status:** Accepted
- **Date:** 2026-06-24

## Context

Clients need to express multi-step workflows with dependencies. Two broad options: a
declarative data format submitted over the API, or code-as-workflow (Temporal style) with
deterministic replay. The latter implies code loading, a determinism sandbox, and
versioned replay — far beyond the v1 scope in [BUSINESS.md](../BUSINESS.md). See decision B
in [ARCHITECTURE.md](../ARCHITECTURE.md).

## Decision

We will accept **declarative JSON DAGs over REST**, validated against a JSON Schema at the
API boundary and stored as JSONB in `workflow_definitions.dag`. One wire format, one parser,
one set of errors. YAML is not accepted at the API (clients convert client-side).
Code-as-workflow with deterministic replay is **explicitly out of scope for v1**.

## Consequences

- A single, inspectable, versionable definition format; validation lives in one place.
- The dashboard can render the DAG directly from stored data.
- Expressiveness is bounded by what the JSON schema allows (no arbitrary control flow in
  the definition) — accepted for v1.
- Rejected: code-as-workflow — too much machinery for the target scope.
