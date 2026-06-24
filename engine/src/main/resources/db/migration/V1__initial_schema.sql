-- Initial schema for the workflow engine.
-- Six tables: definitions, instances, tasks, leases, events, outbox.
-- All timestamps are TIMESTAMPTZ (UTC). Payloads are opaque JSONB.

create table workflow_definitions (
    id         uuid primary key     default gen_random_uuid(),
    name       text        not null,
    version    integer     not null,
    dag        jsonb       not null,
    created_at timestamptz not null default now(),
    unique (name, version)
);

create table workflow_instances (
    id            uuid primary key     default gen_random_uuid(),
    definition_id uuid        not null references workflow_definitions (id),
    status        text        not null,
    input         jsonb,
    output        jsonb,
    created_at    timestamptz not null default now(),
    started_at    timestamptz,
    finished_at   timestamptz,
    updated_at    timestamptz not null default now(),
    constraint workflow_instances_status_check
        check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

create table task_instances (
    id                   uuid primary key     default gen_random_uuid(),
    workflow_instance_id uuid        not null references workflow_instances (id),
    task_key             text        not null,
    status               text        not null,
    attempts             integer     not null default 0,
    max_attempts         integer     not null default 1,
    retry_policy         jsonb,
    input                jsonb,
    output               jsonb,
    scheduled_at         timestamptz,
    started_at           timestamptz,
    finished_at          timestamptz,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    unique (workflow_instance_id, task_key),
    constraint task_instances_status_check
        check (status in ('PENDING', 'READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'RETRY_SCHEDULED', 'CANCELLED'))
);

-- Core queue access path: workers poll READY tasks whose scheduled_at is due.
create index task_instances_ready_idx
    on task_instances (status, scheduled_at);

create table task_leases (
    task_id          uuid primary key references task_instances (id),
    worker_id        text        not null,
    acquired_at      timestamptz not null default now(),
    lease_expires_at timestamptz not null
);

create index task_leases_expiry_idx
    on task_leases (lease_expires_at);

create table events (
    id                   bigint generated always as identity primary key,
    workflow_instance_id uuid        references workflow_instances (id),
    task_instance_id     uuid        references task_instances (id),
    type                 text        not null,
    data                 jsonb,
    created_at           timestamptz not null default now()
);

create index events_workflow_idx
    on events (workflow_instance_id, id);

create table outbox (
    id           bigint generated always as identity primary key,
    topic        text        not null,
    payload      jsonb       not null,
    created_at   timestamptz not null default now(),
    processed_at timestamptz
);

-- Drain query targets unprocessed rows in insertion order.
create index outbox_unprocessed_idx
    on outbox (id)
    where processed_at is null;
