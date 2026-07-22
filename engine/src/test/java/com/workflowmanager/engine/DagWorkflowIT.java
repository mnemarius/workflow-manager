package com.workflowmanager.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
 * M3 milestone demo, automated: a diamond DAG (A -> {B, C} -> D) actually flows. Drives the engine
 * through {@link TaskDispatchService}/{@link TaskCompletionService} directly (rather than a live
 * worker loop) so the fan-in can be observed deterministically: D must not become claimable until
 * BOTH B and C have SUCCEEDED.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class DagWorkflowIT {

    private static final String WORKER = "it-worker";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired TaskDispatchService dispatch;
    @Autowired TaskCompletionService completion;
    @Autowired DSLContext db;

    @Test
    void diamondDag_fanInPromotesDependentOnlyWhenAllParentsSucceed() {
        // A -> {B, C} -> D. Each task has a distinct type so we can claim a specific one by capability.
        String dag =
                "{\"tasks\":["
                        + "{\"key\":\"A\",\"type\":\"a\"},"
                        + "{\"key\":\"B\",\"type\":\"b\",\"dependsOn\":[\"A\"]},"
                        + "{\"key\":\"C\",\"type\":\"c\",\"dependsOn\":[\"A\"]},"
                        + "{\"key\":\"D\",\"type\":\"d\",\"dependsOn\":[\"B\",\"C\"]}"
                        + "]}";
        String body = "{\"name\":\"diamond\",\"version\":1,\"dag\":" + dag + "}";
        UUID instanceId = submit(body);

        // On submit: only A (no deps) is READY; B, C, D wait as PENDING.
        assertThat(statusOf(instanceId, "A")).isEqualTo("READY");
        assertThat(statusOf(instanceId, "B")).isEqualTo("PENDING");
        assertThat(statusOf(instanceId, "C")).isEqualTo("PENDING");
        assertThat(statusOf(instanceId, "D")).isEqualTo("PENDING");

        // Nothing but A is claimable yet — B/C/D are PENDING, not dispatchable.
        assertThat(claim("b")).isEmpty();
        assertThat(claim("c")).isEmpty();
        assertThat(claim("d")).isEmpty();

        // Run A. Its success promotes B and C (both parents = {A} done) but NOT D (needs B and C too).
        runToSuccess("a");
        assertThat(statusOf(instanceId, "A")).isEqualTo("SUCCEEDED");
        assertThat(statusOf(instanceId, "B")).isEqualTo("READY");
        assertThat(statusOf(instanceId, "C")).isEqualTo("READY");
        assertThat(statusOf(instanceId, "D")).isEqualTo("PENDING");
        assertThat(claim("d")).as("D must not be claimable before B and C succeed").isEmpty();

        // Run B. D still blocked on C.
        runToSuccess("b");
        assertThat(statusOf(instanceId, "B")).isEqualTo("SUCCEEDED");
        assertThat(statusOf(instanceId, "D")).isEqualTo("PENDING");
        assertThat(claim("d")).as("D must not be claimable with only B succeeded").isEmpty();

        // Run C. Fan-in complete: D is promoted to READY.
        runToSuccess("c");
        assertThat(statusOf(instanceId, "C")).isEqualTo("SUCCEEDED");
        assertThat(statusOf(instanceId, "D")).isEqualTo("READY");

        // Workflow not done while D is still open.
        assertThat(instanceStatus(instanceId)).isNotEqualTo("SUCCEEDED");

        // Run D. Nothing left to promote, zero open tasks -> workflow SUCCEEDED.
        runToSuccess("d");
        assertThat(statusOf(instanceId, "D")).isEqualTo("SUCCEEDED");
        assertThat(instanceStatus(instanceId)).isEqualTo("SUCCEEDED");

        // Workflow output aggregates the DAG's sinks keyed by task_key (M3 Step 5). D is the lone sink
        // (A, B, C are all depended on), so the output is {"D": <D's output>} — not A/B/C's.
        JsonNode output = instanceOutput(instanceId);
        assertThat(output.fieldNames()).toIterable().containsExactly("D");
        assertThat(output.get("D").get("ok").asBoolean()).isTrue();

        // Event ordering: A succeeds, then B and C become READY, then D becomes READY only after
        // both B and C have succeeded.
        List<String> events =
                db.fetch(
                                "select type from events where workflow_instance_id = ? order by id",
                                instanceId)
                        .getValues("type", String.class);
        assertThat(events).containsSubsequence("TASK_SUCCEEDED", "TASK_READY", "TASK_READY");
        long readyCount = events.stream().filter("TASK_READY"::equals).count();
        assertThat(readyCount).as("B, C and D each promoted exactly once").isEqualTo(3);
        // D's promotion (last TASK_READY) comes after three task successes (A, B, C).
        int lastReady = events.lastIndexOf("TASK_READY");
        long successesBeforeLastReady =
                events.subList(0, lastReady).stream().filter("TASK_SUCCEEDED"::equals).count();
        assertThat(successesBeforeLastReady)
                .as("D promoted only after A, B and C succeeded")
                .isEqualTo(3);
    }

    private void runToSuccess(String type) {
        DispatchedTask task = claim(type).orElseThrow(() -> new AssertionError("no claimable " + type));
        boolean applied = completion.complete(task.taskId(), WORKER, true, "{\"ok\":true}", null);
        assertThat(applied).isTrue();
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

    private String instanceStatus(UUID instanceId) {
        var view = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        return view.getBody().get("status").asText();
    }

    private JsonNode instanceOutput(UUID instanceId) {
        var view = rest.getForEntity("/workflows/" + instanceId, JsonNode.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        return view.getBody().get("output");
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
