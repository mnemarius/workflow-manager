-- Cron-triggered workflows (M4, ADR 0011). One row per schedule; the sweeper claims
-- rows whose next_fire_at is due, submits the workflow, and advances next_fire_at in the
-- same transaction. Postgres holds the whole schedule, so restarts lose nothing.
create table workflow_schedules (
    id              uuid primary key     default gen_random_uuid(),
    name            text        not null unique,
    workflow_name   text        not null,
    workflow_version integer    not null,
    dag             jsonb       not null,
    input           jsonb,
    cron_expression text        not null,
    timezone        text        not null default 'UTC',
    next_fire_at    timestamptz not null,
    last_fired_at   timestamptz,
    paused          boolean     not null default false,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

-- The sweeper's access path: due, unpaused schedules in fire order.
create index workflow_schedules_due_idx
    on workflow_schedules (next_fire_at)
    where paused = false;

-- Which schedule fired a run, and for which nominal fire time. The unique index is the
-- durable guard against a fire being submitted twice — it survives engine restarts and
-- holds once M7 runs several engines against the same database.
alter table workflow_instances
    add column schedule_id uuid references workflow_schedules (id),
    add column fired_for   timestamptz;

create unique index workflow_instances_schedule_fire_idx
    on workflow_instances (schedule_id, fired_for)
    where schedule_id is not null;
