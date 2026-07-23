package com.workflowmanager.engine.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a validated DAG document into the tasks to create. Pure logic — no DB, no Spring beans
 * touched. The DAG shape is guaranteed by the JSON Schema at the API boundary (ADR 0003).
 * Each task's optional {@code dependsOn} edges are parsed into {@link PlannedTask#dependsOn()};
 * a task with no dependencies starts READY, one with dependencies starts PENDING. An optional
 * {@code delaySeconds} offsets when the task becomes claimable once READY (ADR 0011).
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
            Integer delaySeconds =
                    node.hasNonNull("delaySeconds") ? node.get("delaySeconds").asInt() : null;
            List<String> dependsOn = new ArrayList<>();
            JsonNode dependsOnNode = node.get("dependsOn");
            if (dependsOnNode != null && dependsOnNode.isArray()) {
                for (JsonNode dep : dependsOnNode) {
                    dependsOn.add(dep.asText());
                }
            }
            tasks.add(
                    new PlannedTask(
                            key,
                            type,
                            input,
                            retryPolicy,
                            timeoutSeconds,
                            delaySeconds,
                            List.copyOf(dependsOn)));
        }
        return List.copyOf(tasks);
    }
}
