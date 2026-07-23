-- Per-task delay (M4 durable timers). A task with delay_seconds becomes claimable
-- delay_seconds after it is promoted to READY, not immediately: scheduled_at is
-- offset at promotion time. Retries ignore it — backoff owns the retry schedule.
alter table task_instances
    add column delay_seconds integer;

alter table task_instances
    add constraint task_instances_delay_seconds_non_negative
        check (delay_seconds is null or delay_seconds >= 0);
