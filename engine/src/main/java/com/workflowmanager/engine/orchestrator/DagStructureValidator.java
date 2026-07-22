package com.workflowmanager.engine.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates graph-structure rules the JSON Schema cannot express: unique task keys, dependency
 * references that resolve, no self-dependencies, and acyclicity (ADR 0003). Pure logic — no DB, no
 * Spring beans touched. Assumes the DAG shape is already schema-valid at the API boundary.
 */
@Component
public class DagStructureValidator {

    /** @return human-readable structure errors; empty when the DAG is structurally sound. */
    public List<String> validate(JsonNode dag) {
        List<String> errors = new ArrayList<>();
        if (dag == null || dag.get("tasks") == null) {
            return errors;
        }

        // Collect keys, detecting duplicates.
        Set<String> keys = new HashSet<>();
        for (JsonNode node : dag.get("tasks")) {
            String key = node.get("key").asText();
            if (!keys.add(key)) {
                errors.add("duplicate task key: " + key);
            }
        }

        // Build the dependency graph; validate references and self-dependencies along the way.
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (JsonNode node : dag.get("tasks")) {
            String key = node.get("key").asText();
            List<String> deps = new ArrayList<>();
            JsonNode dependsOn = node.get("dependsOn");
            if (dependsOn != null) {
                for (JsonNode dep : dependsOn) {
                    String depKey = dep.asText();
                    if (depKey.equals(key)) {
                        errors.add("task depends on itself: " + key);
                    } else if (!keys.contains(depKey)) {
                        errors.add("task " + key + " depends on unknown task: " + depKey);
                    } else {
                        deps.add(depKey);
                    }
                }
            }
            edges.putIfAbsent(key, deps);
        }

        errors.addAll(detectCycle(edges));
        return errors;
    }

    /**
     * Kahn's topological sort: repeatedly remove nodes whose dependencies are all satisfied. Any
     * node left unresolved is part of a cycle.
     */
    private List<String> detectCycle(Map<String, List<String>> edges) {
        Map<String, Integer> remaining = new HashMap<>();
        for (Map.Entry<String, List<String>> e : edges.entrySet()) {
            remaining.put(e.getKey(), e.getValue().size());
        }

        Deque<String> ready = new ArrayDeque<>();
        remaining.forEach(
                (key, count) -> {
                    if (count == 0) {
                        ready.add(key);
                    }
                });

        int resolved = 0;
        while (!ready.isEmpty()) {
            String done = ready.poll();
            resolved++;
            for (Map.Entry<String, List<String>> e : edges.entrySet()) {
                if (e.getValue().contains(done)) {
                    int count = remaining.get(e.getKey()) - 1;
                    remaining.put(e.getKey(), count);
                    if (count == 0) {
                        ready.add(e.getKey());
                    }
                }
            }
        }

        if (resolved == edges.size()) {
            return List.of();
        }
        List<String> stuck =
                remaining.entrySet().stream().filter(e -> e.getValue() > 0).map(e -> e.getKey()).sorted().toList();
        return List.of("dependency cycle involving task: " + stuck.get(0));
    }
}
