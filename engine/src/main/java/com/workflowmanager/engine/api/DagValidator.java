package com.workflowmanager.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Validates a submitted DAG against the M1 JSON Schema at the API boundary (ADR 0003). */
@Component
public class DagValidator {

    private final JsonSchema schema;

    public DagValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(VersionFlag.V202012);
        try (InputStream in = getClass().getResourceAsStream("/schema/dag.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("dag.schema.json not found on classpath");
            }
            this.schema = factory.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return human-readable validation errors; empty when the DAG is valid. */
    public List<String> validate(JsonNode dag) {
        if (dag == null || dag.isNull()) {
            return List.of("dag is required");
        }
        return schema.validate(dag).stream().map(ValidationMessage::getMessage).sorted().toList();
    }
}
