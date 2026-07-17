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
import java.util.concurrent.atomic.AtomicBoolean;
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

/**
 * The DLQ end-to-end (ADR 0009): an exhausted task is listed with failure metadata, redrive
 * re-executes it to success, and redriving a non-FAILED task conflicts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class DeadLetterIT {

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

    private JsonNode deadLetterFor(String instanceId) {
        var list = rest.getForEntity("/dead-letters?limit=50", JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode entry : list.getBody()) {
            if (entry.get("workflowInstanceId").asText().equals(instanceId)) {
                return entry;
            }
        }
        return null;
    }

    @Test
    void exhaustedTask_listedWithMetadata_redriveReExecutesToSuccess() {
        String instanceId =
                submit(
                        "dlq-redrive",
                        "{\"key\":\"step1\",\"type\":\"dlq-flaky\",\"retryPolicy\":"
                                + "{\"maxAttempts\":2,\"backoffStrategy\":\"fixed\",\"initialDelaySeconds\":0}}");

        AtomicBoolean healed = new AtomicBoolean(false);
        TaskHandler flaky =
                input -> {
                    if (!healed.get()) {
                        throw new IllegalStateException("permanent boom");
                    }
                    return "{\"ok\":true}";
                };
        try (WorkerRuntime worker =
                new WorkerRuntime(
                        "localhost:" + grpcServer.port(), "it-worker", Map.of("dlq-flaky", flaky))) {
            worker.start();
            awaitWorkflowStatus(instanceId, "FAILED");

            JsonNode entry = deadLetterFor(instanceId);
            assertThat(entry).isNotNull();
            assertThat(entry.get("taskKey").asText()).isEqualTo("step1");
            assertThat(entry.get("type").asText()).isEqualTo("dlq-flaky");
            assertThat(entry.get("attempts").asInt()).isEqualTo(2);
            assertThat(entry.get("failureReason").asText()).isEqualTo("HANDLER_FAILED");
            assertThat(entry.get("lastError").get("error").asText()).contains("permanent boom");
            assertThat(entry.get("lastError").get("attempt").asInt()).isEqualTo(2);
            assertThat(entry.get("finishedAt").asText()).isNotEmpty();
            assertThat(eventTypes(instanceId)).containsOnlyOnce("TASK_DEAD_LETTERED");

            healed.set(true);
            var redriven =
                    rest.postForEntity(
                            "/dead-letters/" + entry.get("taskId").asText() + "/retry", null, Void.class);
            assertThat(redriven.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            awaitWorkflowStatus(instanceId, "SUCCEEDED");
        }

        var view = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
        JsonNode task = view.getBody().get("tasks").get(0);
        assertThat(task.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(task.get("attempts").asInt()).isEqualTo(1);
        assertThat(eventTypes(instanceId)).contains("TASK_REDRIVEN", "WORKFLOW_SUCCEEDED");
        assertThat(deadLetterFor(instanceId)).isNull();
    }

    @Test
    void redrive_nonFailedTask_returns409() {
        String instanceId = submit("dlq-conflict", "{\"key\":\"step1\",\"type\":\"dlq-ok\"}");

        TaskHandler ok = input -> "{\"ok\":true}";
        try (WorkerRuntime worker =
                new WorkerRuntime("localhost:" + grpcServer.port(), "it-worker", Map.of("dlq-ok", ok))) {
            worker.start();
            awaitWorkflowStatus(instanceId, "SUCCEEDED");
        }

        UUID taskId =
                db.fetchOne(
                                "select id from task_instances where workflow_instance_id = ?",
                                UUID.fromString(instanceId))
                        .get("id", UUID.class);
        var response =
                rest.postForEntity("/dead-letters/" + taskId + "/retry", null, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
