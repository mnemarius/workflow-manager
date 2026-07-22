package com.workflowmanager.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.workflowmanager.engine.application.DeadLetterService;
import com.workflowmanager.engine.application.DeadLetterService.Redriven;
import com.workflowmanager.engine.application.DispatchedTask;
import com.workflowmanager.engine.application.TaskCompletionService;
import com.workflowmanager.engine.application.TaskDispatchService;
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
 * M3 Step 4: downstream failure cascade. When a task dead-letters, its transitive dependents (which
 * can never run) are CANCELLED; redriving the dead-lettered task restores them to PENDING so the DAG
 * can proceed again. Drives tasks directly (like {@link DagWorkflowIT}) so the cascade is observed
 * deterministically. A task with {@code maxAttempts=1} dead-letters on its first failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class DagFailureIT {

    private static final String WORKER = "it-worker";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired TaskDispatchService dispatch;
    @Autowired TaskCompletionService completion;
    @Autowired DeadLetterService deadLetters;
    @Autowired DSLContext db;

    @Test
    void diamond_deadLetteredTaskCancelsDependentOnly_redriveRestoresIt() {
        // A -> {B, C} -> D. B dead-letters on first failure (maxAttempts=1). D depends on B, so D is a
        // transitive dependent of B and must be CANCELLED; C is a sibling and must be left alone.
        String dag =
                "{\"tasks\":["
                        + "{\"key\":\"A\",\"type\":\"a\"},"
                        + "{\"key\":\"B\",\"type\":\"b\",\"dependsOn\":[\"A\"],\"retryPolicy\":"
                        + "{\"maxAttempts\":1,\"backoffStrategy\":\"fixed\",\"initialDelaySeconds\":0}},"
                        + "{\"key\":\"C\",\"type\":\"c\",\"dependsOn\":[\"A\"]},"
                        + "{\"key\":\"D\",\"type\":\"d\",\"dependsOn\":[\"B\",\"C\"]}"
                        + "]}";
        UUID instanceId = submit("{\"name\":\"diamond-fail\",\"version\":1,\"dag\":" + dag + "}");

        // Run A: promotes B and C to READY; D still PENDING.
        runToSuccess("a");
        assertThat(statusOf(instanceId, "B")).isEqualTo("READY");
        assertThat(statusOf(instanceId, "C")).isEqualTo("READY");
        assertThat(statusOf(instanceId, "D")).isEqualTo("PENDING");

        // Fail B once -> dead-lettered. Cascade cancels D (dependent); C untouched; workflow FAILED.
        runToFailure("b");
        assertThat(statusOf(instanceId, "B")).isEqualTo("FAILED");
        assertThat(statusOf(instanceId, "D")).isEqualTo("CANCELLED");
        assertThat(statusOf(instanceId, "C")).as("sibling C must not be cancelled").isEqualTo("READY");
        assertThat(instanceStatus(instanceId)).isEqualTo("FAILED");

        // Exactly one TASK_CANCELLED (D only), for the diamond.
        assertThat(eventTypes(instanceId).stream().filter("TASK_CANCELLED"::equals).count())
                .as("only D cancelled")
                .isEqualTo(1);

        // Redrive B: B -> READY, D restored to PENDING (not READY — still waits on B and C), run RUNNING.
        redrive(instanceId, "B");
        assertThat(statusOf(instanceId, "B")).isEqualTo("READY");
        assertThat(statusOf(instanceId, "D")).isEqualTo("PENDING");
        assertThat(statusOf(instanceId, "C")).isEqualTo("READY");
        assertThat(instanceStatus(instanceId)).isEqualTo("RUNNING");

        // Drive the DAG to completion. D must not be claimable until both B and C succeed.
        runToSuccess("b");
        assertThat(statusOf(instanceId, "D")).isEqualTo("PENDING");
        runToSuccess("c");
        assertThat(statusOf(instanceId, "D")).isEqualTo("READY");
        runToSuccess("d");

        assertThat(statusOf(instanceId, "D")).isEqualTo("SUCCEEDED");
        assertThat(instanceStatus(instanceId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void linearChain_deadLetterCancelsAllTransitiveDependents_redriveRestoresThem() {
        // A -> B -> C -> D. B dead-letters; C and D are both transitive dependents and get CANCELLED.
        String dag =
                "{\"tasks\":["
                        + "{\"key\":\"A\",\"type\":\"la\"},"
                        + "{\"key\":\"B\",\"type\":\"lb\",\"dependsOn\":[\"A\"],\"retryPolicy\":"
                        + "{\"maxAttempts\":1,\"backoffStrategy\":\"fixed\",\"initialDelaySeconds\":0}},"
                        + "{\"key\":\"C\",\"type\":\"lc\",\"dependsOn\":[\"B\"]},"
                        + "{\"key\":\"D\",\"type\":\"ld\",\"dependsOn\":[\"C\"]}"
                        + "]}";
        UUID instanceId = submit("{\"name\":\"linear-fail\",\"version\":1,\"dag\":" + dag + "}");

        runToSuccess("la");
        assertThat(statusOf(instanceId, "B")).isEqualTo("READY");
        assertThat(statusOf(instanceId, "C")).isEqualTo("PENDING");
        assertThat(statusOf(instanceId, "D")).isEqualTo("PENDING");

        runToFailure("lb");
        assertThat(statusOf(instanceId, "B")).isEqualTo("FAILED");
        assertThat(statusOf(instanceId, "C")).isEqualTo("CANCELLED");
        assertThat(statusOf(instanceId, "D")).isEqualTo("CANCELLED");
        assertThat(instanceStatus(instanceId)).isEqualTo("FAILED");
        assertThat(eventTypes(instanceId).stream().filter("TASK_CANCELLED"::equals).count())
                .as("C and D both cancelled")
                .isEqualTo(2);

        // Redrive B restores the whole tail to PENDING.
        redrive(instanceId, "B");
        assertThat(statusOf(instanceId, "B")).isEqualTo("READY");
        assertThat(statusOf(instanceId, "C")).isEqualTo("PENDING");
        assertThat(statusOf(instanceId, "D")).isEqualTo("PENDING");
        assertThat(instanceStatus(instanceId)).isEqualTo("RUNNING");

        // Chain now runs clean to the end.
        runToSuccess("lb");
        runToSuccess("lc");
        runToSuccess("ld");
        assertThat(statusOf(instanceId, "D")).isEqualTo("SUCCEEDED");
        assertThat(instanceStatus(instanceId)).isEqualTo("SUCCEEDED");
    }

    private void runToSuccess(String type) {
        DispatchedTask task = claim(type).orElseThrow(() -> new AssertionError("no claimable " + type));
        assertThat(completion.complete(task.taskId(), WORKER, true, "{\"ok\":true}", null)).isTrue();
    }

    private void runToFailure(String type) {
        DispatchedTask task = claim(type).orElseThrow(() -> new AssertionError("no claimable " + type));
        assertThat(completion.complete(task.taskId(), WORKER, false, null, "boom")).isTrue();
    }

    private void redrive(UUID instanceId, String taskKey) {
        assertThat(deadLetters.redrive(taskIdOf(instanceId, taskKey))).isInstanceOf(Redriven.class);
    }

    private Optional<DispatchedTask> claim(String type) {
        return dispatch.tryClaim(WORKER, List.of(type));
    }

    private UUID taskIdOf(UUID instanceId, String taskKey) {
        return db.fetchOne(
                        "select id from task_instances where workflow_instance_id = ? and task_key = ?",
                        instanceId,
                        taskKey)
                .get("id", UUID.class);
    }

    private List<String> eventTypes(UUID instanceId) {
        return db
                .fetch("select type from events where workflow_instance_id = ? order by id", instanceId)
                .getValues("type", String.class);
    }

    private UUID submit(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var created = rest.postForEntity("/workflows", new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(created.getBody().get("instanceId").asText());
    }

    private String instanceStatus(UUID instanceId) {
        var view = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        return view.getBody().get("status").asText();
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
