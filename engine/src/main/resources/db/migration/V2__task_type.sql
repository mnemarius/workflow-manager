-- The task type is the capability/handler name a worker must advertise (see ADR 0005).
-- Stored per task so the queue can match ready work to a worker's declared capabilities.
alter table task_instances
    add column type text;

create index task_instances_type_idx
    on task_instances (type);
