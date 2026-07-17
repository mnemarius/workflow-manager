-- Dead-letter metadata (ADR 0009): the DLQ is the set of FAILED rows, not a separate table.
-- failure_reason records why the task dead-lettered; last_error keeps the newest attempt's
-- error visible mid-retry. Redrive clears both.
alter table task_instances
    add column failure_reason text,
    add column last_error jsonb;

-- Serves the GET /dead-letters listing: FAILED tasks, newest first.
create index task_instances_dead_letter_idx
    on task_instances (finished_at desc)
    where status = 'FAILED';
