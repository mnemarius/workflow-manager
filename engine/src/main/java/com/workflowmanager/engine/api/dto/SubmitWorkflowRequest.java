package com.workflowmanager.engine.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code input} is optional; {@code dag} is validated against the DAG JSON Schema (ADR 0003). */
public record SubmitWorkflowRequest(String name, Integer version, JsonNode dag, JsonNode input) {}
