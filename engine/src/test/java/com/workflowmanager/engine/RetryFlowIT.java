package com.workflowmanager.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowmanager.engine.grpc.GrpcServer;
import com.workflowmanager.worker.TaskHandler;
import com.workflowmanager.worker.WorkerRuntime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

/** The M2 retry loop end-to-end: fail, RETRY_SCHEDULED, redeliver when due (ADR 0006). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RetryFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired GrpcServer grpcServer;
    @Autowired DSLContext db;

    private String submit(String name, String taskJson) {
        String body =
                "{\"name\":\"" + name + "\",\"version\":1,\"dag\":{\"tasks\":[" + taskJson + "]}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var created = rest.postForEntity("/workflows", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("instanceId").asText();
    }

    private void awaitWorkflowStatus(String instanceId, String expected) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> {
                            var view = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
                            assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
                            assertThat(view.getBody().get("status").asText()).isEqualTo(expected);
                        });
    }

    private List<String> eventTypes(String instanceId) {
        return db
                .fetch(
                        "select type from events where workflow_instance_id = ? order by id",
                        UUID.fromString(instanceId))
                .getValues("type", String.class);
    }

    @Test
    void failingTwiceThenSucceeding_retriesUntilWorkflowSucceeds() {
        String instanceId =
                submit(
                        "retry-recovers",
                        "{\"key\":\"step1\",\"type\":\"flaky\",\"retryPolicy\":"
                                + "{\"maxAttempts\":3,\"backoffStrategy\":\"fixed\",\"initialDelaySeconds\":0}}");

        AtomicInteger calls = new AtomicInteger();
        TaskHandler flaky =
                input -> {
                    if (calls.incrementAndGet() <= 2) {
                        throw new IllegalStateException("transient boom");
                    }
                    return "{\"ok\":true}";
                };
        try (WorkerRuntime worker =
                new WorkerRuntime("localhost:" + grpcServer.port(), "it-worker", Map.of("flaky", flaky))) {
            worker.start();
            awaitWorkflowStatus(instanceId, "SUCCEEDED");
        }

        var view = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
        JsonNode task = view.getBody().get("tasks").get(0);
        assertThat(task.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(task.get("attempts").asInt()).isEqualTo(3);

        assertThat(eventTypes(instanceId))
                .filteredOn("TASK_RETRY_SCHEDULED"::equals)
                .hasSize(2);
    }

    @Test
    void alwaysFailing_exhaustsRetries_failsTaskAndWorkflow() {
        String instanceId =
                submit(
                        "retry-exhausts",
                        "{\"key\":\"step1\",\"type\":\"doomed\",\"retryPolicy\":"
                                + "{\"maxAttempts\":2,\"backoffStrategy\":\"fixed\",\"initialDelaySeconds\":0}}");

        TaskHandler doomed =
                input -> {
                    throw new IllegalStateException("permanent boom");
                };
        try (WorkerRuntime worker =
                new WorkerRuntime("localhost:" + grpcServer.port(), "it-worker", Map.of("doomed", doomed))) {
            worker.start();
            awaitWorkflowStatus(instanceId, "FAILED");
        }

        var view = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
        JsonNode task = view.getBody().get("tasks").get(0);
        assertThat(task.get("status").asText()).isEqualTo("FAILED");
        assertThat(task.get("attempts").asInt()).isEqualTo(2);

        List<String> events = eventTypes(instanceId);
        assertThat(events).filteredOn("TASK_RETRY_SCHEDULED"::equals).hasSize(1);
        assertThat(events).contains("TASK_FAILED", "WORKFLOW_FAILED");
    }
}
