-- Per-attempt execution budget, measured from the attempt's start (ADR 0007).
-- Enforced by capping lease grants at started_at + timeout_seconds; null = unbounded.
alter table task_instances
    add column timeout_seconds integer;
