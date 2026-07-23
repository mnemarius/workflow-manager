package com.workflowmanager.engine.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class DagPlannerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DagPlanner planner = new DagPlanner();

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void plan_singleTaskWithType_producesOneReadyTask() {
        var dag = parse("{\"tasks\":[{\"key\":\"step1\",\"type\":\"echo\",\"input\":{\"msg\":\"hi\"}}]}");
        var tasks = planner.plan(dag, null);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).key()).isEqualTo("step1");
        assertThat(tasks.get(0).type()).isEqualTo("echo");
        assertThat(tasks.get(0).inputJson()).contains("\"msg\"");
        assertThat(tasks.get(0).retryPolicy()).isEqualTo(RetryPolicy.DEFAULT);
    }

    @Test
    void plan_taskWithoutType_defaultsTypeToKey() {
        var dag = parse("{\"tasks\":[{\"key\":\"step1\"}]}");
        var tasks = planner.plan(dag, "{\"wf\":true}");
        assertThat(tasks.get(0).type()).isEqualTo("step1");
        assertThat(tasks.get(0).inputJson()).isEqualTo("{\"wf\":true}");
    }

    @Test
    void plan_taskWithoutDependsOn_defaultsToEmptyList() {
        var dag = parse("{\"tasks\":[{\"key\":\"step1\"}]}");
        var tasks = planner.plan(dag, null);
        assertThat(tasks.get(0).dependsOn()).isEmpty();
    }

    @Test
    void plan_taskWithDependsOn_parsesKeys() {
        var dag =
                parse(
                        "{\"tasks\":[{\"key\":\"a\"},{\"key\":\"b\"},"
                                + "{\"key\":\"c\",\"dependsOn\":[\"a\",\"b\"]}]}");
        var tasks = planner.plan(dag, null);
        assertThat(tasks.get(0).dependsOn()).isEmpty();
        assertThat(tasks.get(1).dependsOn()).isEmpty();
        assertThat(tasks.get(2).dependsOn()).containsExactly("a", "b");
    }

    @Test
    void plan_taskWithoutDelay_hasNullDelaySeconds() {
        var dag = parse("{\"tasks\":[{\"key\":\"step1\"}]}");
        var tasks = planner.plan(dag, null);
        assertThat(tasks.get(0).delaySeconds()).isNull();
    }

    @Test
    void plan_taskWithDelay_parsesDelaySeconds() {
        var dag = parse("{\"tasks\":[{\"key\":\"wait\",\"delaySeconds\":86400}]}");
        var tasks = planner.plan(dag, null);
        assertThat(tasks.get(0).delaySeconds()).isEqualTo(86400);
    }

    @Test
    void plan_taskWithRetryPolicy_parsesPolicy() {
        var dag =
                parse(
                        "{\"tasks\":[{\"key\":\"step1\",\"retryPolicy\":"
                                + "{\"maxAttempts\":2,\"backoffStrategy\":\"exponential\"}}]}");
        var tasks = planner.plan(dag, null);
        assertThat(tasks.get(0).retryPolicy())
                .isEqualTo(new RetryPolicy(2, RetryPolicy.BackoffStrategy.EXPONENTIAL, 5, 300));
    }
}
