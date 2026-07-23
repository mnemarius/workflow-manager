package com.workflowmanager.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowmanager.engine.application.DispatchedTask;
import com.workflowmanager.engine.application.TaskCompletionService;
import com.workflowmanager.engine.application.TaskDispatchService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * Durable timers (M4, ADR 0011): a task with {@code delaySeconds} is not claimable until its
 * delay has elapsed, and the delay is measured from when the task became READY — at submit for a
 * root task, at promotion for a dependent one. Time is fast-forwarded by backdating
 * {@code scheduled_at} rather than sleeping, so the assertions stay deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class DelayedTaskIT {

    private static final String WORKER = "it-worker";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired TaskDispatchService dispatch;
    @Autowired TaskCompletionService completion;
    @Autowired DSLContext db;

    @Test
    void rootTaskWithDelay_isNotClaimableUntilDelayElapses() {
        Instant beforeSubmit = Instant.now();
        UUID instanceId =
                submit(
                        "{\"name\":\"delayed-root\",\"version\":1,\"dag\":{\"tasks\":["
                                + "{\"key\":\"wait\",\"type\":\"delayed-root-a\",\"delaySeconds\":86400}"
                                + "]}}");
        Instant afterSubmit = Instant.now();

        // READY immediately — readiness and claimability are different things (ADR 0011).
        assertThat(statusOf(instanceId, "wait")).isEqualTo("READY");
        assertThat(claim("delayed-root-a")).as("delay has not elapsed").isEmpty();

        // A root task is READY at submit, so its day runs from there.
        assertThat(scheduledAtOf(instanceId, "wait"))
                .isBetween(beforeSubmit.plusSeconds(86400), afterSubmit.plusSeconds(86400));

        expire(instanceId, "wait");
        assertThat(claim("delayed-root-a")).as("delay elapsed").isPresent();
    }

    @Test
    void dependentTaskWithDelay_countsDelayFromPromotionNotSubmit() {
        UUID instanceId =
                submit(
                        "{\"name\":\"delayed-dependent\",\"version\":1,\"dag\":{\"tasks\":["
                                + "{\"key\":\"first\",\"type\":\"delayed-dep-a\"},"
                                + "{\"key\":\"later\",\"type\":\"delayed-dep-b\","
                                + "\"dependsOn\":[\"first\"],\"delaySeconds\":3600}"
                                + "]}}");

        assertThat(statusOf(instanceId, "later")).isEqualTo("PENDING");

        // Everything before this instant is submit; everything after is promotion. Had the delay
        // been anchored at submit, scheduled_at would land strictly before this bound.
        Instant beforePromotion = Instant.now();
        runToSuccess("delayed-dep-a");

        // Promotion happened, so the task is READY — but the hour has not passed.
        assertThat(statusOf(instanceId, "later")).isEqualTo("READY");
        assertThat(claim("delayed-dep-b")).as("delay runs from promotion").isEmpty();

        assertThat(scheduledAtOf(instanceId, "later"))
                .as("delay is anchored to promotion, not submit")
                .isAfterOrEqualTo(beforePromotion.plusSeconds(3600));

        expire(instanceId, "later");
        assertThat(claim("delayed-dep-b")).isPresent();
    }

    @Test
    void taskWithoutDelay_isClaimableImmediately() {
        UUID instanceId =
                submit(
                        "{\"name\":\"undelayed\",\"version\":1,\"dag\":{\"tasks\":["
                                + "{\"key\":\"now\",\"type\":\"undelayed-a\"}"
                                + "]}}");
        assertThat(statusOf(instanceId, "now")).isEqualTo("READY");
        assertThat(claim("undelayed-a")).isPresent();
    }

    /** Fast-forward past a task's delay by backdating the moment it became due. */
    private void expire(UUID instanceId, String taskKey) {
        db.execute(
                "update task_instances set scheduled_at = now() - interval '1 second'"
                        + " where workflow_instance_id = ? and task_key = ?",
                instanceId,
                taskKey);
    }

    private Instant scheduledAtOf(UUID instanceId, String taskKey) {
        return db.fetchSingle(
                        "select scheduled_at from task_instances"
                                + " where workflow_instance_id = ? and task_key = ?",
                        instanceId,
                        taskKey)
                .get(0, Instant.class);
    }

    private void runToSuccess(String type) {
        DispatchedTask task = claim(type).orElseThrow(() -> new AssertionError("no claimable " + type));
        assertThat(completion.complete(task.taskId(), WORKER, true, "{\"ok\":true}", null)).isTrue();
    }

    private Optional<DispatchedTask> claim(String type) {
        return dispatch.tryClaim(WORKER, List.of(type));
    }

    private UUID submit(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var created = rest.postForEntity("/workflows", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(created.getBody().get("instanceId").asText());
    }

    private String statusOf(UUID instanceId, String taskKey) {
        var view = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode task : view.getBody().get("tasks")) {
            if (task.get("taskKey").asText().equals(taskKey)) {
                return task.get("status").asText();
            }
        }
        throw new AssertionError("no task with key " + taskKey);
    }
}
