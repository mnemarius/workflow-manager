package com.workflowmanager.engine.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import com.workflowmanager.engine.persistence.WorkflowRepository.ExpiredLeaseTask;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crashed-worker recovery (ADR 0008): reclaims RUNNING tasks whose lease has expired. The
 * attempt was consumed at claim, so expiry only resolves it — retry without backoff (a dead
 * worker says nothing bad about the task) or dead-letter when the budget is spent.
 */
@Service
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final WorkflowRepository repo;
    private final TaskFailureResolver failureResolver;
    private final ObjectMapper mapper;

    public LeaseReaper(WorkflowRepository repo, TaskFailureResolver failureResolver, ObjectMapper mapper) {
        this.repo = repo;
        this.failureResolver = failureResolver;
        this.mapper = mapper;
    }

    /** @return how many expired leases were reaped. */
    @Transactional
    public int reapExpired(Instant now) {
        List<ExpiredLeaseTask> expired = repo.claimExpiredRunningTasks(now);
        for (ExpiredLeaseTask task : expired) {
            MDC.put("workflow_id", task.workflowInstanceId().toString());
            MDC.put("task_id", task.taskId().toString());
            try {
                repo.releaseLease(task.taskId());
                repo.insertEvent(
                        task.workflowInstanceId(),
                        task.taskId(),
                        EventType.TASK_LEASE_EXPIRED,
                        mapper.createObjectNode().put("workerId", task.workerId()).toString());
                log.warn("lease expired worker={} attempts={}", task.workerId(), task.attempts());
                failureResolver.resolve(
                        task.workflowInstanceId(),
                        task.taskId(),
                        task.attempts(),
                        task.retryPolicyJson(),
                        "lease expired (worker " + task.workerId() + ")",
                        now,
                        false);
            } finally {
                MDC.remove("workflow_id");
                MDC.remove("task_id");
            }
        }
        return expired.size();
    }
}
