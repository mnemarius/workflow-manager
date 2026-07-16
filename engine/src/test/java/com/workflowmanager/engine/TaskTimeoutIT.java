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

/**
 * Per-attempt timeout end-to-end (ADR 0007): a hung-but-heartbeating handler is cut off at the
 * attempt deadline, the reaper classifies the reap as TIMED_OUT, and the retry succeeds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class TaskTimeoutIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired GrpcServer grpcServer;
    @Autowired LeaseReaper reaper;
    @Autowired Clock clock;
    @Autowired DSLContext db;

    @Test
    void handlerOverrunsTimeout_attemptAborted_reapedAsTimedOut_retrySucceeds() throws Exception {
        String body =
                "{\"name\":\"timeout\",\"version\":1,\"dag\":{\"tasks\":[{\"key\":\"step1\","
                        + "\"type\":\"budgeted\",\"timeoutSeconds\":2,\"retryPolicy\":"
                        + "{\"maxAttempts\":2,\"backoffStrategy\":\"fixed\",\"initialDelaySeconds\":0}}]}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var created = rest.postForEntity("/workflows", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String instanceId = created.getBody().get("instanceId").asText();

        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstAttemptAborted = new CountDownLatch(1);
        TaskHandler budgeted =
                input -> {
                    if (calls.incrementAndGet() == 1) {
                        try {
                            new CountDownLatch(1).await(); // would run ~forever without the deadline
                            return "unreachable";
                        } catch (InterruptedException e) {
                            firstAttemptAborted.countDown();
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("interrupted", e);
                        }
                    }
                    return "{\"ok\":true}";
                };

        try (WorkerRuntime worker =
                new WorkerRuntime(
                        "localhost:" + grpcServer.port(), "it-worker", Map.of("budgeted", budgeted))) {
            worker.start();

            // The SDK aborts at the deadline (or on a denied heartbeat) without reporting completion.
            assertThat(firstAttemptAborted.await(20, TimeUnit.SECONDS)).isTrue();

            // The lease was capped at the deadline, so once real time passes it the reap fires
            // (driven here directly; the background schedule may also beat us to it).
            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(
                            () -> {
                                reaper.reapExpired(clock.instant());
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
        assertThat(events).contains("TASK_TIMED_OUT", "TASK_RETRY_SCHEDULED");
        assertThat(events).doesNotContain("TASK_LEASE_EXPIRED");
    }
}
