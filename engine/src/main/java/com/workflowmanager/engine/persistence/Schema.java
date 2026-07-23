package com.workflowmanager.engine.persistence;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.Instant;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.SQLDataType;

/**
 * Hand-written jOOQ references for the Flyway-owned schema. Codegen from DDL is deferred
 * (see FUTURE.md); these keep the SQL explicit and value-typed without an ORM.
 */
final class Schema {

    private Schema() {}

    static final Table<?> WORKFLOW_DEFINITIONS = table(name("workflow_definitions"));
    static final Table<?> WORKFLOW_INSTANCES = table(name("workflow_instances"));
    static final Table<?> TASK_INSTANCES = table(name("task_instances"));
    static final Table<?> TASK_LEASES = table(name("task_leases"));
    static final Table<?> TASK_DEPENDENCIES = table(name("task_dependencies"));
    static final Table<?> EVENTS = table(name("events"));
    static final Table<?> WORKFLOW_SCHEDULES = table(name("workflow_schedules"));

    // workflow_definitions
    static final Field<UUID> DEF_ID = field(name("id"), SQLDataType.UUID);
    static final Field<String> DEF_NAME = field(name("name"), SQLDataType.VARCHAR);
    static final Field<Integer> DEF_VERSION = field(name("version"), SQLDataType.INTEGER);
    static final Field<JSONB> DEF_DAG = field(name("dag"), SQLDataType.JSONB);

    // workflow_instances
    static final Field<UUID> WI_ID = field(name("id"), SQLDataType.UUID);
    static final Field<UUID> WI_DEFINITION_ID = field(name("definition_id"), SQLDataType.UUID);
    static final Field<String> WI_STATUS = field(name("status"), SQLDataType.VARCHAR);
    static final Field<JSONB> WI_INPUT = field(name("input"), SQLDataType.JSONB);
    static final Field<JSONB> WI_OUTPUT = field(name("output"), SQLDataType.JSONB);
    static final Field<Instant> WI_STARTED_AT = field(name("started_at"), SQLDataType.INSTANT);
    static final Field<Instant> WI_FINISHED_AT = field(name("finished_at"), SQLDataType.INSTANT);
    static final Field<Instant> WI_UPDATED_AT = field(name("updated_at"), SQLDataType.INSTANT);
    static final Field<UUID> WI_SCHEDULE_ID = field(name("schedule_id"), SQLDataType.UUID);
    static final Field<Instant> WI_FIRED_FOR = field(name("fired_for"), SQLDataType.INSTANT);

    // task_instances
    static final Field<UUID> TI_ID = field(name("id"), SQLDataType.UUID);
    static final Field<UUID> TI_WORKFLOW_INSTANCE_ID =
            field(name("workflow_instance_id"), SQLDataType.UUID);
    static final Field<String> TI_TASK_KEY = field(name("task_key"), SQLDataType.VARCHAR);
    static final Field<String> TI_TYPE = field(name("type"), SQLDataType.VARCHAR);
    static final Field<String> TI_STATUS = field(name("status"), SQLDataType.VARCHAR);
    static final Field<Integer> TI_ATTEMPTS = field(name("attempts"), SQLDataType.INTEGER);
    static final Field<Integer> TI_MAX_ATTEMPTS = field(name("max_attempts"), SQLDataType.INTEGER);
    static final Field<Integer> TI_TIMEOUT_SECONDS =
            field(name("timeout_seconds"), SQLDataType.INTEGER);
    static final Field<JSONB> TI_RETRY_POLICY = field(name("retry_policy"), SQLDataType.JSONB);
    static final Field<String> TI_FAILURE_REASON =
            field(name("failure_reason"), SQLDataType.VARCHAR);
    static final Field<JSONB> TI_LAST_ERROR = field(name("last_error"), SQLDataType.JSONB);
    static final Field<JSONB> TI_INPUT = field(name("input"), SQLDataType.JSONB);
    static final Field<JSONB> TI_OUTPUT = field(name("output"), SQLDataType.JSONB);
    static final Field<Integer> TI_DELAY_SECONDS =
            field(name("delay_seconds"), SQLDataType.INTEGER);
    static final Field<Instant> TI_SCHEDULED_AT = field(name("scheduled_at"), SQLDataType.INSTANT);
    static final Field<Instant> TI_STARTED_AT = field(name("started_at"), SQLDataType.INSTANT);
    static final Field<Instant> TI_FINISHED_AT = field(name("finished_at"), SQLDataType.INSTANT);
    static final Field<Instant> TI_UPDATED_AT = field(name("updated_at"), SQLDataType.INSTANT);

    // task_leases
    static final Field<UUID> TL_TASK_ID = field(name("task_id"), SQLDataType.UUID);
    static final Field<String> TL_WORKER_ID = field(name("worker_id"), SQLDataType.VARCHAR);
    static final Field<Instant> TL_LEASE_EXPIRES_AT =
            field(name("lease_expires_at"), SQLDataType.INSTANT);

    // task_dependencies
    static final Field<UUID> TD_TASK_ID = field(name("task_id"), SQLDataType.UUID);
    static final Field<UUID> TD_DEPENDS_ON_TASK_ID =
            field(name("depends_on_task_id"), SQLDataType.UUID);

    // workflow_schedules
    static final Field<UUID> WS_ID = field(name("id"), SQLDataType.UUID);
    static final Field<String> WS_NAME = field(name("name"), SQLDataType.VARCHAR);
    static final Field<String> WS_WORKFLOW_NAME = field(name("workflow_name"), SQLDataType.VARCHAR);
    static final Field<Integer> WS_WORKFLOW_VERSION =
            field(name("workflow_version"), SQLDataType.INTEGER);
    static final Field<JSONB> WS_DAG = field(name("dag"), SQLDataType.JSONB);
    static final Field<JSONB> WS_INPUT = field(name("input"), SQLDataType.JSONB);
    static final Field<String> WS_CRON_EXPRESSION =
            field(name("cron_expression"), SQLDataType.VARCHAR);
    static final Field<String> WS_TIMEZONE = field(name("timezone"), SQLDataType.VARCHAR);
    static final Field<Instant> WS_NEXT_FIRE_AT = field(name("next_fire_at"), SQLDataType.INSTANT);
    static final Field<Instant> WS_LAST_FIRED_AT = field(name("last_fired_at"), SQLDataType.INSTANT);
    static final Field<Boolean> WS_PAUSED = field(name("paused"), SQLDataType.BOOLEAN);
    static final Field<Instant> WS_UPDATED_AT = field(name("updated_at"), SQLDataType.INSTANT);

    // events
    static final Field<UUID> EV_WORKFLOW_INSTANCE_ID =
            field(name("workflow_instance_id"), SQLDataType.UUID);
    static final Field<UUID> EV_TASK_INSTANCE_ID =
            field(name("task_instance_id"), SQLDataType.UUID);
    static final Field<String> EV_TYPE = field(name("type"), SQLDataType.VARCHAR);
    static final Field<JSONB> EV_DATA = field(name("data"), SQLDataType.JSONB);
}
