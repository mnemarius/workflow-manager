package com.workflowmanager.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowmanager.engine.grpc.GrpcServer;
import com.workflowmanager.worker.TaskHandler;
import com.workflowmanager.worker.WorkerRuntime;
import java.time.Duration;
import java.util.Map;
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

/** The M1 milestone demo, automated: submit a single-task workflow and watch it run end-to-end. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class EndToEndWorkflowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired GrpcServer grpcServer;

    @Test
    void submittedSingleTask_runsToSucceeded() {
        String body =
                "{\"name\":\"e2e\",\"version\":1,\"dag\":{\"tasks\":"
                        + "[{\"key\":\"step1\",\"type\":\"echo\",\"input\":{\"msg\":\"hi\"}}]}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var created = rest.postForEntity("/workflows", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String instanceId = created.getBody().get("instanceId").asText();

        TaskHandler echo = input -> "{\"echoed\":" + (input == null || input.isBlank() ? "null" : input) + "}";
        try (WorkerRuntime worker =
                new WorkerRuntime("localhost:" + grpcServer.port(), "it-worker", Map.of("echo", echo))) {
            worker.start();

            await().atMost(Duration.ofSeconds(20))
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
        assertThat(task.get("attempts").asInt()).isEqualTo(1);
        assertThat(task.get("output").get("echoed").get("msg").asText()).isEqualTo("hi");
    }
}
