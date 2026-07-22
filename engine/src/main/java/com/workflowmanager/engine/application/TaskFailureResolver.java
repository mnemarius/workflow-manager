package com.workflowmanager.engine.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.domain.FailureReason;
import com.workflowmanager.engine.orchestrator.CompletionPolicy;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Exhausted;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.InstanceOutcome;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Retry;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.TaskResolution;
import com.workflowmanager.engine.orchestrator.RetryPolicy;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The one failure path: worker-reported failures and reaper recoveries both resolve here, so
 * retry-vs-exhausted semantics cannot diverge. Caller owns the transaction and MDC.
 */
@Service
public class TaskFailureResolver {

    private static final Logger log = LoggerFactory.getLogger(TaskFailureResolver.class);

    private final WorkflowRepository repo;
    private final CompletionPolicy policy;
    private final ObjectMapper mapper;

    public TaskFailureResolver(WorkflowRepository repo, CompletionPolicy policy, ObjectMapper mapper) {
        this.repo = repo;
        this.policy = policy;
        this.mapper = mapper;
    }

    public void resolve(
            UUID instanceId,
            UUID taskId,
            int attempts,
            String retryPolicyJson,
            FailureReason reason,
            String errorMessage,
            Instant now,
            boolean withBackoff) {
        RetryPolicy retryPolicy = parsePolicy(retryPolicyJson);
        String lastError = lastErrorData(errorMessage, attempts);
        TaskResolution resolution =
                switch (policy.resolveFailure(attempts, retryPolicy, now)) {
                    case Retry retry -> {
                        Instant nextRunAt = withBackoff ? retry.nextRunAt() : now;
                        repo.scheduleRetry(taskId, nextRunAt, lastError, now);
                        repo.insertEvent(
                                instanceId,
                                taskId,
                                EventType.TASK_RETRY_SCHEDULED,
                                retryData(errorMessage, nextRunAt));
                        log.info(
                                "task retry scheduled attempts={} maxAttempts={} nextRunAt={}",
                                attempts,
                                retryPolicy.maxAttempts(),
                                nextRunAt);
                        yield TaskResolution.RETRY_SCHEDULED;
                    }
                    case Exhausted ignored -> {
                        repo.completeTaskFailure(taskId, reason, lastError, now);
                        repo.insertEvent(instanceId, taskId, EventType.TASK_FAILED, errorData(errorMessage));
                        repo.insertEvent(
                                instanceId,
                                taskId,
                                EventType.TASK_DEAD_LETTERED,
                                deadLetterData(reason, attempts));
                        log.warn("task dead-lettered reason={} attempts={}", reason, attempts);

                        // Downstream cascade: transitive dependents can never run now, so cancel the
                        // PENDING ones (siblings untouched). The subsequent progress() still FAILs the
                        // instance. Redrive of this task restores them (DeadLetterService).
                        List<UUID> cancelled = repo.cancelDependents(taskId, now);
                        for (UUID cancelledTaskId : cancelled) {
                            repo.insertEvent(
                                    instanceId, cancelledTaskId, EventType.TASK_CANCELLED, null);
                        }
                        if (!cancelled.isEmpty()) {
                            log.info("cancelled {} downstream dependent(s)", cancelled.size());
                        }
                        yield TaskResolution.DEAD_LETTERED;
                    }
                };

        if (policy.progress(repo.countOpenTasks(instanceId), resolution) == InstanceOutcome.FAIL) {
            repo.failInstance(instanceId, now);
            repo.insertEvent(instanceId, null, EventType.WORKFLOW_FAILED, null);
            log.info("workflow failed");
        }
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

    private String lastErrorData(String errorMessage, int attempts) {
        return mapper.createObjectNode().put("error", errorMessage).put("attempt", attempts).toString();
    }

    private String deadLetterData(FailureReason reason, int attempts) {
        return mapper.createObjectNode()
                .put("reason", reason.name())
                .put("attempts", attempts)
                .toString();
    }
}
