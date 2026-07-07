package com.workflowmanager.engine.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.workflowmanager.engine.domain.TaskStatus;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Accepted;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.InstanceOutcome;
import com.workflowmanager.engine.orchestrator.CompletionPolicy.Rejected;
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
    void progress_whenLastTaskSucceeds_succeedsRun() {
        assertThat(policy.progress(0, false)).isEqualTo(InstanceOutcome.SUCCEED);
    }

    @Test
    void progress_whenTaskFails_failsRun() {
        assertThat(policy.progress(0, true)).isEqualTo(InstanceOutcome.FAIL);
    }

    @Test
    void progress_whenTasksRemain_continues() {
        assertThat(policy.progress(2, false)).isEqualTo(InstanceOutcome.CONTINUE);
    }
}
