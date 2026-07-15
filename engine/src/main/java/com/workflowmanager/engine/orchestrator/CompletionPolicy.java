package com.workflowmanager.engine.orchestrator;

import com.workflowmanager.engine.domain.TaskStatus;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The state-machine decisions for a task completion report. Pure logic (ADR 0004 at-least-once):
 * a report is only applied when the reporting worker still owns a live lease, so a stale worker
 * whose lease was reassigned cannot clobber the task.
 */
@Component
public class CompletionPolicy {

    public sealed interface Decision permits Accepted, Rejected {}

    public record Accepted(boolean success) implements Decision {}

    public record Rejected(String reason) implements Decision {}

    public sealed interface FailureResolution permits Retry, Exhausted {}

    public record Retry(Instant nextRunAt) implements FailureResolution {}

    public record Exhausted() implements FailureResolution {}

    public enum TaskResolution {
        SUCCEEDED,
        RETRY_SCHEDULED,
        DEAD_LETTERED
    }

    public enum InstanceOutcome {
        SUCCEED,
        FAIL,
        CONTINUE
    }

    public Decision decide(
            TaskStatus status,
            String leaseWorkerId,
            Instant leaseExpiresAt,
            String reportingWorkerId,
            Instant now,
            boolean success) {
        if (status != TaskStatus.RUNNING) {
            return new Rejected("task is " + status + ", not RUNNING");
        }
        if (leaseWorkerId == null || leaseExpiresAt == null) {
            return new Rejected("no active lease");
        }
        if (!leaseWorkerId.equals(reportingWorkerId)) {
            return new Rejected("lease held by another worker");
        }
        if (!leaseExpiresAt.isAfter(now)) {
            return new Rejected("lease expired");
        }
        return new Accepted(success);
    }

    public FailureResolution resolveFailure(int attempts, RetryPolicy policy, Instant now) {
        if (attempts < policy.maxAttempts()) {
            return new Retry(now.plus(policy.backoffFor(attempts)));
        }
        return new Exhausted();
    }

    /** Given the workflow's remaining open tasks and how this task resolved, decide the run. */
    public InstanceOutcome progress(long openTasks, TaskResolution resolution) {
        return switch (resolution) {
            case DEAD_LETTERED -> InstanceOutcome.FAIL;
            case RETRY_SCHEDULED -> InstanceOutcome.CONTINUE;
            case SUCCEEDED -> openTasks == 0 ? InstanceOutcome.SUCCEED : InstanceOutcome.CONTINUE;
        };
    }
}
