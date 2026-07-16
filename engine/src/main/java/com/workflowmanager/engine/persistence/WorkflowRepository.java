package com.workflowmanager.engine.persistence;

import static com.workflowmanager.engine.persistence.Schema.*;

import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.domain.TaskStatus;
import com.workflowmanager.engine.domain.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/**
 * All engine SQL, written explicitly with the jOOQ DSL (no ORM). Callers own the transaction
 * boundary (see the application services); the queue claim relies on the caller holding the
 * row lock until commit.
 */
@Repository
public class WorkflowRepository {

    public record ClaimedTask(
            UUID taskId,
            String taskKey,
            String type,
            String input,
            int attempts,
            UUID workflowInstanceId,
            Integer timeoutSeconds) {}

    public record RunningTask(
            UUID taskId,
            UUID workflowInstanceId,
            TaskStatus status,
            String leaseWorkerId,
            Instant leaseExpiresAt,
            int attempts,
            int maxAttempts,
            String retryPolicyJson,
            Instant startedAt,
            Integer timeoutSeconds) {}

    public record ExpiredLeaseTask(
            UUID taskId,
            UUID workflowInstanceId,
            String workerId,
            int attempts,
            int maxAttempts,
            String retryPolicyJson,
            Instant startedAt,
            Integer timeoutSeconds) {}

    public record InstanceRow(
            UUID id, WorkflowStatus status, String output, Instant startedAt, Instant finishedAt) {}

    public record TaskRow(String taskKey, String type, TaskStatus status, int attempts, String output) {}

    private final DSLContext db;

    public WorkflowRepository(DSLContext db) {
        this.db = db;
    }

    public UUID upsertDefinition(String name, int version, String dagJson) {
        return db.insertInto(WORKFLOW_DEFINITIONS)
                .set(DEF_NAME, name)
                .set(DEF_VERSION, version)
                .set(DEF_DAG, JSONB.valueOf(dagJson))
                .onConflict(DEF_NAME, DEF_VERSION)
                .doUpdate()
                .set(DEF_DAG, JSONB.valueOf(dagJson))
                .returningResult(DEF_ID)
                .fetchOne()
                .value1();
    }

    public UUID insertInstance(UUID definitionId, String inputJson, Instant now) {
        return db.insertInto(WORKFLOW_INSTANCES)
                .set(WI_DEFINITION_ID, definitionId)
                .set(WI_STATUS, WorkflowStatus.PENDING.name())
                .set(WI_INPUT, jsonbOrNull(inputJson))
                .set(WI_UPDATED_AT, now)
                .returningResult(WI_ID)
                .fetchOne()
                .value1();
    }

    public void insertReadyTask(
            UUID instanceId,
            String taskKey,
            String type,
            String inputJson,
            int maxAttempts,
            String retryPolicyJson,
            Integer timeoutSeconds,
            Instant scheduledAt,
            Instant now) {
        db.insertInto(TASK_INSTANCES)
                .set(TI_WORKFLOW_INSTANCE_ID, instanceId)
                .set(TI_TASK_KEY, taskKey)
                .set(TI_TYPE, type)
                .set(TI_STATUS, TaskStatus.READY.name())
                .set(TI_MAX_ATTEMPTS, maxAttempts)
                .set(TI_TIMEOUT_SECONDS, timeoutSeconds)
                .set(TI_RETRY_POLICY, jsonbOrNull(retryPolicyJson))
                .set(TI_INPUT, jsonbOrNull(inputJson))
                .set(TI_SCHEDULED_AT, scheduledAt)
                .set(TI_UPDATED_AT, now)
                .execute();
    }

    /**
     * Claims one due task the worker can run: READY, or RETRY_SCHEDULED whose backoff has elapsed
     * (ADR 0006 — no promotion sweeper). FOR UPDATE SKIP LOCKED (ADR 0001).
     */
    public Optional<ClaimedTask> claimReadyTask(List<String> capabilities, Instant now) {
        var condition =
                TI_STATUS.in(TaskStatus.READY.name(), TaskStatus.RETRY_SCHEDULED.name())
                        .and(TI_SCHEDULED_AT.le(now));
        if (!capabilities.isEmpty()) {
            condition = condition.and(TI_TYPE.in(capabilities));
        }
        return db.select(
                        TI_ID,
                        TI_TASK_KEY,
                        TI_TYPE,
                        TI_INPUT,
                        TI_ATTEMPTS,
                        TI_WORKFLOW_INSTANCE_ID,
                        TI_TIMEOUT_SECONDS)
                .from(TASK_INSTANCES)
                .where(condition)
                .orderBy(TI_SCHEDULED_AT.asc())
                .limit(1)
                .forUpdate()
                .skipLocked()
                .fetchOptional(
                        r ->
                                new ClaimedTask(
                                        r.get(TI_ID),
                                        r.get(TI_TASK_KEY),
                                        r.get(TI_TYPE),
                                        dataOrNull(r.get(TI_INPUT)),
                                        r.get(TI_ATTEMPTS),
                                        r.get(TI_WORKFLOW_INSTANCE_ID),
                                        r.get(TI_TIMEOUT_SECONDS)));
    }

