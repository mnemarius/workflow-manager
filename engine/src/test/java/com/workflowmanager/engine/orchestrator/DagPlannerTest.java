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
        assertThat(tasks.get(0).maxAttempts()).isEqualTo(1);
    }

    @Test
    void plan_taskWithoutType_defaultsTypeToKey() {
        var dag = parse("{\"tasks\":[{\"key\":\"step1\"}]}");
        var tasks = planner.plan(dag, "{\"wf\":true}");
        assertThat(tasks.get(0).type()).isEqualTo("step1");
        assertThat(tasks.get(0).inputJson()).isEqualTo("{\"wf\":true}");
    }
}
