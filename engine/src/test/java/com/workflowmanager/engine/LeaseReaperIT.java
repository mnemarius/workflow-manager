package com.workflowmanager.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.workflowmanager.engine.application.LeaseReaper;
import com.workflowmanager.engine.domain.TaskStatus;
import com.workflowmanager.engine.domain.WorkflowStatus;
import com.workflowmanager.engine.persistence.WorkflowRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Reaper semantics (ADR 0008), fast-forwarded by passing a future Instant — no sleeps. */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class LeaseReaperIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Duration LEASE = Duration.ofSeconds(30);

    @Autowired LeaseReaper reaper;
    @Autowired WorkflowRepository repo;
    @Autowired Clock clock;
    @Autowired DSLContext db;

    private record Running(UUID instanceId, UUID taskId) {}

    private Running setupRunningTask(String type, String retryPolicyJson, Instant now) {
        UUID defId = repo.upsertDefinition(type, 1, "{\"tasks\":[{\"key\":\"step1\"}]}");
        UUID instanceId = repo.insertInstance(defId, null, now);
        repo.insertReadyTask(instanceId, "step1", type, null, 3, retryPolicyJson, now, now);
        var claimed = repo.claimReadyTask(List.of(type), now).orElseThrow();
        repo.markTaskRunning(claimed.taskId(), "crashed-worker", now, now.plus(LEASE));
        repo.markInstanceRunning(instanceId, now);
        return new Running(instanceId, claimed.taskId());
    }

    private List<String> eventTypes(UUID instanceId) {
        return db
                .fetch("select type from events where workflow_instance_id = ? order by id", instanceId)
                .getValues("type", String.class);
    }

    @Test
    void reapExpired_budgetLeft_retriesImmediatelyAndKeepsWorkflowRunning() {
        Instant now = clock.instant();
        Running running = setupRunningTask("reap-retry", null, now);
        Instant afterExpiry = now.plus(LEASE).plusSeconds(1);

        reaper.reapExpired(afterExpiry);

        var tasks = repo.findTasks(running.instanceId());
        assertThat(tasks.get(0).status()).isEqualTo(TaskStatus.RETRY_SCHEDULED);
        assertThat(tasks.get(0).attempts()).isEqualTo(1);
        assertThat(repo.loadRunningTask(running.taskId()).orElseThrow().leaseWorkerId()).isNull();
        assertThat(repo.findInstance(running.instanceId()).orElseThrow().status())
                .isEqualTo(WorkflowStatus.RUNNING);
        assertThat(eventTypes(running.instanceId()))
                .contains("TASK_LEASE_EXPIRED", "TASK_RETRY_SCHEDULED");

        // scheduled_at = reap time, no backoff: claimable right away by the next worker
        var reclaimed = repo.claimReadyTask(List.of("reap-retry"), afterExpiry);
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.orElseThrow().taskId()).isEqualTo(running.taskId());
    }

    @Test
    void reapExpired_budgetExhausted_failsTaskAndWorkflow() {
        Instant now = clock.instant();
        Running running = setupRunningTask("reap-exhaust", "{\"maxAttempts\":1}", now);
        Instant afterExpiry = now.plus(LEASE).plusSeconds(1);

        reaper.reapExpired(afterExpiry);

        assertThat(repo.findTasks(running.instanceId()).get(0).status()).isEqualTo(TaskStatus.FAILED);
        assertThat(repo.findInstance(running.instanceId()).orElseThrow().status())
                .isEqualTo(WorkflowStatus.FAILED);
        assertThat(eventTypes(running.instanceId()))
                .contains("TASK_LEASE_EXPIRED", "TASK_FAILED", "WORKFLOW_FAILED");
    }

    @Test
    void reapExpired_liveLease_leavesTaskUntouched() {
        Instant now = clock.instant();
        Running running = setupRunningTask("reap-live", null, now);

        reaper.reapExpired(now);

        var task = repo.loadRunningTask(running.taskId()).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.leaseWorkerId()).isEqualTo("crashed-worker");
        assertThat(eventTypes(running.instanceId())).doesNotContain("TASK_LEASE_EXPIRED");
    }
}