    public void markTaskRunning(UUID taskId, String workerId, Instant now, Instant leaseExpiresAt) {
        db.update(TASK_INSTANCES)
                .set(TI_STATUS, TaskStatus.RUNNING.name())
                .set(TI_ATTEMPTS, TI_ATTEMPTS.plus(1))
                .set(TI_STARTED_AT, now)
                .set(TI_UPDATED_AT, now)
                .where(TI_ID.eq(taskId))
                .execute();
        db.insertInto(TASK_LEASES)
                .set(TL_TASK_ID, taskId)
                .set(TL_WORKER_ID, workerId)
                .set(TL_LEASE_EXPIRES_AT, leaseExpiresAt)
                .execute();
    }

    public void markInstanceRunning(UUID instanceId, Instant now) {
        db.update(WORKFLOW_INSTANCES)
                .set(WI_STATUS, WorkflowStatus.RUNNING.name())
                .set(WI_STARTED_AT, now)
                .set(WI_UPDATED_AT, now)
                .where(WI_ID.eq(instanceId))
                .and(WI_STATUS.eq(WorkflowStatus.PENDING.name()))
                .execute();
    }

    public Optional<RunningTask> loadRunningTask(UUID taskId) {
        return db.select(
                        TI_WORKFLOW_INSTANCE_ID,
                        TI_STATUS,
                        TL_WORKER_ID,
                        TL_LEASE_EXPIRES_AT,
                        TI_ATTEMPTS,
                        TI_MAX_ATTEMPTS,
                        TI_RETRY_POLICY,
                        TI_STARTED_AT,
                        TI_TIMEOUT_SECONDS)
                .from(TASK_INSTANCES)
                .leftJoin(TASK_LEASES)
                .on(TL_TASK_ID.eq(TI_ID))
                .where(TI_ID.eq(taskId))
                .fetchOptional(
                        r ->
                                new RunningTask(
                                        taskId,
                                        r.get(TI_WORKFLOW_INSTANCE_ID),
                                        TaskStatus.valueOf(r.get(TI_STATUS)),
                                        r.get(TL_WORKER_ID),
                                        r.get(TL_LEASE_EXPIRES_AT),
                                        r.get(TI_ATTEMPTS),
                                        r.get(TI_MAX_ATTEMPTS),
                                        dataOrNull(r.get(TI_RETRY_POLICY)),
                                        r.get(TI_STARTED_AT),
                                        r.get(TI_TIMEOUT_SECONDS)));
    }

    /**
     * Locks RUNNING tasks whose lease has expired (ADR 0008). SKIP LOCKED keeps a concurrent
     * reaper (multi-engine, M7) or an in-flight completion from double-processing a task.
     */
    public List<ExpiredLeaseTask> claimExpiredRunningTasks(Instant now) {
        return db.select(
                        TI_ID,
                        TI_WORKFLOW_INSTANCE_ID,
                        TL_WORKER_ID,
                        TI_ATTEMPTS,
                        TI_MAX_ATTEMPTS,
                        TI_RETRY_POLICY,
                        TI_STARTED_AT,
                        TI_TIMEOUT_SECONDS)
                .from(TASK_INSTANCES)
                .join(TASK_LEASES)
                .on(TL_TASK_ID.eq(TI_ID))
                .where(TI_STATUS.eq(TaskStatus.RUNNING.name()))
                .and(TL_LEASE_EXPIRES_AT.le(now))
                .forUpdate()
                .of(TASK_INSTANCES)
                .skipLocked()
                .fetch(
                        r ->
                                new ExpiredLeaseTask(
                                        r.get(TI_ID),
                                        r.get(TI_WORKFLOW_INSTANCE_ID),
                                        r.get(TL_WORKER_ID),
                                        r.get(TI_ATTEMPTS),
                                        r.get(TI_MAX_ATTEMPTS),
                                        dataOrNull(r.get(TI_RETRY_POLICY)),
                                        r.get(TI_STARTED_AT),
                                        r.get(TI_TIMEOUT_SECONDS)));
    }

    public void renewLease(UUID taskId, Instant newExpiry) {
        db.update(TASK_LEASES)
                .set(TL_LEASE_EXPIRES_AT, newExpiry)
                .where(TL_TASK_ID.eq(taskId))
                .execute();
    }

