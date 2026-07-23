package com.workflowmanager.engine.persistence;

import static com.workflowmanager.engine.persistence.Schema.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** SQL for cron schedules (ADR 0011). Callers own the transaction boundary. */
@Repository
public class ScheduleRepository {

    public record ScheduleRow(
            UUID id,
            String name,
            String workflowName,
            int workflowVersion,
            String dagJson,
            String inputJson,
            String cronExpression,
            String timezone,
            Instant nextFireAt,
            Instant lastFiredAt,
            boolean paused) {}

    public record ScheduleRun(UUID workflowInstanceId, String status, Instant firedFor) {}

    private final DSLContext db;

    public ScheduleRepository(DSLContext db) {
        this.db = db;
    }

    public UUID insert(
            String name,
            String workflowName,
            int workflowVersion,
            String dagJson,
            String inputJson,
            String cronExpression,
            String timezone,
            Instant nextFireAt,
            Instant now) {
        return db.insertInto(WORKFLOW_SCHEDULES)
                .set(WS_NAME, name)
                .set(WS_WORKFLOW_NAME, workflowName)
                .set(WS_WORKFLOW_VERSION, workflowVersion)
                .set(WS_DAG, JSONB.valueOf(dagJson))
                .set(WS_INPUT, inputJson == null ? null : JSONB.valueOf(inputJson))
                .set(WS_CRON_EXPRESSION, cronExpression)
                .set(WS_TIMEZONE, timezone)
                .set(WS_NEXT_FIRE_AT, nextFireAt)
                .set(WS_UPDATED_AT, now)
                .returningResult(WS_ID)
                .fetchOne()
                .value1();
    }

    /**
     * Claims every due, unpaused schedule for this sweep. FOR UPDATE SKIP LOCKED (ADR 0001) so a
     * second engine sweeping concurrently takes disjoint rows rather than blocking — this is what
     * keeps the sweeper correct unchanged under M7's multi-engine setup.
     */
    public List<ScheduleRow> claimDueSchedules(Instant now, int limit) {
        return db.select(
                        WS_ID,
                        WS_NAME,
                        WS_WORKFLOW_NAME,
                        WS_WORKFLOW_VERSION,
                        WS_DAG,
                        WS_INPUT,
                        WS_CRON_EXPRESSION,
                        WS_TIMEZONE,
                        WS_NEXT_FIRE_AT,
                        WS_LAST_FIRED_AT,
                        WS_PAUSED)
                .from(WORKFLOW_SCHEDULES)
                .where(WS_PAUSED.isFalse())
                .and(WS_NEXT_FIRE_AT.le(now))
                .orderBy(WS_NEXT_FIRE_AT.asc())
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(ScheduleRepository::toRow);
    }

    /** The runs a schedule has started, newest fire first. */
    public List<ScheduleRun> findRuns(UUID scheduleId, int limit) {
        return db.select(WI_ID, WI_STATUS, WI_FIRED_FOR)
                .from(WORKFLOW_INSTANCES)
                .where(WI_SCHEDULE_ID.eq(scheduleId))
                .orderBy(WI_FIRED_FOR.desc())
                .limit(limit)
                .fetch(
                        r ->
                                new ScheduleRun(
                                        r.get(WI_ID), r.get(WI_STATUS), r.get(WI_FIRED_FOR)));
    }

    public void advance(UUID scheduleId, Instant nextFireAt, Instant lastFiredAt, Instant now) {
        db.update(WORKFLOW_SCHEDULES)
                .set(WS_NEXT_FIRE_AT, nextFireAt)
                .set(WS_LAST_FIRED_AT, lastFiredAt)
                .set(WS_UPDATED_AT, now)
                .where(WS_ID.eq(scheduleId))
                .execute();
    }

    public List<ScheduleRow> findAll() {
        return db.select(
                        WS_ID,
                        WS_NAME,
                        WS_WORKFLOW_NAME,
                        WS_WORKFLOW_VERSION,
                        WS_DAG,
                        WS_INPUT,
                        WS_CRON_EXPRESSION,
                        WS_TIMEZONE,
                        WS_NEXT_FIRE_AT,
                        WS_LAST_FIRED_AT,
                        WS_PAUSED)
                .from(WORKFLOW_SCHEDULES)
                .orderBy(WS_NAME.asc())
                .fetch(ScheduleRepository::toRow);
    }

    public Optional<ScheduleRow> find(UUID scheduleId) {
        return db.select(
                        WS_ID,
                        WS_NAME,
                        WS_WORKFLOW_NAME,
                        WS_WORKFLOW_VERSION,
                        WS_DAG,
                        WS_INPUT,
                        WS_CRON_EXPRESSION,
                        WS_TIMEZONE,
                        WS_NEXT_FIRE_AT,
                        WS_LAST_FIRED_AT,
                        WS_PAUSED)
                .from(WORKFLOW_SCHEDULES)
                .where(WS_ID.eq(scheduleId))
                .fetchOptional(ScheduleRepository::toRow);
    }

    /**
     * Resuming re-anchors the schedule so a schedule paused across many fire windows does not wake
     * up owing a backlog — the same reasoning as the missed-fire policy (ADR 0011).
     */
    public int setPaused(UUID scheduleId, boolean paused, Instant nextFireAt, Instant now) {
        var update =
                db.update(WORKFLOW_SCHEDULES).set(WS_PAUSED, paused).set(WS_UPDATED_AT, now);
        if (nextFireAt != null) {
            update = update.set(WS_NEXT_FIRE_AT, nextFireAt);
        }
        return update.where(WS_ID.eq(scheduleId)).execute();
    }

    public int delete(UUID scheduleId) {
        return db.deleteFrom(WORKFLOW_SCHEDULES).where(WS_ID.eq(scheduleId)).execute();
    }

    private static ScheduleRow toRow(Record r) {
        JSONB input = r.get(WS_INPUT);
        return new ScheduleRow(
                r.get(WS_ID),
                r.get(WS_NAME),
                r.get(WS_WORKFLOW_NAME),
                r.get(WS_WORKFLOW_VERSION),
                r.get(WS_DAG).data(),
                input == null ? null : input.data(),
                r.get(WS_CRON_EXPRESSION),
                r.get(WS_TIMEZONE),
                r.get(WS_NEXT_FIRE_AT),
                r.get(WS_LAST_FIRED_AT),
                r.get(WS_PAUSED));
    }
}
