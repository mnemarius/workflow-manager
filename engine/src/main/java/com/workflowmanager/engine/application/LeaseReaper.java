package com.workflowmanager.engine.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.domain.FailureReason;
import com.workflowmanager.engine.orchestrator.LeasePolicy;
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
 * Crashed-worker recovery (ADR 0008), also the enforcement point for per-attempt timeouts
 * (ADR 0007): reclaims RUNNING tasks whose lease has expired. The attempt was consumed at
 * claim, so expiry only resolves it. A timed-out attempt failed on its own budget, so it
 * retries with backoff; a crash says nothing bad about the task, so it retries without.
 */
@Service
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final WorkflowRepository repo;
    private final TaskFailureResolver failureResolver;
    private final LeasePolicy leasePolicy;
    private final ObjectMapper mapper;

    public LeaseReaper(
            WorkflowRepository repo,
            TaskFailureResolver failureResolver,
            LeasePolicy leasePolicy,
            ObjectMapper mapper) {
        this.repo = repo;
        this.failureResolver = failureResolver;
        this.leasePolicy = leasePolicy;
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
                boolean timedOut =
                        leasePolicy.pastDeadline(
                                leasePolicy.attemptDeadline(task.startedAt(), task.timeoutSeconds()), now);
                repo.releaseLease(task.taskId());
                repo.insertEvent(
                        task.workflowInstanceId(),
                        task.taskId(),
                        timedOut ? EventType.TASK_TIMED_OUT : EventType.TASK_LEASE_EXPIRED,
                        mapper.createObjectNode().put("workerId", task.workerId()).toString());
                log.warn(
                        "{} worker={} attempts={}",
                        timedOut ? "attempt timed out" : "lease expired",
                        task.workerId(),
                        task.attempts());
                failureResolver.resolve(
                        task.workflowInstanceId(),
                        task.taskId(),
                        task.attempts(),
                        task.retryPolicyJson(),
                        timedOut ? FailureReason.TIMED_OUT : FailureReason.LEASE_EXPIRED,
                        (timedOut ? "attempt timed out (worker " : "lease expired (worker ")
                                + task.workerId()
                                + ")",
                        now,
                        timedOut);
            } finally {
                MDC.remove("workflow_id");
                MDC.remove("task_id");
            }
        }
        return expired.size();
    }
}
