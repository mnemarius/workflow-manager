package com.workflowmanager.engine.persistence;

import static com.workflowmanager.engine.persistence.Schema.*;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;

import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.domain.FailureReason;
import com.workflowmanager.engine.domain.TaskStatus;
import com.workflowmanager.engine.domain.WorkflowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.CommonTableExpression;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.impl.SQLDataType;
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

    /** A SUCCEEDED sink task's key and its output JSON (nullable), for workflow-output aggregation. */
    public record LeafOutput(String taskKey, String outputJson) {}

    public record DeadLetterRow(
            UUID taskId,
            UUID workflowInstanceId,
            String taskKey,
            String type,
            int attempts,
            String failureReason,
            String lastError,
            Instant finishedAt) {}

    public record RedriveCandidate(UUID workflowInstanceId, TaskStatus status) {}

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

    public UUID insertReadyTask(
            UUID instanceId,
            String taskKey,
            String type,
            String inputJson,
            int maxAttempts,
            String retryPolicyJson,
            Integer timeoutSeconds,
            Instant scheduledAt,
            Instant now) {
        return insertTask(
                TaskStatus.READY,
                instanceId,
                taskKey,
                type,
                inputJson,
                maxAttempts,
                retryPolicyJson,
                timeoutSeconds,
                null,
                scheduledAt,
                now);
    }

    /**
     * Inserts a task in the given initial status (READY when it has no dependencies, PENDING when it
     * waits on others) and returns the generated id, which the caller needs to wire dependency edges.
     */
    public UUID insertTask(
            TaskStatus status,
            UUID instanceId,
            String taskKey,
            String type,
            String inputJson,
            int maxAttempts,
            String retryPolicyJson,
            Integer timeoutSeconds,
            Integer delaySeconds,
            Instant scheduledAt,
            Instant now) {
        return db.insertInto(TASK_INSTANCES)
                .set(TI_WORKFLOW_INSTANCE_ID, instanceId)
                .set(TI_TASK_KEY, taskKey)
                .set(TI_TYPE, type)
                .set(TI_STATUS, status.name())
                .set(TI_MAX_ATTEMPTS, maxAttempts)
                .set(TI_TIMEOUT_SECONDS, timeoutSeconds)
                .set(TI_DELAY_SECONDS, delaySeconds)
                .set(TI_RETRY_POLICY, jsonbOrNull(retryPolicyJson))
                .set(TI_INPUT, jsonbOrNull(inputJson))
                .set(TI_SCHEDULED_AT, scheduledAt)
                .set(TI_UPDATED_AT, now)
                .returningResult(TI_ID)
                .fetchOne()
                .value1();
    }

    public void insertDependency(UUID taskId, UUID dependsOnTaskId) {
        db.insertInto(TASK_DEPENDENCIES)
                .set(TD_TASK_ID, taskId)
                .set(TD_DEPENDS_ON_TASK_ID, dependsOnTaskId)
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

    /**
     * Event-driven fan-in (ADR 0006 — no promotion sweeper): promote every PENDING task in this
     * workflow whose dependencies are now ALL SUCCEEDED to READY, and return the ids promoted so the
     * caller can emit events. A task is promoted iff no row in {@code task_dependencies} for it points
     * at an upstream task that is not yet SUCCEEDED (NOT EXISTS correlated subquery).
     *
     * <p>Concurrency: two sibling completions of a shared child's parents can run in overlapping
     * transactions (independent gRPC CompleteTask calls). Under READ COMMITTED a bare conditional
     * UPDATE would let both miss the child (neither sees the other's not-yet-committed SUCCEEDED),
     * orphaning it. To close that fan-in race we first take a blocking {@code FOR UPDATE} lock on this
     * workflow's PENDING rows (ordered by id to avoid deadlock, NOT skip-locked): the second completer
     * blocks until the first commits, then re-evaluates on a fresh snapshot and promotes the child.
     * The lock is scoped to one workflow instance, so completions in other workflows are unaffected.
     */
    public List<UUID> promoteReadyDependents(UUID workflowInstanceId, Instant now) {
        // Serialize sibling completions within this workflow on its PENDING set (see javadoc).
        db.select(TI_ID)
                .from(TASK_INSTANCES)
                .where(TI_WORKFLOW_INSTANCE_ID.eq(workflowInstanceId))
                .and(TI_STATUS.eq(TaskStatus.PENDING.name()))
                .orderBy(TI_ID.asc())
                .forUpdate()
                .fetch();

        Field<UUID> pendingTaskId = field(name("task_instances", "id"), SQLDataType.UUID);
        Field<UUID> upstreamId = field(name("upstream", "id"), SQLDataType.UUID);
        Field<String> upstreamStatus = field(name("upstream", "status"), SQLDataType.VARCHAR);

        var hasUnfinishedDependency =
                selectOne()
                        .from(TASK_DEPENDENCIES)
                        .join(TASK_INSTANCES.as("upstream"))
                        .on(upstreamId.eq(TD_DEPENDS_ON_TASK_ID))
                        .where(TD_TASK_ID.eq(pendingTaskId))
                        .and(upstreamStatus.ne(TaskStatus.SUCCEEDED.name()));

        // A delayed task is promoted like any other, but is not claimable until its delay
        // has elapsed from this moment — the delay is measured from readiness (ADR 0011).
        Field<Instant> readyAt =
                field(
                        "{0} + make_interval(secs => coalesce({1}, 0))",
                        SQLDataType.INSTANT, val(now), TI_DELAY_SECONDS);

        return db.update(TASK_INSTANCES)
                .set(TI_STATUS, TaskStatus.READY.name())
                .set(TI_SCHEDULED_AT, readyAt)
                .set(TI_UPDATED_AT, now)
                .where(TI_WORKFLOW_INSTANCE_ID.eq(workflowInstanceId))
                .and(TI_STATUS.eq(TaskStatus.PENDING.name()))
                .andNotExists(hasUnfinishedDependency)
                .returningResult(TI_ID)
                .fetch(TI_ID);
    }

    public void scheduleRetry(UUID taskId, Instant nextRunAt, String lastErrorJson, Instant now) {
        db.update(TASK_INSTANCES)
                .set(TI_STATUS, TaskStatus.RETRY_SCHEDULED.name())
                .set(TI_SCHEDULED_AT, nextRunAt)
                .set(TI_LAST_ERROR, jsonbOrNull(lastErrorJson))
                .set(TI_UPDATED_AT, now)
                .where(TI_ID.eq(taskId))
                .execute();
        releaseLease(taskId);
    }

    public void completeTaskFailure(
            UUID taskId, FailureReason reason, String lastErrorJson, Instant now) {
        db.update(TASK_INSTANCES)
                .set(TI_STATUS, TaskStatus.FAILED.name())
                .set(TI_FAILURE_REASON, reason.name())
                .set(TI_LAST_ERROR, jsonbOrNull(lastErrorJson))
                .set(TI_FINISHED_AT, now)
                .set(TI_UPDATED_AT, now)
                .where(TI_ID.eq(taskId))
                .execute();
        releaseLease(taskId);
    }

    /**
     * Transitive dependents of {@code taskId}: every task reachable by following dependency edges in
     * reverse. An edge {@code (task_id, depends_on_task_id)} means {@code task_id} depends on
     * {@code depends_on_task_id}; so the direct dependents of a task are the rows whose
     * {@code depends_on_task_id} equals it, and we recurse on those. Expressed as a recursive CTE
     * ({@code WITH RECURSIVE dependents(id) AS (seed UNION ALL step)}). Does NOT include
     * {@code taskId} itself. Edges live within a single workflow instance, so no instance filter is
     * needed.
     */
    private CommonTableExpression<Record1<UUID>> transitiveDependents(UUID taskId) {
        Field<UUID> dependentId = field(name("dependents", "id"), SQLDataType.UUID);
        return name("dependents")
                .fields("id")
                .as(
                        select(TD_TASK_ID)
                                .from(TASK_DEPENDENCIES)
                                .where(TD_DEPENDS_ON_TASK_ID.eq(taskId))
                                .unionAll(
                                        select(TD_TASK_ID)
                                                .from(TASK_DEPENDENCIES)
                                                .join(table(name("dependents")))
                                                .on(TD_DEPENDS_ON_TASK_ID.eq(dependentId))));
    }

    /**
     * On dead-letter: cancel every transitive dependent that is still PENDING (a dependent of a
     * just-failed task can only be PENDING — it was never dispatchable). CANCELLED is terminal, so we
     * stamp finished_at like {@link #completeTaskFailure}. Siblings are untouched. Returns the ids
     * actually cancelled so the caller can emit an event per task.
     */
    public List<UUID> cancelDependents(UUID taskId, Instant now) {
        CommonTableExpression<Record1<UUID>> dependents = transitiveDependents(taskId);
        return db.withRecursive(dependents)
                .update(TASK_INSTANCES)
                .set(TI_STATUS, TaskStatus.CANCELLED.name())
                .set(TI_FINISHED_AT, now)
                .set(TI_UPDATED_AT, now)
                .where(TI_ID.in(select(field(name("dependents", "id"), SQLDataType.UUID)).from(dependents)))
                .and(TI_STATUS.eq(TaskStatus.PENDING.name()))
                .returningResult(TI_ID)
                .fetch(TI_ID);
    }

    /**
     * On redrive of a previously dead-lettered task: restore its transitive dependents that were
     * CANCELLED back to PENDING (not READY — they re-block until their own dependencies succeed
     * again), clearing finished_at. Returns the restored ids.
     */
    public List<UUID> restoreCancelledDependents(UUID taskId, Instant now) {
        CommonTableExpression<Record1<UUID>> dependents = transitiveDependents(taskId);
        return db.withRecursive(dependents)
                .update(TASK_INSTANCES)
                .set(TI_STATUS, TaskStatus.PENDING.name())
                .set(TI_FINISHED_AT, (Instant) null)
                .set(TI_UPDATED_AT, now)
                .where(TI_ID.in(select(field(name("dependents", "id"), SQLDataType.UUID)).from(dependents)))
                .and(TI_STATUS.eq(TaskStatus.CANCELLED.name()))
                .returningResult(TI_ID)
                .fetch(TI_ID);
    }

    public List<DeadLetterRow> findDeadLetters(int limit) {
        return db.select(
                        TI_ID,
                        TI_WORKFLOW_INSTANCE_ID,
                        TI_TASK_KEY,
                        TI_TYPE,
                        TI_ATTEMPTS,
                        TI_FAILURE_REASON,
                        TI_LAST_ERROR,
                        TI_FINISHED_AT)
                .from(TASK_INSTANCES)
                .where(TI_STATUS.eq(TaskStatus.FAILED.name()))
                .orderBy(TI_FINISHED_AT.desc())
                .limit(limit)
                .fetch(
                        r ->
                                new DeadLetterRow(
                                        r.get(TI_ID),
                                        r.get(TI_WORKFLOW_INSTANCE_ID),
                                        r.get(TI_TASK_KEY),
                                        r.get(TI_TYPE),
                                        r.get(TI_ATTEMPTS),
                                        r.get(TI_FAILURE_REASON),
                                        dataOrNull(r.get(TI_LAST_ERROR)),
                                        r.get(TI_FINISHED_AT)));
    }

    public Optional<RedriveCandidate> lockTaskForRedrive(UUID taskId) {
        return db.select(TI_WORKFLOW_INSTANCE_ID, TI_STATUS)
                .from(TASK_INSTANCES)
                .where(TI_ID.eq(taskId))
                .forUpdate()
                .fetchOptional(
                        r ->
                                new RedriveCandidate(
                                        r.get(TI_WORKFLOW_INSTANCE_ID),
                                        TaskStatus.valueOf(r.get(TI_STATUS))));
    }

    public void redriveTask(UUID taskId, Instant now) {
        db.update(TASK_INSTANCES)
                .set(TI_STATUS, TaskStatus.READY.name())
                .set(TI_ATTEMPTS, 0)
                .set(TI_SCHEDULED_AT, now)
                .set(TI_FAILURE_REASON, (String) null)
                .set(TI_LAST_ERROR, (JSONB) null)
                .set(TI_FINISHED_AT, (Instant) null)
                .set(TI_UPDATED_AT, now)
                .where(TI_ID.eq(taskId))
                .execute();
    }

    public void reopenFailedInstance(UUID instanceId, Instant now) {
        db.update(WORKFLOW_INSTANCES)
                .set(WI_STATUS, WorkflowStatus.RUNNING.name())
                .set(WI_FINISHED_AT, (Instant) null)
                .set(WI_UPDATED_AT, now)
                .where(WI_ID.eq(instanceId))
                .and(WI_STATUS.eq(WorkflowStatus.FAILED.name()))
                .execute();
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

    /**
     * The SUCCEEDED sink tasks of an instance with their output JSON. A sink is a task whose id never
     * appears as a {@code depends_on_task_id} — nothing depends on it, so it is a terminal result of
     * the DAG (NOT EXISTS anti-join). Ordered by task_key for deterministic aggregate output. Sinks
     * should all be SUCCEEDED when the workflow succeeds; the status filter guards against anything
     * else slipping in.
     */
    public List<LeafOutput> findSinkOutputs(UUID instanceId) {
        var isDependedOn =
                selectOne().from(TASK_DEPENDENCIES).where(TD_DEPENDS_ON_TASK_ID.eq(TI_ID));
        return db.select(TI_TASK_KEY, TI_OUTPUT)
                .from(TASK_INSTANCES)
                .where(TI_WORKFLOW_INSTANCE_ID.eq(instanceId))
                .and(TI_STATUS.eq(TaskStatus.SUCCEEDED.name()))
                .andNotExists(isDependedOn)
                .orderBy(TI_TASK_KEY.asc())
                .fetch(r -> new LeafOutput(r.get(TI_TASK_KEY), dataOrNull(r.get(TI_OUTPUT))));
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
