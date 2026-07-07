package com.workflowmanager.engine.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.workflowmanager.engine.domain.EventType;
import com.workflowmanager.engine.domain.TaskStatus;
import com.workflowmanager.engine.domain.WorkflowStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class WorkflowRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WorkflowRepository repo;
    @Autowired Clock clock;

    @Test
    void claimAndComplete_singleTask_reachesSucceeded() {
        Instant now = clock.instant();
        UUID defId = repo.upsertDefinition("demo", 1, "{\"tasks\":[{\"key\":\"step1\"}]}");
        UUID instanceId = repo.insertInstance(defId, "{\"msg\":\"hi\"}", now);
        repo.insertReadyTask(instanceId, "step1", "echo", "{\"msg\":\"hi\"}", 1, now, now);

        var claimed = repo.claimReadyTask(List.of("echo"), now);
        assertThat(claimed).isPresent();
        assertThat(claimed.get().taskKey()).isEqualTo("step1");
        assertThat(claimed.get().type()).isEqualTo("echo");
        assertThat(claimed.get().workflowInstanceId()).isEqualTo(instanceId);

        UUID taskId = claimed.get().taskId();
        repo.markTaskRunning(taskId, "worker-1", now, now.plusSeconds(30));
        repo.markInstanceRunning(instanceId, now);
        repo.insertEvent(instanceId, taskId, EventType.TASK_DISPATCHED, null);

        // Already RUNNING, so it is no longer claimable.
        assertThat(repo.claimReadyTask(List.of("echo"), now)).isEmpty();

        var running = repo.loadRunningTask(taskId);
        assertThat(running).isPresent();
        assertThat(running.get().status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(running.get().leaseWorkerId()).isEqualTo("worker-1");
        assertThat(running.get().leaseExpiresAt()).isEqualTo(now.plusSeconds(30));

        repo.completeTaskSuccess(taskId, "{\"result\":\"ok\"}", now);
        assertThat(repo.countOpenTasks(instanceId)).isZero();
        repo.succeedInstance(instanceId, "{\"result\":\"ok\"}", now);

        var instance = repo.findInstance(instanceId);
        assertThat(instance).isPresent();
        assertThat(instance.get().status()).isEqualTo(WorkflowStatus.SUCCEEDED);
        assertThat(instance.get().output()).contains("\"result\"");

        List<WorkflowRepository.TaskRow> tasks = repo.findTasks(instanceId);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(tasks.get(0).attempts()).isEqualTo(1);
    }

    @Test
    void claimReadyTask_capabilityMismatch_returnsEmpty() {
        Instant now = clock.instant();
        UUID defId = repo.upsertDefinition("mismatch", 1, "{\"tasks\":[{\"key\":\"a\"}]}");
        UUID instanceId = repo.insertInstance(defId, null, now);
        repo.insertReadyTask(instanceId, "a", "special", null, 1, now, now);

        assertThat(repo.claimReadyTask(List.of("echo"), now)).isEmpty();
        assertThat(repo.claimReadyTask(List.of("special"), now)).isPresent();
    }
}
