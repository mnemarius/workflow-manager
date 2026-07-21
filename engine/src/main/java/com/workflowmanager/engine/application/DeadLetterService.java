package com.workflowmanager.engine.application;

import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.domain.TaskStatus;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import com.workflowmanager.engine.persistence.WorkflowRepository.RedriveCandidate;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redrive for dead-lettered tasks (ADR 0009): a FAILED task is reset to READY with a fresh
 * attempt budget and its workflow reopened, so the normal claim path re-executes it.
 */
@Service
public class DeadLetterService {

    public sealed interface RedriveResult permits Redriven, NotFound, NotDeadLettered {}

    public record Redriven(UUID workflowInstanceId) implements RedriveResult {}

    public record NotFound() implements RedriveResult {}

    public record NotDeadLettered(TaskStatus status) implements RedriveResult {}

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final WorkflowRepository repo;
    private final Clock clock;

    public DeadLetterService(WorkflowRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional
    public RedriveResult redrive(UUID taskId) {
        Instant now = clock.instant();
        RedriveCandidate candidate = repo.lockTaskForRedrive(taskId).orElse(null);
        if (candidate == null) {
            return new NotFound();
        }
        if (candidate.status() != TaskStatus.FAILED) {
            return new NotDeadLettered(candidate.status());
        }

        UUID instanceId = candidate.workflowInstanceId();
        MDC.put("workflow_id", instanceId.toString());
        MDC.put("task_id", taskId.toString());
        try {
            repo.redriveTask(taskId, now);
            repo.reopenFailedInstance(instanceId, now);
            repo.insertEvent(instanceId, taskId, EventType.TASK_REDRIVEN, null);
            log.info("task redriven");
            return new Redriven(instanceId);
        } finally {
            MDC.remove("workflow_id");
            MDC.remove("task_id");
        }
    }
}
