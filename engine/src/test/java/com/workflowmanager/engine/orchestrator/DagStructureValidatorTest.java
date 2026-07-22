package com.workflowmanager.engine.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DagStructureValidatorTest {

    private final DagStructureValidator validator = new DagStructureValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    private List<String> validate(String json) {
        try {
            JsonNode dag = mapper.readTree(json);
            return validator.validate(dag);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void validLinearChain_hasNoErrors() {
        var errors =
                validate(
                        """
                        { "tasks": [
                          { "key": "a" },
                          { "key": "b", "dependsOn": ["a"] },
                          { "key": "c", "dependsOn": ["b"] }
                        ] }
                        """);
        assertThat(errors).isEmpty();
    }

    @Test
    void validDiamond_hasNoErrors() {
        var errors =
                validate(
                        """
                        { "tasks": [
                          { "key": "a" },
                          { "key": "b", "dependsOn": ["a"] },
                          { "key": "c", "dependsOn": ["a"] },
                          { "key": "d", "dependsOn": ["b", "c"] }
                        ] }
                        """);
        assertThat(errors).isEmpty();
    }

    @Test
    void duplicateKeys_reported() {
        var errors =
                validate(
                        """
                        { "tasks": [
                          { "key": "a" },
                          { "key": "a" }
                        ] }
                        """);
        assertThat(errors).anyMatch(e -> e.contains("duplicate task key"));
    }

    @Test
    void danglingDependency_reported() {
        var errors =
                validate(
                        """
                        { "tasks": [
                          { "key": "a", "dependsOn": ["ghost"] }
                        ] }
                        """);
        assertThat(errors).anyMatch(e -> e.contains("unknown task") && e.contains("ghost"));
    }

    @Test
    void selfDependency_reported() {
        var errors =
                validate(
                        """
                        { "tasks": [
                          { "key": "a", "dependsOn": ["a"] }
                        ] }
                        """);
        assertThat(errors).anyMatch(e -> e.contains("depends on itself"));
    }

    @Test
    void twoNodeCycle_reported() {
        var errors =
                validate(
                        """
                        { "tasks": [
                          { "key": "a", "dependsOn": ["b"] },
                          { "key": "b", "dependsOn": ["a"] }
                        ] }
                        """);
        assertThat(errors).anyMatch(e -> e.contains("cycle"));
        assertThat(errors).anyMatch(e -> e.contains("a") || e.contains("b"));
    }

    @Test
    void threeNodeCycle_reported() {
        var errors =
                validate(
                        """
                        { "tasks": [
                          { "key": "a", "dependsOn": ["c"] },
                          { "key": "b", "dependsOn": ["a"] },
                          { "key": "c", "dependsOn": ["b"] }
                        ] }
                        """);
        assertThat(errors).anyMatch(e -> e.contains("cycle"));
        assertThat(errors).anyMatch(e -> e.contains("a") || e.contains("b") || e.contains("c"));
    }
}