    public void completeTaskSuccess(UUID taskId, String outputJson, Instant now) {
        db.update(TASK_INSTANCES)
                .set(TI_STATUS, TaskStatus.SUCCEEDED.name())
                .set(TI_OUTPUT, jsonbOrNull(outputJson))
                .set(TI_FINISHED_AT, now)
                .set(TI_UPDATED_AT, now)
                .where(TI_ID.eq(taskId))
                .execute();
        releaseLease(taskId);
    }

    public void scheduleRetry(UUID taskId, Instant nextRunAt, Instant now) {
        db.update(TASK_INSTANCES)
                .set(TI_STATUS, TaskStatus.RETRY_SCHEDULED.name())
                .set(TI_SCHEDULED_AT, nextRunAt)
                .set(TI_UPDATED_AT, now)
                .where(TI_ID.eq(taskId))
                .execute();
        releaseLease(taskId);
    }

    public void completeTaskFailure(UUID taskId, Instant now) {
        db.update(TASK_INSTANCES)
                .set(TI_STATUS, TaskStatus.FAILED.name())
                .set(TI_FINISHED_AT, now)
                .set(TI_UPDATED_AT, now)
                .where(TI_ID.eq(taskId))
                .execute();
        releaseLease(taskId);
    }

    public long countOpenTasks(UUID instanceId) {
        return db.selectCount()
                .from(TASK_INSTANCES)
                .where(TI_WORKFLOW_INSTANCE_ID.eq(instanceId))
                .and(
                        TI_STATUS.notIn(
                                TaskStatus.SUCCEEDED.name(),
                                TaskStatus.FAILED.name(),
                                TaskStatus.CANCELLED.name()))
                .fetchOne(0, long.class);
    }

    public void succeedInstance(UUID instanceId, String outputJson, Instant now) {
        db.update(WORKFLOW_INSTANCES)
                .set(WI_STATUS, WorkflowStatus.SUCCEEDED.name())
                .set(WI_OUTPUT, jsonbOrNull(outputJson))
                .set(WI_FINISHED_AT, now)
                .set(WI_UPDATED_AT, now)
                .where(WI_ID.eq(instanceId))
                .execute();
    }

    public void failInstance(UUID instanceId, Instant now) {
        db.update(WORKFLOW_INSTANCES)
                .set(WI_STATUS, WorkflowStatus.FAILED.name())
                .set(WI_FINISHED_AT, now)
                .set(WI_UPDATED_AT, now)
                .where(WI_ID.eq(instanceId))
                .execute();
    }

    public void insertEvent(UUID instanceId, UUID taskId, EventType type, String dataJson) {
        db.insertInto(EVENTS)
                .set(EV_WORKFLOW_INSTANCE_ID, instanceId)
                .set(EV_TASK_INSTANCE_ID, taskId)
                .set(EV_TYPE, type.name())
                .set(EV_DATA, jsonbOrNull(dataJson))
                .execute();
    }

    public Optional<InstanceRow> findInstance(UUID instanceId) {
        return db.select(WI_STATUS, WI_OUTPUT, WI_STARTED_AT, WI_FINISHED_AT)
                .from(WORKFLOW_INSTANCES)
                .where(WI_ID.eq(instanceId))
                .fetchOptional(
                        r ->
                                new InstanceRow(
                                        instanceId,
                                        WorkflowStatus.valueOf(r.get(WI_STATUS)),
                                        dataOrNull(r.get(WI_OUTPUT)),
                                        r.get(WI_STARTED_AT),
                                        r.get(WI_FINISHED_AT)));
    }

    public List<TaskRow> findTasks(UUID instanceId) {
        return db.select(TI_TASK_KEY, TI_TYPE, TI_STATUS, TI_ATTEMPTS, TI_OUTPUT)
                .from(TASK_INSTANCES)
                .where(TI_WORKFLOW_INSTANCE_ID.eq(instanceId))
                .orderBy(TI_TASK_KEY.asc())
                .fetch(
                        r ->
                                new TaskRow(
                                        r.get(TI_TASK_KEY),
                                        r.get(TI_TYPE),
                                        TaskStatus.valueOf(r.get(TI_STATUS)),
                                        r.get(TI_ATTEMPTS),
                                        dataOrNull(r.get(TI_OUTPUT))));
    }

    public void releaseLease(UUID taskId) {
        db.deleteFrom(TASK_LEASES).where(TL_TASK_ID.eq(taskId)).execute();
    }

    private static JSONB jsonbOrNull(String json) {
        return json == null ? null : JSONB.valueOf(json);
    }

    private static String dataOrNull(JSONB value) {
        return value == null ? null : value.data();
    }
}
