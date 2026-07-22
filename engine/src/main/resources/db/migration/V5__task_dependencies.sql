-- Dependency edges between tasks of one workflow instance (M3 DAG support).
-- One row per edge: task_id waits on depends_on_task_id. A task with no rows here
-- starts READY; a task with edges starts PENDING until its dependencies finish.
create table task_dependencies (
    task_id            uuid not null references task_instances (id),
    depends_on_task_id uuid not null references task_instances (id),
    primary key (task_id, depends_on_task_id)
);

-- Step 3's promotion query looks up dependents by the task that just finished.
create index task_dependencies_depends_on_idx
    on task_dependencies (depends_on_task_id);
