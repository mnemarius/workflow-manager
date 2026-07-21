package com.workflowmanager.engine.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a validated DAG document into the tasks to create. Pure logic — no DB, no Spring beans
 * touched. The DAG shape is guaranteed by the JSON Schema at the API boundary (ADR 0003).
 * M1 has no dependency edges: every task starts READY.
 */
@Component
public class DagPlanner {

    public List<PlannedTask> plan(JsonNode dag, String workflowInputJson) {
        List<PlannedTask> tasks = new ArrayList<>();
        for (JsonNode node : dag.get("tasks")) {
            String key = node.get("key").asText();
            String type = node.hasNonNull("type") ? node.get("type").asText() : key;
            String input = node.hasNonNull("input") ? node.get("input").toString() : workflowInputJson;
            RetryPolicy retryPolicy = RetryPolicy.from(node.get("retryPolicy"));
            Integer timeoutSeconds =
                    node.hasNonNull("timeoutSeconds") ? node.get("timeoutSeconds").asInt() : null;
            tasks.add(new PlannedTask(key, type, input, retryPolicy, timeoutSeconds));
        }
        return List.copyOf(tasks);
    }
}
