package com.workflowmanager.engine.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.orchestrator.CompletionPolicy;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Rejected;
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

            if (success) {
                repo.completeTaskSuccess(taskId, outputJson, now);
                repo.insertEvent(instanceId, taskId, EventType.TASK_SUCCEEDED, null);
            } else {
                repo.completeTaskFailure(taskId, now);
                repo.insertEvent(instanceId, taskId, EventType.TASK_FAILED, errorData(errorMessage));
            }

            long open = repo.countOpenTasks(instanceId);
            switch (policy.progress(open, !success)) {
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

    private String errorData(String errorMessage) {
        return errorMessage == null ? null : mapper.createObjectNode().put("error", errorMessage).toString();
    }
}
