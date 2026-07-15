package com.workflowmanager.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowmanager.engine.application.LeaseReaper;
import com.workflowmanager.engine.grpc.GrpcServer;
import com.workflowmanager.worker.TaskHandler;
import com.workflowmanager.worker.WorkerRuntime;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The M2 milestone demo, automated: kill a worker mid-task and watch another finish the job. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.lease-duration=2s")
@Testcontainers
@ActiveProfiles("test")
class WorkerFailoverIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired GrpcServer grpcServer;
    @Autowired LeaseReaper reaper;
    @Autowired Clock clock;
    @Autowired DSLContext db;

    @Test
    void workerDiesMidTask_leaseReaped_anotherWorkerCompletesTheWorkflow() throws Exception {
        String body =
                "{\"name\":\"failover\",\"version\":1,\"dag\":{\"tasks\":"
                        + "[{\"key\":\"step1\",\"type\":\"critical\"}]}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var created = rest.postForEntity("/workflows", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String instanceId = created.getBody().get("instanceId").asText();

        CountDownLatch fetchedByA = new CountDownLatch(1);
        TaskHandler stuck =
                input -> {
                    fetchedByA.countDown();
                    try {
                        new CountDownLatch(1).await(); // never finishes on its own
                        return "unreachable";
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", e);
                    }
                };
        WorkerRuntime workerA =
                new WorkerRuntime("localhost:" + grpcServer.port(), "worker-a", Map.of("critical", stuck));
        workerA.start();
        assertThat(fetchedByA.await(20, TimeUnit.SECONDS)).isTrue();
        workerA.close(); // crash: no CompleteTask, no more heartbeats

        // Fast-forward past the 2s lease by handing the reaper a future Instant — no sleeps.
        reaper.reapExpired(clock.instant().plus(Duration.ofSeconds(3)));

        TaskHandler healthy = input -> "{\"ok\":true}";
        try (WorkerRuntime workerB =
                new WorkerRuntime(
                        "localhost:" + grpcServer.port(), "worker-b", Map.of("critical", healthy))) {
            workerB.start();
            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(
                            () -> {
                                var view = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
                                assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
                                assertThat(view.getBody().get("status").asText()).isEqualTo("SUCCEEDED");
                            });
        }

        var finalView = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
        JsonNode task = finalView.getBody().get("tasks").get(0);
        assertThat(task.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(task.get("attempts").asInt()).isEqualTo(2);

        List<String> events =
                db
                        .fetch(
                                "select type from events where workflow_instance_id = ? order by id",
                                UUID.fromString(instanceId))
                        .getValues("type", String.class);
        assertThat(events).contains("TASK_LEASE_EXPIRED");
    }
}
