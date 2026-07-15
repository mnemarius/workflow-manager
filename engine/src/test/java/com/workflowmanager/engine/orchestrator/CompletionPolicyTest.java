package com.workflowmanager.engine.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.workflowmanager.engine.domain.TaskStatus;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Accepted;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Exhausted;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.InstanceOutcome;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Rejected;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Retry;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.TaskResolution;
import com.workflowmanager.engine.orchestrator.RetryPolicy.BackoffStrategy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CompletionPolicyTest {

    private final CompletionPolicy policy = new CompletionPolicy();
    private final Instant now = Instant.parse("2026-07-07T12:00:00Z");
    private final Instant future = now.plusSeconds(30);

    @Test
    void decide_whenOwnerReportsLiveLease_accepts() {
        var decision = policy.decide(TaskStatus.RUNNING, "worker-1", future, "worker-1", now, true);
        assertThat(decision).isInstanceOf(Accepted.class);
        assertThat(((Accepted) decision).success()).isTrue();
    }

    @Test
    void decide_whenLeaseHeldByAnotherWorker_rejects() {
        var decision = policy.decide(TaskStatus.RUNNING, "worker-1", future, "worker-2", now, true);
        assertThat(decision).isInstanceOf(Rejected.class);
    }

    @Test
    void decide_whenLeaseExpired_rejects() {
        var expired = now.minusSeconds(1);
        var decision = policy.decide(TaskStatus.RUNNING, "worker-1", expired, "worker-1", now, true);
        assertThat(decision).isInstanceOf(Rejected.class);
    }

    @Test
    void decide_whenTaskNotRunning_rejects() {
        var decision = policy.decide(TaskStatus.SUCCEEDED, "worker-1", future, "worker-1", now, true);
        assertThat(decision).isInstanceOf(Rejected.class);
    }

    @Test
    void resolveFailure_whenAttemptsBelowMax_schedulesRetryAfterBackoff() {
        var fixed = new RetryPolicy(3, BackoffStrategy.FIXED, 5, 300);
        var resolution = policy.resolveFailure(1, fixed, now);
        assertThat(resolution).isEqualTo(new Retry(now.plusSeconds(5)));
    }

    @Test
    void resolveFailure_exponentialBackoff_growsWithFailedAttempts() {
        var exponential = new RetryPolicy(4, BackoffStrategy.EXPONENTIAL, 5, 300);
        assertThat(policy.resolveFailure(1, exponential, now)).isEqualTo(new Retry(now.plusSeconds(5)));
        assertThat(policy.resolveFailure(2, exponential, now)).isEqualTo(new Retry(now.plusSeconds(10)));
        assertThat(policy.resolveFailure(3, exponential, now)).isEqualTo(new Retry(now.plusSeconds(20)));
    }

    @Test
    void resolveFailure_whenAttemptsEqualMax_exhausts() {
        var fixed = new RetryPolicy(3, BackoffStrategy.FIXED, 5, 300);
        assertThat(policy.resolveFailure(3, fixed, now)).isEqualTo(new Exhausted());
    }

    @Test
    void progress_whenLastTaskSucceeds_succeedsRun() {
        assertThat(policy.progress(0, TaskResolution.SUCCEEDED)).isEqualTo(InstanceOutcome.SUCCEED);
    }

    @Test
    void progress_whenTaskDeadLettered_failsRun() {
        assertThat(policy.progress(0, TaskResolution.DEAD_LETTERED)).isEqualTo(InstanceOutcome.FAIL);
    }

    @Test
    void progress_whenTaskRetryScheduled_continues() {
        assertThat(policy.progress(1, TaskResolution.RETRY_SCHEDULED)).isEqualTo(InstanceOutcome.CONTINUE);
    }

    @Test
    void progress_whenTasksRemain_continues() {
        assertThat(policy.progress(2, TaskResolution.SUCCEEDED)).isEqualTo(InstanceOutcome.CONTINUE);
    }
}
