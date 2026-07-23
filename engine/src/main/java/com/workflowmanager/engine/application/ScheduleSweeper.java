package com.workflowmanager.engine.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.orchestrator.CronPolicy;
import com.workflowmanager.engine.persistence.ScheduleRepository;
import com.workflowmanager.engine.persistence.ScheduleRepository.ScheduleRow;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fires due cron schedules (ADR 0011). Claim, submit and advance happen in one transaction, so a
 * crash mid-sweep rolls the schedule back to due rather than losing or duplicating the run — and
 * SKIP LOCKED means a second engine sweeping at the same time takes different rows.
 */
@Service
public class ScheduleSweeper {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSweeper.class);
    private static final int BATCH_SIZE = 50;

    private final ScheduleRepository schedules;
    private final WorkflowRepository repo;
    private final WorkflowSubmissionService submission;
    private final CronPolicy cronPolicy;
    private final ObjectMapper mapper;

    public ScheduleSweeper(
            ScheduleRepository schedules,
            WorkflowRepository repo,
            WorkflowSubmissionService submission,
            CronPolicy cronPolicy,
            ObjectMapper mapper) {
        this.schedules = schedules;
        this.repo = repo;
        this.submission = submission;
        this.cronPolicy = cronPolicy;
        this.mapper = mapper;
    }

    /** @return how many schedules fired. */
    @Transactional
    public int sweep(Instant now) {
        List<ScheduleRow> due = schedules.claimDueSchedules(now, BATCH_SIZE);
        int fired = 0;
        for (ScheduleRow schedule : due) {
            if (fire(schedule, now)) {
                fired++;
            }
        }
        return fired;
    }

    private boolean fire(ScheduleRow schedule, Instant now) {
        CronPolicy.Fire fire;
        try {
            fire =
                    cronPolicy.resolveDue(
                            schedule.cronExpression(), schedule.timezone(), schedule.nextFireAt(), now);
        } catch (RuntimeException e) {
            // A schedule that can no longer be evaluated must not wedge the sweep for the others.
            log.error(
                    "schedule {} has an unusable cron expression, pausing it: {}",
                    schedule.name(),
                    e.getMessage());
            schedules.setPaused(schedule.id(), true, null, now);
            return false;
        }

        UUID instanceId =
                submission.submit(
                        schedule.workflowName(),
                        schedule.workflowVersion(),
                        parse(schedule.dagJson()),
                        parse(schedule.inputJson()),
                        schedule.id(),
                        fire.firedFor());

        repo.insertEvent(
                instanceId,
                null,
                EventType.WORKFLOW_FIRED_BY_SCHEDULE,
                mapper
                        .createObjectNode()
                        .put("scheduleName", schedule.name())
                        .put("firedFor", fire.firedFor().toString())
                        .put("skipped", fire.skipped())
                        .toString());

        schedules.advance(schedule.id(), fire.nextFireAt(), fire.firedFor(), now);

        MDC.put("workflow_id", instanceId.toString());
        try {
            if (fire.skipped() > 0) {
                log.warn(
                        "schedule {} fired late for {} — {} missed fires skipped, next {}",
                        schedule.name(),
                        fire.firedFor(),
                        fire.skipped(),
                        fire.nextFireAt());
            } else {
                log.info(
                        "schedule {} fired for {} next={}",
                        schedule.name(),
                        fire.firedFor(),
                        fire.nextFireAt());
            }
        } finally {
            MDC.remove("workflow_id");
        }
        return true;
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("stored schedule json is not readable", e);
        }
    }
}
