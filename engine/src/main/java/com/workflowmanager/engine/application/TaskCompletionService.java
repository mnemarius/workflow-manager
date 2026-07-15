package com.workflowmanager.engine.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.orchestrator.CompletionPolicy;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Exhausted;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Rejected;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Retry;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.TaskResolution;
import com.workflowmanager.engine.orchestrator.RetryPolicy;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import com.workflowmanager.engine.persistence.WorkflowRepository.RunningTask;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies a worker's completion report if it still owns a live lease (ADR 0004). */
@Service
public class TaskCompletionService {

    private static final Logger log = LoggerFactory.getLogger(TaskCompletionService.class);

    private final WorkflowRepository repo;
    private final CompletionPolicy policy;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TaskCompletionService(
            WorkflowRepository repo, CompletionPolicy policy, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.policy = policy;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** @return true if the report was applied; false if rejected (unknown task or stale lease). */
    @Transactional
    public boolean complete(
            UUID taskId, String workerId, boolean success, String outputJson, String errorMessage) {
        Instant now = clock.instant();
        RunningTask running = repo.loadRunningTask(taskId).orElse(null);
        if (running == null) {
            return false;
        }

        var decision =
                policy.decide(
                        running.status(),
                        running.leaseWorkerId(),
                        running.leaseExpiresAt(),
                        workerId,
                        now,
                        success);

        UUID instanceId = running.workflowInstanceId();
        MDC.put("workflow_id", instanceId.toString());
        MDC.put("task_id", taskId.toString());
        try {
            if (decision instanceof Rejected rejected) {
                log.warn("completion rejected worker={} reason={}", workerId, rejected.reason());
                return false;
            }

            TaskResolution resolution =
                    success
                            ? applySuccess(instanceId, taskId, outputJson, now)
                            : applyFailure(instanceId, running, errorMessage, now);

            long open = repo.countOpenTasks(instanceId);
            switch (policy.progress(open, resolution)) {
                case SUCCEED -> {
                    repo.succeedInstance(instanceId, outputJson, now);
                    repo.insertEvent(instanceId, null, EventType.WORKFLOW_SUCCEEDED, null);
                    log.info("workflow succeeded");
                }
                case FAIL -> {
                    repo.failInstance(instanceId, now);
                    repo.insertEvent(instanceId, null, EventType.WORKFLOW_FAILED, null);
                    log.info("workflow failed");
                }
                case CONTINUE -> {}
            }
            return true;
        } finally {
            MDC.remove("workflow_id");
            MDC.remove("task_id");
        }
    }

    private TaskResolution applySuccess(UUID instanceId, UUID taskId, String outputJson, Instant now) {
        repo.completeTaskSuccess(taskId, outputJson, now);
        repo.insertEvent(instanceId, taskId, EventType.TASK_SUCCEEDED, null);
        return TaskResolution.SUCCEEDED;
    }

    private TaskResolution applyFailure(
            UUID instanceId, RunningTask running, String errorMessage, Instant now) {
        RetryPolicy retryPolicy = parsePolicy(running.retryPolicyJson());
        return switch (policy.resolveFailure(running.attempts(), retryPolicy, now)) {
            case Retry retry -> {
                repo.scheduleRetry(running.taskId(), retry.nextRunAt(), now);
                repo.insertEvent(
                        instanceId,
                        running.taskId(),
                        EventType.TASK_RETRY_SCHEDULED,
                        retryData(errorMessage, retry.nextRunAt()));
                log.info(
                        "task retry scheduled attempts={} maxAttempts={} nextRunAt={}",
                        running.attempts(),
                        retryPolicy.maxAttempts(),
                        retry.nextRunAt());
                yield TaskResolution.RETRY_SCHEDULED;
            }
            case Exhausted ignored -> {
                repo.completeTaskFailure(running.taskId(), now);
                repo.insertEvent(
                        instanceId, running.taskId(), EventType.TASK_FAILED, errorData(errorMessage));
                yield TaskResolution.DEAD_LETTERED;
            }
        };
    }

    private RetryPolicy parsePolicy(String json) {
        if (json == null) {
            return RetryPolicy.DEFAULT;
        }
        try {
            return RetryPolicy.from(mapper.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("malformed retry_policy JSON", e);
        }
    }

    private String retryData(String errorMessage, Instant nextRunAt) {
        return mapper.createObjectNode()
                .put("error", errorMessage)
                .put("nextRunAt", nextRunAt.toString())
                .toString();
    }

    private String errorData(String errorMessage) {
        return errorMessage == null ? null : mapper.createObjectNode().put("error", errorMessage).toString();
    }
}
